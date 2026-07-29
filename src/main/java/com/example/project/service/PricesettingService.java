package com.example.project.service;

import com.example.project.constant.TaxRevenueGroup;
import com.example.project.dto.response.PriceSettingBatchPointResponse;
import com.example.project.dto.response.PriceSettingDetailResponse;
import com.example.project.dto.response.PriceSettingProductRowResponse;
import com.example.project.dto.response.PriceSettingRowResponse;
import com.example.project.dto.response.PriceSettingTaxProjectionResponse;
import com.example.project.entity.Batch;
import com.example.project.entity.Product;
import com.example.project.entity.Productunit;
import com.example.project.entity.Type;
import com.example.project.repository.BatchRepository;
import com.example.project.repository.ProductRepository;
import com.example.project.repository.ProductunitRepository;
import com.example.project.repository.TypeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * "Cài đặt giá bán" (Price Settings) — lets the Owner change any product's sell price directly
 * from one screen instead of opening each product's own edit page. Per the user's own framing
 * (2026-07-23): "cho phép thay đổi giá bán của các sản phẩm ... thay vì phải mở chi tiết của từng
 * sản phẩm". Deliberately NOT a markup/cost-based price calculator — it edits
 * {@code Productunit.sellPrice} directly; the import price shown per row is read-only reference
 * info only, never written back or used in a formula. No schema change: reuses the existing
 * {@code Productunit}/{@code Product}/{@code Batch} tables.
 *
 * <p>The screen lists <strong>products</strong>, each expanding to its unit rows (2026-07-25) — the
 * flat one-row-per-unit table got long and repetitive once products carried several units. Paging
 * therefore counts products, not units.</p>
 */
@Service
public class PricesettingService {

    /** Product name A→Z (accent-insensitive). Default when no sort is supplied. */
    public static final String SORT_NAME_ASC = "name_asc";
    /** Product name Z→A. */
    public static final String SORT_NAME_DESC = "name_desc";
    /** Base-unit sell price ascending; products with no priced unit sink to the bottom. */
    public static final String SORT_PRICE_ASC = "price_asc";
    /** Base-unit sell price descending; products with no priced unit sink to the bottom. */
    public static final String SORT_PRICE_DESC = "price_desc";

    /** Batch expiry on the detail modal; a batch with no expiry renders {@link #NO_VALUE} instead. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String NO_VALUE = "—";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ProductunitRepository productunitRepository;
    private final ProductRepository productRepository;
    private final BatchRepository batchRepository;
    private final TypeRepository typeRepository;
    /**
     * Only for {@code currentRevenueGroup()}. The detail modal must show the group actually in
     * force — the last snapshot's {@code nextPeriodTaxType} — not the raw
     * {@code Financialsetting.revenueGroup}, which is merely the seed for the very first period.
     * Reading the setting directly here would quietly disagree with the Kỳ thuế screen the moment
     * anyone closes a period with a group change.
     */
    private final TaxperiodsnapshotService taxperiodsnapshotService;

    public PricesettingService(ProductunitRepository productunitRepository,
                               ProductRepository productRepository,
                               BatchRepository batchRepository,
                               TypeRepository typeRepository,
                               TaxperiodsnapshotService taxperiodsnapshotService) {
        this.productunitRepository = productunitRepository;
        this.productRepository = productRepository;
        this.batchRepository = batchRepository;
        this.typeRepository = typeRepository;
        this.taxperiodsnapshotService = taxperiodsnapshotService;
    }

    /**
     * @param keyword matched against product code and product name only (accent-insensitive)
     * @param typeId  optional "Loại hàng" filter
     * @param sort    one of the {@code SORT_*} constants; anything unrecognised falls back to
     *                {@link #SORT_NAME_ASC}
     */
    @Transactional(readOnly = true)
    public Page<PriceSettingProductRowResponse> search(String keyword, Integer typeId, String sort,
                                                       Pageable pageable) {
        final String normalizedKeyword = normalize(keyword);

        Map<Integer, Product> productById = productRepository.findAllWithRelations().stream()
                .collect(Collectors.toMap(Product::getProductID, product -> product));

        Map<Integer, BigDecimal> averageImportByProduct = averageInStockImportPricePerProduct();

        Map<Integer, List<Productunit>> unitsByProduct = productunitRepository.findAllWithProduct().stream()
                .filter(unit -> unit.getProductID() != null)
                .filter(unit -> productById.containsKey(unit.getProductID().getProductID()))
                .collect(Collectors.groupingBy(unit -> unit.getProductID().getProductID()));

        List<PriceSettingProductRowResponse> filtered = productById.values().stream()
                .filter(product -> unitsByProduct.containsKey(product.getProductID()))
                .filter(product -> matchesType(product, typeId))
                .filter(product -> matchesKeyword(product, normalizedKeyword))
                .map(product -> toProductRow(product,
                        unitsByProduct.get(product.getProductID()),
                        averageImportByProduct.get(product.getProductID())))
                .sorted(comparatorFor(sort))
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<PriceSettingProductRowResponse> content =
                start >= filtered.size() ? List.of() : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    public List<Type> listTypes() {
        return typeRepository.findAll().stream()
                .sorted(Comparator.comparing(Type::getName))
                .toList();
    }

    /**
     * Updates a single {@code ProductUnit.sellPrice}. Each row on the screen saves independently
     * (same pattern as the Permission Table's per-cell save), so one bad value never blocks the
     * rest.
     *
     * <p><strong>Base-unit cascade:</strong> when the edited row is the product's base unit, every
     * sibling unit whose current price still exactly matches {@code oldBasePrice × ratio} is
     * recomputed to {@code newBasePrice × ratio} too — those units were never manually customized,
     * they were just following the base price. A sibling whose price does <em>not</em> match that
     * formula has clearly been hand-adjusted at some point and is left untouched. There is no
     * separate "manually overridden" flag in the schema; this is inferred purely from whether the
     * stored value still agrees with the ratio formula, so it needs no migration. Editing a
     * non-base unit never cascades to anything else.</p>
     *
     * @return how many sibling units were cascaded (0 for a non-base-unit edit or when nothing
     * qualified)
     */
    @Transactional
    public int updatePrice(Integer productUnitId, BigDecimal sellPrice) {
        if (sellPrice == null) {
            throw new IllegalArgumentException("Giá bán phải lớn hơn 0");
        }
        // Round first, then validate: an entry that rounds away to 0 must be rejected with the
        // usual message rather than silently saved as a free product.
        BigDecimal roundedPrice = roundMoney(sellPrice);
        if (roundedPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Giá bán phải lớn hơn 0");
        }
        Productunit unit = productunitRepository.findById(productUnitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn vị sản phẩm"));

        BigDecimal oldBasePrice = unit.getSellPrice();
        unit.setSellPrice(roundedPrice);
        productunitRepository.save(unit);

        if (!Boolean.TRUE.equals(unit.getIsBaseUnit()) || unit.getProductID() == null) {
            return 0;
        }
        return cascadeToSiblings(unit, oldBasePrice, roundedPrice);
    }

    private int cascadeToSiblings(Productunit baseUnit, BigDecimal oldBasePrice, BigDecimal newBasePrice) {
        List<Productunit> siblings = productunitRepository.findByProductId(baseUnit.getProductID().getProductID());

        int cascaded = 0;
        for (Productunit sibling : siblings) {
            if (sibling.getId().equals(baseUnit.getId()) || sibling.getRatio() == null
                    || sibling.getSellPrice() == null) {
                continue;
            }
            BigDecimal expectedOldPrice = roundMoney(oldBasePrice.multiply(sibling.getRatio()));
            BigDecimal currentPrice = roundMoney(sibling.getSellPrice());
            if (currentPrice.compareTo(expectedOldPrice) != 0) {
                continue; // hand-adjusted away from the ratio at some point — leave it alone
            }
            sibling.setSellPrice(roundMoney(newBasePrice.multiply(sibling.getRatio())));
            productunitRepository.save(sibling);
            cascaded++;
        }
        return cascaded;
    }

    // ------------------------------------------------------------------ detail modal

    /**
     * Everything the "Chi tiết giá &amp; thuế" modal shows for one product: its in-stock lots'
     * import prices against the current base-unit sell price, and what the revenue group in force
     * does to that margin.
     *
     * <p>Fetched on demand (one product at a time) rather than rendered with the list — the list
     * page would otherwise run a batch query per row for a panel most visits never open.</p>
     *
     * @throws IllegalArgumentException when the product does not exist
     */
    @Transactional(readOnly = true)
    public PriceSettingDetailResponse getDetail(Integer productId) {
        Product product = productRepository.findDetailById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm"));

        Productunit baseUnit = resolveBaseUnit(productId);
        BigDecimal sellPricePerBase = baseUnit == null || baseUnit.getSellPrice() == null
                ? null : roundMoney(baseUnit.getSellPrice());
        BigDecimal vatRatePercent = resolveVatRateSnapshot(product);

        List<Batch> inStock = batchRepository.findInStockBatchesByProduct(productId).stream()
                .filter(batch -> batch.getImportPricePerBase() != null)
                .toList();

        List<PriceSettingBatchPointResponse> batches = inStock.stream()
                .map(batch -> toBatchPoint(batch, sellPricePerBase, vatRatePercent))
                .toList();

        List<BigDecimal> importPrices = batches.stream()
                .map(PriceSettingBatchPointResponse::getImportPricePerBase)
                .toList();
        BigDecimal average = mean(importPrices);

        return new PriceSettingDetailResponse(
                product.getProductID(),
                product.getCode(),
                product.getName(),
                product.getTypeID() != null ? product.getTypeID().getName() : NO_VALUE,
                baseUnit != null ? baseUnit.getUnitName() : NO_VALUE,
                sellPricePerBase,
                average,
                importPrices.stream().min(Comparator.naturalOrder()).orElse(null),
                importPrices.stream().max(Comparator.naturalOrder()).orElse(null),
                inStock.stream()
                        .map(Batch::getStorageQuantity)
                        .filter(Objects::nonNull)
                        .mapToLong(Integer::longValue)
                        .sum(),
                batches,
                projectTax(sellPricePerBase, average, vatRatePercent, vatRateSource(product)));
    }

    /**
     * Tax on <strong>one base unit</strong> under the group in force, branch for branch the same as
     * {@code TaxperiodsnapshotService.computePeriod()} — see
     * {@link PriceSettingTaxProjectionResponse} for the table and for the two caveats this cannot
     * avoid (period-level operating costs, and per-invoice deductibility).
     *
     * <p>Both {@code sellPrice} and {@code importPrice} are GROSS, which is exactly how the period
     * computation treats revenue ({@code Invoice.total}) and cost of goods sold
     * ({@code baseQtyDeducted × importPricePerBase}) — so the per-unit figures add up to the
     * quarterly ones rather than merely resembling them.</p>
     */
    private PriceSettingTaxProjectionResponse projectTax(BigDecimal sellPrice, BigDecimal importPrice,
                                                         BigDecimal vatRatePercent, String vatRateSource) {
        Integer group = taxperiodsnapshotService.currentRevenueGroup();
        boolean exempt = TaxRevenueGroup.isTaxExempt(group);
        boolean deduction = TaxRevenueGroup.isDeductionGroup(group);
        boolean direct = !exempt && !deduction;

        // An unknown figure stays null all the way to the screen and renders "—". Substituting zero
        // would turn "chưa có lô tồn nên chưa biết giá vốn" into "giá vốn bằng 0", which reads as
        // pure profit — the one wrong answer this panel must never give.
        boolean priceKnown = sellPrice != null;
        boolean costKnown = importPrice != null;
        boolean marginKnown = priceKnown && costKnown;

        BigDecimal outputVat;
        BigDecimal inputVat;
        BigDecimal incomeTaxBase;
        BigDecimal incomeTaxRate;
        BigDecimal incomeTax;
        String formula;
        String caveat;

        if (exempt) {
            // Nothing is declared, so these are genuine zeros rather than unknowns.
            outputVat = BigDecimal.ZERO;
            inputVat = BigDecimal.ZERO;
            incomeTaxBase = BigDecimal.ZERO;
            incomeTaxRate = BigDecimal.ZERO;
            incomeTax = BigDecimal.ZERO;
            formula = "Nhóm 1 miễn thuế hoàn toàn — không kê khai GTGT lẫn TNCN.";
            caveat = null;
        } else if (direct) {
            // The percentage method never looks at cost, so an unknown cost does not make the tax
            // unknown — only the margin below it.
            outputVat = priceKnown ? sellPrice.multiply(TaxRevenueGroup.DIRECT_VAT_RATE) : null;
            inputVat = BigDecimal.ZERO;
            incomeTaxBase = priceKnown ? sellPrice : null;
            incomeTaxRate = TaxRevenueGroup.DIRECT_PIT_RATE;
            incomeTax = priceKnown ? sellPrice.multiply(incomeTaxRate) : null;
            formula = "Nhóm 2 tính thẳng trên doanh thu: GTGT = giá bán × 1%, TNCN = giá bán × 0,5%.";
            caveat = "Giá vốn không ảnh hưởng tới số thuế — lô nhập đắt hay rẻ vẫn nộp bằng nhau, "
                    + "nên chênh lệch giá nhập rơi hết vào lợi nhuận.";
        } else {
            outputVat = priceKnown ? embeddedVat(sellPrice, vatRatePercent) : null;
            inputVat = costKnown ? embeddedVat(importPrice, vatRatePercent) : null;
            // A loss-making unit owes no TNCN; it does not create a negative tax (same as the period).
            incomeTaxBase = marginKnown ? sellPrice.subtract(importPrice).max(BigDecimal.ZERO) : null;
            incomeTaxRate = TaxRevenueGroup.DEDUCTION_PIT_RATE;
            incomeTax = incomeTaxBase == null ? null : incomeTaxBase.multiply(incomeTaxRate);
            formula = "Nhóm 3 khấu trừ: GTGT phải nộp = GTGT đầu ra − GTGT đầu vào; "
                    + "TNCN = 15% × (giá bán − giá vốn).";
            caveat = "Ước tính trên 1 đơn vị: chi phí vận hành (điện, nước, lương) chỉ trừ được ở "
                    + "cấp kỳ thuế nên TNCN thực tế sẽ THẤP HƠN số này; GTGT đầu vào cũng chỉ được "
                    + "khấu trừ nếu phiếu nhập của lô đủ điều kiện (Điều 26 NĐ 181/2025).";
        }

        BigDecimal vatPayable = subtractIfKnown(outputVat, inputVat);
        BigDecimal totalTax = addIfKnown(vatPayable, incomeTax);
        BigDecimal grossMargin = marginKnown ? sellPrice.subtract(importPrice) : null;
        BigDecimal netProfit = subtractIfKnown(grossMargin, totalTax);

        if (!costKnown) {
            caveat = "Sản phẩm chưa có lô nào còn tồn nên chưa biết giá vốn — phần chênh lệch, lợi "
                    + "nhuận" + (deduction ? " và GTGT đầu vào" : "") + " để trống thay vì tính bằng 0.";
        }

        return new PriceSettingTaxProjectionResponse(
                group,
                TaxRevenueGroup.label(group),
                exempt,
                deduction,
                direct,
                scale2(vatRatePercent),
                vatRateSource,
                deduction,
                costKnown,
                nullableScale2(sellPrice),
                nullableScale2(importPrice),
                nullableScale2(outputVat),
                nullableScale2(inputVat),
                nullableScale2(vatPayable),
                nullableScale2(incomeTaxBase),
                percent(incomeTaxRate),
                nullableScale2(incomeTax),
                nullableScale2(totalTax),
                nullableScale2(grossMargin),
                nullableScale2(netProfit),
                formula,
                caveat);
    }

    /** {@code a − b}, or {@code null} when either side is unknown — "unknown" must not decay to 0. */
    private BigDecimal subtractIfKnown(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.subtract(b);
    }

    /** {@code a + b}, or {@code null} when either side is unknown. */
    private BigDecimal addIfKnown(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.add(b);
    }

    private PriceSettingBatchPointResponse toBatchPoint(Batch batch, BigDecimal sellPricePerBase,
                                                        BigDecimal vatRatePercent) {
        BigDecimal importPrice = roundMoney(batch.getImportPricePerBase());
        BigDecimal margin = sellPricePerBase == null ? null : sellPricePerBase.subtract(importPrice);
        BigDecimal marginPercent = margin == null || sellPricePerBase.signum() == 0
                ? null
                : margin.multiply(HUNDRED).divide(sellPricePerBase, 1, RoundingMode.HALF_UP);

        return new PriceSettingBatchPointResponse(
                batch.getId(),
                batch.getBatchCode(),
                blankToDash(batch.getLotNumber()),
                batch.getExpirationDate() == null ? NO_VALUE : DATE.format(batch.getExpirationDate()),
                batch.getStorageQuantity(),
                importPrice,
                scale2(embeddedVat(importPrice, vatRatePercent)),
                margin,
                marginPercent);
    }

    /**
     * The VAT already inside a GROSS amount: {@code gross × rate / (100 + rate)}. The inverse of
     * {@code InvoiceService.calculateSaleLinePreTaxAmount}, which divides by {@code 1 + rate/100} —
     * both express the same convention, that stored prices include VAT.
     */
    private BigDecimal embeddedVat(BigDecimal gross, BigDecimal vatRatePercent) {
        BigDecimal rate = zeroIfNull(vatRatePercent);
        if (gross == null || gross.signum() <= 0 || rate.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return gross.multiply(rate).divide(HUNDRED.add(rate), 2, RoundingMode.HALF_UP);
    }

    /**
     * Product VAT rate, mirroring {@code InvoiceService.resolveVatRateSnapshot} and
     * {@code StockadjustmentService.resolveVatRateSnapshot}: the product's own override if set,
     * otherwise its type's default, otherwise 0. <strong>The three must agree</strong> — this one
     * only projects, but a projection that disagrees with what the sale will actually charge is
     * worse than no projection.
     */
    private BigDecimal resolveVatRateSnapshot(Product product) {
        if (product.getVatRateOverride() != null) {
            return product.getVatRateOverride();
        }
        Type type = product.getTypeID();
        if (type != null && type.getDefaultVATRate() != null) {
            return type.getDefaultVATRate();
        }
        return BigDecimal.ZERO;
    }

    /** Why the rate is what it is — {@code Product.vatRateOverride} has no UI, so this is the only place it shows. */
    private String vatRateSource(Product product) {
        if (product.getVatRateOverride() != null) {
            return "Thuế suất riêng của sản phẩm";
        }
        Type type = product.getTypeID();
        if (type != null && type.getDefaultVATRate() != null) {
            return "Thuế suất mặc định của loại hàng" + (type.getName() == null ? "" : " · " + type.getName());
        }
        return "Chưa khai báo thuế suất — tạm tính 0%";
    }

    /**
     * The product's base unit: the row flagged {@code isBaseUnit}, falling back to the smallest
     * ratio for the (data-error) case of a product with no base unit flagged — the same fallback
     * shape {@link #representativePrice} already uses for the sort key.
     */
    private Productunit resolveBaseUnit(Integer productId) {
        List<Productunit> units = productunitRepository.findByProductId(productId);
        return units.stream()
                .filter(unit -> Boolean.TRUE.equals(unit.getIsBaseUnit()))
                .findFirst()
                .orElseGet(() -> units.stream()
                        .min(Comparator.comparing(Productunit::getRatio,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .orElse(null));
    }

    private BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 0, RoundingMode.HALF_UP);
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? NO_VALUE : value;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Tax figures keep 2 decimals like every other module — only the editable price is whole đồng. */
    private BigDecimal scale2(BigDecimal value) {
        return zeroIfNull(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Like {@link #scale2} but keeps {@code null} as {@code null} — an unknown, not a zero. */
    private BigDecimal nullableScale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** A stored rate ({@code 0.005}) as the number a screen shows ({@code 0.50}). */
    private BigDecimal percent(BigDecimal rate) {
        return zeroIfNull(rate).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Money on this screen carries no decimals — every amount is rounded to a whole đồng with
     * HALF_UP (…,3 → down; …,5 → up), per the user's 2026-07-25 call. The DB columns stay
     * {@code decimal(15,2)}; the fractional part is simply always zero from here on. Rows written
     * before this change keep their stored decimals until the next save, but are displayed rounded.
     */
    private BigDecimal roundMoney(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ sorting

    private Comparator<PriceSettingProductRowResponse> comparatorFor(String sort) {
        Comparator<PriceSettingProductRowResponse> byName =
                Comparator.comparing(row -> normalize(row.getProductName()));

        return switch (sort == null ? "" : sort) {
            case SORT_NAME_DESC -> byName.reversed();
            case SORT_PRICE_ASC -> Comparator
                    .comparing(PriceSettingProductRowResponse::getBasePrice,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(byName);
            case SORT_PRICE_DESC -> Comparator
                    .comparing(PriceSettingProductRowResponse::getBasePrice,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(byName);
            default -> byName;
        };
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Arithmetic mean ("trung bình cộng") of {@code importPricePerBase} per product, over that
     * product's <strong>in-stock</strong> batches only — a fully-sold-out lot no longer says
     * anything about what the stock on hand cost. Products with no in-stock batch are simply absent
     * from the map (the row then renders "Chưa có lô tồn").
     */
    private Map<Integer, BigDecimal> averageInStockImportPricePerProduct() {
        Map<Integer, List<BigDecimal>> pricesByProduct = batchRepository.findAll().stream()
                .filter(batch -> batch.getProductID() != null)
                .filter(batch -> batch.getImportPricePerBase() != null)
                .filter(this::isInStock)
                .collect(Collectors.groupingBy(
                        batch -> batch.getProductID().getProductID(),
                        Collectors.mapping(Batch::getImportPricePerBase, Collectors.toList())));

        return pricesByProduct.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    List<BigDecimal> prices = entry.getValue();
                    BigDecimal sum = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    return sum.divide(BigDecimal.valueOf(prices.size()), 0, RoundingMode.HALF_UP);
                }));
    }

    /** {@code status} is nullable in the schema and defaults to true, so only an explicit false deactivates. */
    private boolean isInStock(Batch batch) {
        return batch.getStorageQuantity() != null
                && batch.getStorageQuantity() > 0
                && !Boolean.FALSE.equals(batch.getStatus());
    }

    private boolean matchesType(Product product, Integer typeId) {
        if (typeId == null) {
            return true;
        }
        return product.getTypeID() != null && typeId.equals(product.getTypeID().getId());
    }

    private boolean matchesKeyword(Product product, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        return containsNormalized(product.getCode(), normalizedKeyword)
                || containsNormalized(product.getName(), normalizedKeyword);
    }

    private PriceSettingProductRowResponse toProductRow(Product product, List<Productunit> units,
                                                        BigDecimal averageImportPrice) {
        List<PriceSettingRowResponse> unitRows = units.stream()
                .sorted(Comparator.comparing(Productunit::getRatio,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(unit -> new PriceSettingRowResponse(
                        unit.getId(),
                        unit.getUnitName(),
                        Boolean.TRUE.equals(unit.getIsBaseUnit()),
                        unit.getRatio(),
                        // Displayed without decimals even for rows stored before the rounding rule.
                        unit.getSellPrice() == null ? null : roundMoney(unit.getSellPrice())))
                .toList();

        return new PriceSettingProductRowResponse(
                product.getProductID(),
                product.getCode(),
                product.getName(),
                product.getTypeID() != null ? product.getTypeID().getName() : "—",
                averageImportPrice,
                representativePrice(unitRows),
                unitRows);
    }

    /**
     * Sort key for the price asc/desc options: the base unit's price, since every other unit is
     * derived from it by ratio. Falls back to the first priced unit for the (data-error) case of a
     * product with no base unit flagged.
     */
    private BigDecimal representativePrice(List<PriceSettingRowResponse> unitRows) {
        return unitRows.stream()
                .filter(PriceSettingRowResponse::isBaseUnit)
                .map(PriceSettingRowResponse::getCurrentSellPrice)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> unitRows.stream()
                        .map(PriceSettingRowResponse::getCurrentSellPrice)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null));
    }

    private boolean containsNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).contains(normalizedKeyword);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replace("Đ", "D").replace("đ", "d");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }
}
