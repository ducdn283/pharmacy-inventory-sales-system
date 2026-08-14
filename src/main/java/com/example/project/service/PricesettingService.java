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
 * "Cài đặt giá bán" — cho Chủ nhà thuốc sửa giá bán sản phẩm ngay trên một màn hình, không cần mở
 * từng trang chi tiết. Đây là công cụ sửa giá bán trực tiếp ({@code Productunit.sellPrice}),
 * <strong>không phải</strong> máy tính markup — giá nhập hiển thị chỉ để tham khảo, không bao giờ
 * ghi ngược lại hay dùng trong công thức tính giá bán.
 *
 * <p>Màn hình liệt kê theo <strong>sản phẩm</strong>, mở rộng ra mới thấy từng đơn vị — phân trang
 * cũng tính theo số sản phẩm, không tính theo số đơn vị.</p>
 */
@Service
public class PricesettingService {

    /** Tên sản phẩm A→Z (không phân biệt dấu). Mặc định khi không truyền sort. */
    public static final String SORT_NAME_ASC = "name_asc";
    /** Tên sản phẩm Z→A. */
    public static final String SORT_NAME_DESC = "name_desc";
    /** Giá bán đơn vị cơ bản tăng dần; sản phẩm chưa có giá xếp cuối. */
    public static final String SORT_PRICE_ASC = "price_asc";
    /** Giá bán đơn vị cơ bản giảm dần; sản phẩm chưa có giá xếp cuối. */
    public static final String SORT_PRICE_DESC = "price_desc";

    /** Định dạng hạn dùng trên modal chi tiết; lô không có hạn dùng hiện {@link #NO_VALUE}. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String NO_VALUE = "—";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ProductunitRepository productunitRepository;
    private final ProductRepository productRepository;
    private final BatchRepository batchRepository;
    private final TypeRepository typeRepository;
    /** Dùng để lấy nhóm doanh thu đang áp dụng thực tế (không đọc thẳng {@code Financialsetting.revenueGroup}). */
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
     * Tìm/lọc/sắp xếp danh sách sản phẩm cho màn Cài đặt giá bán.
     *
     * @param keyword khớp theo mã sản phẩm và tên sản phẩm (không phân biệt dấu)
     * @param typeId  lọc theo loại hàng (tuỳ chọn)
     * @param sort    một trong các hằng số {@code SORT_*}; giá trị lạ dùng {@link #SORT_NAME_ASC}
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

    // Danh sách loại hàng để đổ vào bộ lọc, sắp xếp theo tên.
    public List<Type> listTypes() {
        return typeRepository.findAll().stream()
                .sorted(Comparator.comparing(Type::getName))
                .toList();
    }

    /**
     * Cập nhật giá bán của một đơn vị sản phẩm ({@code ProductUnit.sellPrice}).
     *
     * <p><strong>Cascade từ đơn vị cơ bản:</strong> nếu đơn vị đang sửa là đơn vị cơ bản, mọi đơn
     * vị khác đang có giá đúng bằng {@code giá cũ × tỷ lệ quy đổi} sẽ được cập nhật theo
     * {@code giá mới × tỷ lệ quy đổi} — vì các đơn vị đó chưa từng bị sửa tay, vẫn đang bám theo
     * giá cơ bản. Đơn vị nào có giá không khớp công thức coi như đã bị sửa tay riêng, giữ nguyên
     * không đụng tới. Không có cờ "đã sửa tay" riêng trong DB — suy ra hoàn toàn từ việc giá còn
     * khớp công thức tỷ lệ hay không. Sửa một đơn vị không phải đơn vị cơ bản thì không cascade.</p>
     *
     * @return số đơn vị khác được cascade theo (0 nếu không phải đơn vị cơ bản hoặc không có đơn
     * vị nào khớp công thức)
     */
    @Transactional
    public int updatePrice(Integer productUnitId, BigDecimal sellPrice) {
        if (sellPrice == null) {
            throw new IllegalArgumentException("Giá bán phải lớn hơn 0");
        }
        // Làm tròn trước rồi mới kiểm tra: giá trị làm tròn về 0 vẫn phải bị từ chối.
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

    /** Kết quả lưu giá cho cả sản phẩm — xem {@link #updatePrices}. */
    public record PriceUpdateResult(int explicitCount, int cascadedCount) {
    }

    /**
     * Lưu giá của mọi đơn vị đã thay đổi trong một sản phẩm, chỉ một lần gọi (một nút "Lưu" cho
     * cả sản phẩm thay vì từng đơn vị riêng).
     *
     * <p>Đơn vị nào giá gửi lên không đổi so với DB thì bỏ qua. Nếu trong lần lưu có sửa cả đơn vị
     * cơ bản, đơn vị đó luôn được áp dụng <strong>sau cùng</strong> để cascade sang các đơn vị
     * khác hoạt động đúng — nhờ vậy, nếu người dùng cũng gõ giá riêng cho một đơn vị khác trong
     * cùng lần lưu, giá riêng đó vẫn được giữ (lúc cascade chạy, giá đơn vị đó đã không còn khớp
     * công thức tỷ lệ cũ nữa nên cascade tự động bỏ qua).</p>
     *
     * @param sellPriceByUnitId giá gửi lên của từng dòng, khoá theo {@code Productunit.id}; đơn vị
     *                          không có trong map (hoặc giá trị {@code null}) thì giữ nguyên
     * @return số đơn vị được sửa trực tiếp, và số đơn vị được cascade theo
     */
    @Transactional
    public PriceUpdateResult updatePrices(Integer productId, Map<Integer, BigDecimal> sellPriceByUnitId) {
        List<Productunit> units = productunitRepository.findByProductId(productId);
        if (units.isEmpty()) {
            throw new IllegalArgumentException("Không tìm thấy sản phẩm");
        }

        List<Productunit> changed = units.stream()
                .filter(unit -> isPriceChanged(unit, sellPriceByUnitId.get(unit.getId())))
                // false < true, nên dòng đơn vị cơ bản luôn xếp sau các dòng còn lại.
                .sorted(Comparator.comparing(unit -> Boolean.TRUE.equals(unit.getIsBaseUnit())))
                .toList();

        int explicitCount = 0;
        int cascadedCount = 0;
        for (Productunit unit : changed) {
            cascadedCount += updatePrice(unit.getId(), sellPriceByUnitId.get(unit.getId()));
            explicitCount++;
        }

        return new PriceUpdateResult(explicitCount, cascadedCount);
    }

    // So sánh giá gửi lên với giá đang lưu để biết đơn vị này có thực sự cần cập nhật hay không.
    private boolean isPriceChanged(Productunit unit, BigDecimal submitted) {
        if (submitted == null) {
            return false;
        }
        BigDecimal current = unit.getSellPrice();
        return current == null || roundMoney(current).compareTo(roundMoney(submitted)) != 0;
    }

    // Cập nhật giá các đơn vị anh em còn bám theo công thức tỷ lệ cũ của đơn vị cơ bản; trả về số đơn vị đã cascade.
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
                continue; // đã bị sửa tay khác công thức tỷ lệ — giữ nguyên
            }
            sibling.setSellPrice(roundMoney(newBasePrice.multiply(sibling.getRatio())));
            productunitRepository.save(sibling);
            cascaded++;
        }
        return cascaded;
    }

    // ------------------------------------------------------------------ modal chi tiết

    /**
     * Toàn bộ dữ liệu cho modal "Chi tiết giá &amp; thuế" của một sản phẩm: giá nhập các lô còn
     * tồn so với giá bán đơn vị cơ bản hiện tại, và thuế theo nhóm doanh thu đang áp dụng.
     *
     * @throws IllegalArgumentException nếu không tìm thấy sản phẩm
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
     * Tính thuế cho <strong>một đơn vị cơ bản</strong> theo nhóm doanh thu đang áp dụng, cùng công
     * thức với {@code TaxperiodsnapshotService.computePeriod()}. Xem
     * {@link PriceSettingTaxProjectionResponse} để biết bảng công thức và các giới hạn của con số
     * này (chưa tính chi phí vận hành theo kỳ, chưa xét điều kiện khấu trừ theo từng hóa đơn).
     *
     * <p>{@code sellPrice} và {@code importPrice} đều là giá GROSS, cùng cách tính doanh thu
     * ({@code Invoice.total}) và giá vốn ({@code baseQtyDeducted × importPricePerBase}) mà kỳ thuế
     * dùng, nên số của một đơn vị cộng dồn đúng vào số của cả kỳ.</p>
     */
    private PriceSettingTaxProjectionResponse projectTax(BigDecimal sellPrice, BigDecimal importPrice,
                                                         BigDecimal vatRatePercent, String vatRateSource) {
        Integer group = taxperiodsnapshotService.currentRevenueGroup();
        boolean exempt = TaxRevenueGroup.isTaxExempt(group);
        // Nhóm 2 và nhóm 3 đều tính GTGT trực tiếp trên doanh thu, không còn nhánh khấu trừ riêng.
        boolean direct = !exempt;

        // Giá trị chưa biết giữ null tới tận màn hình, hiện "—" — không mặc định về 0 vì sẽ đọc
        // nhầm thành lợi nhuận bằng cả giá bán.
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
            // Không kê khai gì cả nên đây là số 0 thật, không phải "chưa biết".
            outputVat = BigDecimal.ZERO;
            inputVat = BigDecimal.ZERO;
            incomeTaxBase = BigDecimal.ZERO;
            incomeTaxRate = BigDecimal.ZERO;
            incomeTax = BigDecimal.ZERO;
            formula = "Nhóm 1 miễn thuế hoàn toàn — không kê khai GTGT lẫn TNCN.";
            caveat = null;
        } else {
            // Tính thuế theo % doanh thu nên không phụ thuộc giá vốn, áp dụng chung cho nhóm 2 và 3.
            outputVat = priceKnown ? sellPrice.multiply(TaxRevenueGroup.DIRECT_VAT_RATE) : null;
            inputVat = BigDecimal.ZERO;
            incomeTaxBase = priceKnown ? sellPrice : null;
            incomeTaxRate = TaxRevenueGroup.DIRECT_PIT_RATE;
            incomeTax = priceKnown ? sellPrice.multiply(incomeTaxRate) : null;
            formula = "Tính trực tiếp trên doanh thu: GTGT = giá bán × 1%, TNCN = giá bán × 0,5%.";
            caveat = "Giá vốn không ảnh hưởng tới số thuế — lô nhập đắt hay rẻ vẫn nộp bằng nhau, "
                    + "nên chênh lệch giá nhập rơi hết vào lợi nhuận.";
            // Panel này luôn chiếu theo công thức doanh thu × 0,5%, chưa hỗ trợ phương án TNCN
            // theo lợi nhuận của nhóm 2 (Financialsetting.taxCalculationMethod).
        }

        BigDecimal vatPayable = subtractIfKnown(outputVat, inputVat);
        BigDecimal totalTax = addIfKnown(vatPayable, incomeTax);
        BigDecimal grossMargin = marginKnown ? sellPrice.subtract(importPrice) : null;
        BigDecimal netProfit = subtractIfKnown(grossMargin, totalTax);

        if (!costKnown) {
            caveat = "Sản phẩm chưa có lô nào còn tồn nên chưa biết giá vốn — phần chênh lệch để "
                    + "trống thay vì tính bằng 0.";
        }

        return new PriceSettingTaxProjectionResponse(
                group,
                TaxRevenueGroup.label(group),
                exempt,
                // Luôn false: không nhóm nào còn tách riêng GTGT đầu vào theo lô.
                false,
                direct,
                scale2(vatRatePercent),
                vatRateSource,
                // Luôn false: thuế suất GTGT riêng của sản phẩm không ảnh hưởng số thuế ở mọi nhóm.
                false,
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

    /** {@code a − b}, hoặc {@code null} nếu một trong hai chưa biết. */
    private BigDecimal subtractIfKnown(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.subtract(b);
    }

    /** {@code a + b}, hoặc {@code null} nếu một trong hai chưa biết. */
    private BigDecimal addIfKnown(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.add(b);
    }

    // Chuyển một lô hàng còn tồn thành một điểm dữ liệu (giá nhập, chênh lệch, thuế GTGT ẩn) cho biểu đồ chi tiết.
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

    /** Thuế GTGT đã nằm trong một số tiền GROSS: {@code gross × rate / (100 + rate)}. */
    private BigDecimal embeddedVat(BigDecimal gross, BigDecimal vatRatePercent) {
        BigDecimal rate = zeroIfNull(vatRatePercent);
        if (gross == null || gross.signum() <= 0 || rate.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return gross.multiply(rate).divide(HUNDRED.add(rate), 2, RoundingMode.HALF_UP);
    }

    /** Thuế suất GTGT của sản phẩm: ưu tiên override riêng, không thì lấy mặc định của loại hàng, không có thì 0. */
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

    /** Giải thích nguồn gốc thuế suất, hiển thị cho người dùng. */
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

    /** Đơn vị cơ bản của sản phẩm (đơn vị có cờ {@code isBaseUnit}); nếu thiếu dữ liệu thì lấy đơn vị có tỷ lệ nhỏ nhất. */
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

    // Trung bình cộng danh sách số tiền, làm tròn về đồng chẵn; trả về null nếu danh sách rỗng.
    private BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 0, RoundingMode.HALF_UP);
    }

    // Trả về giá trị gốc, hoặc dấu "—" nếu chuỗi rỗng/null.
    private String blankToDash(String value) {
        return value == null || value.isBlank() ? NO_VALUE : value;
    }

    // Trả về 0 nếu giá trị null, dùng để tránh NullPointerException khi tính toán.
    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Số liệu thuế giữ 2 số lẻ như các module khác — chỉ riêng giá bán trên màn này làm tròn đồng chẵn. */
    private BigDecimal scale2(BigDecimal value) {
        return zeroIfNull(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Giống {@link #scale2} nhưng giữ nguyên {@code null} — chưa biết chứ không phải bằng 0. */
    private BigDecimal nullableScale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Đổi tỷ lệ lưu trong DB ({@code 0.005}) thành số phần trăm hiển thị ({@code 0.50}). */
    private BigDecimal percent(BigDecimal rate) {
        return zeroIfNull(rate).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    /** Giá bán trên màn này không có phần thập phân — làm tròn về đồng chẵn theo HALF_UP. */
    private BigDecimal roundMoney(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ sắp xếp

    // Chọn comparator theo tham số sort (tên/giá, tăng/giảm); giá trị lạ mặc định sắp theo tên.
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

    // ------------------------------------------------------------------ helper

    /**
     * Trung bình cộng {@code importPricePerBase} theo từng sản phẩm, chỉ tính trên các lô
     * <strong>còn tồn kho</strong>. Sản phẩm không còn lô tồn thì không có trong map (hiển thị
     * "Chưa có lô tồn").
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

    /** {@code status} có thể null trong DB (coi như true) — chỉ giá trị false rõ ràng mới coi là ngừng hoạt động. */
    private boolean isInStock(Batch batch) {
        return batch.getStorageQuantity() != null
                && batch.getStorageQuantity() > 0
                && !Boolean.FALSE.equals(batch.getStatus());
    }

    // Kiểm tra sản phẩm có thuộc loại hàng đang lọc hay không (không lọc nếu typeId null).
    private boolean matchesType(Product product, Integer typeId) {
        if (typeId == null) {
            return true;
        }
        return product.getTypeID() != null && typeId.equals(product.getTypeID().getId());
    }

    // Kiểm tra sản phẩm có khớp từ khoá tìm kiếm (theo mã hoặc tên, không dấu) hay không.
    private boolean matchesKeyword(Product product, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        return containsNormalized(product.getCode(), normalizedKeyword)
                || containsNormalized(product.getName(), normalizedKeyword);
    }

    // Dựng một dòng sản phẩm cho danh sách, kèm các đơn vị đã sắp xếp theo tỷ lệ quy đổi.
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

    /** Giá đại diện để sắp xếp theo giá: giá của đơn vị cơ bản, hoặc đơn vị đầu tiên có giá nếu thiếu dữ liệu. */
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

    // Kiểm tra chuỗi (đã chuẩn hoá) có chứa từ khoá (đã chuẩn hoá) hay không.
    private boolean containsNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).contains(normalizedKeyword);
    }

    // Chuẩn hoá chuỗi để so khớp không phân biệt dấu/hoa-thường (bỏ dấu tiếng Việt, đổi "đ"→"d").
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
