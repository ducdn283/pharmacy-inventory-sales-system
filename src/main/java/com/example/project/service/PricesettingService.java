package com.example.project.service;

import com.example.project.dto.response.PriceSettingProductRowResponse;
import com.example.project.dto.response.PriceSettingRowResponse;
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

    private final ProductunitRepository productunitRepository;
    private final ProductRepository productRepository;
    private final BatchRepository batchRepository;
    private final TypeRepository typeRepository;

    public PricesettingService(ProductunitRepository productunitRepository,
                               ProductRepository productRepository,
                               BatchRepository batchRepository,
                               TypeRepository typeRepository) {
        this.productunitRepository = productunitRepository;
        this.productRepository = productRepository;
        this.batchRepository = batchRepository;
        this.typeRepository = typeRepository;
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
