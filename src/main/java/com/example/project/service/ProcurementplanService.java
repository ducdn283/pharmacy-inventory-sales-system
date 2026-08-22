package com.example.project.service;

import com.example.project.dto.request.ProcurementPlanCreateRequest;
import com.example.project.dto.request.ProcurementPlanDetailCreateRequest;
import com.example.project.dto.response.ProcurementPlanPrintLineResponse;
import com.example.project.dto.response.ProcurementPlanPrintPageResponse;
import com.example.project.dto.response.ProcurementProductSearchResponse;
import com.example.project.dto.response.ProcurementProductStockResponse;
import com.example.project.dto.response.ProcurementProductUnitResponse;
import com.example.project.dto.response.ProcurementSupplierSearchResponse;
import com.example.project.dto.response.ProcurementplanResponse;
import com.example.project.entity.Procurementplan;
import com.example.project.entity.Procurementplandetail;
import com.example.project.entity.Product;
import com.example.project.entity.Productunit;
import com.example.project.entity.Supplier;
import com.example.project.entity.Supplierproduct;
import com.example.project.repository.BatchRepository;
import com.example.project.repository.ProcurementplanRepository;
import com.example.project.repository.ProcurementplandetailRepository;
import com.example.project.repository.ProductRepository;
import com.example.project.repository.ProductunitRepository;
import com.example.project.repository.SupplierRepository;
import com.example.project.repository.SupplierproductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Nghiệp vụ dự trù mua hàng ({@link Procurementplan}) phục vụ màn Owner/Accountant
 * ({@code /owner/procurements/**}, {@code /accountant/procurements/**}).
 */
@Service
public class ProcurementplanService {
    private static final String DEFAULT_STATUS = "Đang thực hiện";
    private static final String COMPLETED_STATUS = "Đã hoàn thành";
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<String> ALLOWED_STATUSES = Set.of(DEFAULT_STATUS, COMPLETED_STATUS);

    private final ProcurementplanRepository procurementplanRepository;
    private final ProcurementplandetailRepository procurementplandetailRepository;
    private final ProductRepository productRepository;
    private final ProductunitRepository productunitRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierproductRepository supplierproductRepository;
    private final BatchRepository batchRepository;

    public ProcurementplanService(ProcurementplanRepository procurementplanRepository,
                                  ProcurementplandetailRepository procurementplandetailRepository,
                                  ProductRepository productRepository,
                                  ProductunitRepository productunitRepository,
                                  SupplierRepository supplierRepository,
                                  SupplierproductRepository supplierproductRepository,
                                  BatchRepository batchRepository) {
        this.procurementplanRepository = procurementplanRepository;
        this.procurementplandetailRepository = procurementplandetailRepository;
        this.productRepository = productRepository;
        this.productunitRepository = productunitRepository;
        this.supplierRepository = supplierRepository;
        this.supplierproductRepository = supplierproductRepository;
        this.batchRepository = batchRepository;
    }


    /**
     * Danh sách dự trù có phân trang, lọc theo mã, khoảng ngày và trạng thái.
     * Lọc trong bộ nhớ vì cần so khớp mã linh hoạt.
     */
    @Transactional(readOnly = true)
    public Page<ProcurementplanResponse> list(String search,
                                              String fromDate,
                                              String toDate,
                                              String status,
                                              Pageable pageable) {
        String keyword = search == null ? "" : search.trim();
        LocalDate from = parseDate(fromDate);
        LocalDate to = parseDate(toDate);
        String normalizedStatus = status == null ? "" : status.trim();

        List<ProcurementplanResponse> filtered = procurementplanRepository.findAll(
                        Sort.by(Sort.Direction.DESC, "date").and(Sort.by(Sort.Direction.DESC, "id")))
                .stream()
                .filter(plan -> keyword.isEmpty()
                        || (plan.getProcurementCode() != null
                        && plan.getProcurementCode().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))))
                .filter(plan -> matchesDate(plan, from, to))
                .filter(plan -> normalizedStatus.isEmpty() || normalizedStatus.equals(plan.getStatus()))
                .map(ProcurementplanResponse::from)
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());

        List<ProcurementplanResponse> content = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    /** Các trạng thái hợp lệ — dropdown lọc / form tạo-sửa. */
    @Transactional(readOnly = true)
    public List<String> listStatuses() {
        return List.copyOf(ALLOWED_STATUSES);
    }

    /** Kiểm tra phiếu dự trù đã ở trạng thái hoàn thành chưa — dùng để khóa form sửa. */
    @Transactional(readOnly = true)
    public boolean isCompleted(Integer id) {
        return procurementplanRepository.findById(id)
                .map(plan -> COMPLETED_STATUS.equals(plan.getStatus()))
                .orElse(false);
    }

    /** Tổng số phiếu dự trù — thẻ thống kê màn danh sách. */
    @Transactional(readOnly = true)
    public long countAll() {
        return procurementplanRepository.count();
    }

    /** Số phiếu dự trù đã hoàn thành — thẻ thống kê. */
    @Transactional(readOnly = true)
    public long countCompleted() {
        return procurementplanRepository.countByStatus(COMPLETED_STATUS);
    }

    /** Số phiếu dự trù đang thực hiện — thẻ thống kê. */
    @Transactional(readOnly = true)
    public long countInProgress() {
        return procurementplanRepository.countByStatus(DEFAULT_STATUS);
    }

    /** Chi tiết một phiếu dự trù — form xem/sửa. */
    @Transactional(readOnly = true)
    public ProcurementplanResponse getById(Integer id) {
        return procurementplanRepository.findById(id)
                .map(ProcurementplanResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dự trù mua hàng"));
    }

    /** Map entity sang {@link ProcurementPlanCreateRequest} — điền form cập nhật. */
    @Transactional(readOnly = true)
    public ProcurementPlanCreateRequest buildUpdateForm(Integer id) {
        Procurementplan plan = procurementplanRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dự trù mua hàng"));

        ProcurementPlanCreateRequest form = new ProcurementPlanCreateRequest();
        form.setNote(plan.getNote());
        form.setStatus(plan.getStatus());

        List<ProcurementPlanDetailCreateRequest> details = new ArrayList<>();
        for (Procurementplandetail detail : procurementplandetailRepository.findByProcurementID_Id(id)) {
            ProcurementPlanDetailCreateRequest item = new ProcurementPlanDetailCreateRequest();
            item.setProductId(detail.getProductID().getProductID());
            item.setRequestedQuantity(detail.getRequestedQuantity());
            item.setUnit(detail.getUnit());
            item.setEstimatedPrice(detail.getEstimatedPrice());
            if (detail.getSupplierID() != null) {
                item.setSupplierId(detail.getSupplierID().getId());
            }
            details.add(item);
        }
        form.setDetails(details);
        return form;
    }

    /** Dữ liệu in phiếu dự trù — header, dòng chi tiết và tổng giá ước tính. */
    @Transactional(readOnly = true)
    public ProcurementPlanPrintPageResponse getPrintPage(Integer id) {
        Procurementplan plan = procurementplanRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dự trù mua hàng"));

        List<Procurementplandetail> details = procurementplandetailRepository.findByProcurementID_IdWithRelations(id);
        Map<Integer, Productunit> baseUnitByProduct = loadBaseUnitByProduct();
        Map<Integer, Productunit> mainUnitByProduct = loadMainUnitByProduct();
        Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct = loadUnitsByProduct();
        List<ProcurementPlanPrintLineResponse> lines = details.stream()
                .map(detail -> toPrintLine(detail, baseUnitByProduct, mainUnitByProduct, unitsByProduct))
                .toList();

        BigDecimal totalEstimated = details.stream()
                .map(Procurementplandetail::getEstimatedPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ProcurementPlanPrintPageResponse(
                plan.getId(),
                plan.getProcurementCode(),
                formatDateTime(plan.getDate()),
                plan.getStatus(),
                plan.getNote(),
                lines.size(),
                totalEstimated,
                lines
        );
    }

    /** Map một dòng chi tiết sang DTO hiển thị trên trang in. */
    private ProcurementPlanPrintLineResponse toPrintLine(Procurementplandetail detail,
                                                         Map<Integer, Productunit> baseUnitByProduct,
                                                         Map<Integer, Productunit> mainUnitByProduct,
                                                         Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct) {
        Product product = detail.getProductID();
        Supplier supplier = detail.getSupplierID();
        Integer quantity = detail.getRequestedQuantity();
        BigDecimal estimatedPrice = detail.getEstimatedPrice();
        BigDecimal unitPrice = null;

        if (estimatedPrice != null && quantity != null && quantity > 0) {
            unitPrice = estimatedPrice.divide(BigDecimal.valueOf(quantity.longValue()), 2, RoundingMode.HALF_UP);
        }

        String stockUnit = null;
        String unitConversionHint = null;
        if (product != null && product.getProductID() != null) {
            Productunit baseUnit = baseUnitByProduct.get(product.getProductID());
            stockUnit = baseUnit != null ? baseUnit.getUnitName() : null;
            Productunit mainUnit = mainUnitByProduct.get(product.getProductID());
            unitConversionHint = buildUnitConversionChain(
                    product.getProductID(), stockUnit, mainUnit, unitsByProduct);
        }

        return new ProcurementPlanPrintLineResponse(
                product != null ? product.getCode() : "",
                product != null ? product.getName() : "Không rõ",
                detail.getCurrentStock(),
                stockUnit,
                unitConversionHint,
                quantity,
                detail.getUnit(),
                unitPrice,
                estimatedPrice,
                supplier != null ? supplier.getName() : "—"
        );
    }

    /** Chuỗi quy đổi liên tiếp, ví dụ: 1 Hộp = 10 Vỉ = 100 Viên. */
    private String buildUnitConversionChain(Integer productId,
                                            String stockUnit,
                                            Productunit mainUnit,
                                            Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct) {
        List<ProcurementProductUnitResponse> ordered =
                normalizeProductUnits(productId, stockUnit, mainUnit, unitsByProduct);
        if (ordered.size() <= 1) {
            return null;
        }

        ProcurementProductUnitResponse largest = ordered.get(ordered.size() - 1);
        StringBuilder chain = new StringBuilder("1 ").append(largest.getUnitName());

        for (int i = ordered.size() - 2; i >= 0; i--) {
            ProcurementProductUnitResponse unit = ordered.get(i);
            BigDecimal amount = largest.getRatio().divide(unit.getRatio(), 4, RoundingMode.HALF_UP);
            chain.append(" = ")
                    .append(formatUnitRatio(amount))
                    .append(' ')
                    .append(unit.getUnitName());
        }
        return chain.toString();
    }

    /** Chuẩn hóa và sắp xếp đơn vị quy đổi của sản phẩm — phục vụ chuỗi quy đổi trên trang in. */
    private List<ProcurementProductUnitResponse> normalizeProductUnits(Integer productId,
                                                                       String stockUnit,
                                                                       Productunit mainUnit,
                                                                       Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct) {
        Map<String, ProcurementProductUnitResponse> byName = new LinkedHashMap<>();
        for (ProcurementProductUnitResponse unitRow : unitsByProduct.getOrDefault(productId, List.of())) {
            String unitName = unitRow.getUnitName();
            BigDecimal ratio = unitRow.getRatio();
            if (unitName == null || unitName.isBlank() || ratio == null || ratio.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            ProcurementProductUnitResponse existing = byName.get(unitName);
            if (existing == null || ratio.compareTo(existing.getRatio()) > 0) {
                byName.put(unitName, unitRow);
            }
        }

        List<ProcurementProductUnitResponse> ordered = new ArrayList<>(byName.values());
        ordered.sort(Comparator
                .comparing(ProcurementProductUnitResponse::getRatio, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ProcurementProductUnitResponse::getUnitName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        if (!ordered.isEmpty()) {
            return ordered;
        }

        if (mainUnit == null) {
            return List.of();
        }

        String unit = mainUnit.getUnitName();
        BigDecimal ratio = mainUnit.getRatio();
        if (unit == null || unit.isBlank() || ratio == null || ratio.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }
        if (stockUnit != null && unit.equals(stockUnit) && ratio.compareTo(BigDecimal.ONE) == 0) {
            return List.of();
        }

        return List.of(new ProcurementProductUnitResponse(
                unit, ratio, Boolean.TRUE.equals(mainUnit.getIsBaseUnit())));
    }

    /** Định dạng tỷ lệ quy đổi đơn vị (bỏ số 0 thừa). */
    private String formatUnitRatio(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal normalized = value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return normalized.toPlainString();
    }

    /** Tìm sản phẩm theo prefix mã/tên/barcode — autocomplete trên form dự trù. */
    @Transactional(readOnly = true)
    public List<ProcurementProductSearchResponse> searchProducts(String keyword, int limit) {
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isBlank()) {
            return List.of();
        }

        int maxResults = limit <= 0 ? 12 : Math.min(limit, 30);
        Map<Integer, Long> stockByProduct = buildStockByProduct();
        Map<Integer, Productunit> mainUnitByProduct = loadMainUnitByProduct();
        Map<Integer, Productunit> baseUnitByProduct = loadBaseUnitByProduct();
        Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct = loadUnitsByProduct();
        Map<Integer, PreferredSupplierOption> preferredSupplierByProduct = loadPreferredSupplierByProduct();

        return productRepository.findAllWithRelations()
                .stream()
                .filter(product -> Boolean.TRUE.equals(product.getStatus()))
                .filter(product -> matchesKeyword(product, normalizedKeyword))
                .sorted(Comparator.comparing(product -> product.getName() == null ? "" : product.getName()))
                .limit(maxResults)
                .map(product -> toSearchResponse(product, stockByProduct, mainUnitByProduct, baseUnitByProduct,
                        unitsByProduct, preferredSupplierByProduct))
                .toList();
    }

    /** Danh sách tồn kho tất cả sản phẩm (dùng modal "Xem tồn sản phẩm" trên form tạo dự trù). */
    @Transactional(readOnly = true)
    public List<ProcurementProductStockResponse> listAllProductStocks(String stockSort) {
        Map<Integer, Long> stockByProduct = buildStockByProduct();
        Map<Integer, Productunit> mainUnitByProduct = loadMainUnitByProduct();
        Map<Integer, Productunit> baseUnitByProduct = loadBaseUnitByProduct();
        Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct = loadUnitsByProduct();
        Map<Integer, PreferredSupplierOption> preferredSupplierByProduct = loadPreferredSupplierByProduct();
        boolean ascending = "asc".equalsIgnoreCase(normalize(stockSort));

        Comparator<Product> byStock = ascending
                ? Comparator.comparing(product -> stockByProduct.getOrDefault(product.getProductID(), 0L))
                : Comparator.<Product, Long>comparing(product -> stockByProduct.getOrDefault(product.getProductID(), 0L)).reversed();

        return productRepository.findAllWithRelations().stream()
                .filter(product -> Boolean.TRUE.equals(product.getStatus()))
                .sorted(byStock.thenComparing(product -> product.getName() == null ? "" : product.getName()))
                .map(product -> toStockResponse(
                        product, stockByProduct, mainUnitByProduct, baseUnitByProduct, unitsByProduct,
                        preferredSupplierByProduct))
                .toList();
    }

    /** Sản phẩm đang hết hoặc sắp hết hàng, dùng cho bước chọn trước khi tạo phiếu dự trù. */
    @Transactional(readOnly = true)
    public List<ProcurementProductStockResponse> listRestockNeededProducts() {
        return listAllProductStocks("asc").stream()
                .filter(product -> {
                    int stock = product.getCurrentStock() == null ? 0 : product.getCurrentStock();
                    int minStock = product.getMinStock() == null ? 0 : product.getMinStock();
                    return stock <= minStock;
                })
                .toList();
    }

    /** Map {@link Product} sang DTO tồn kho cho modal "Xem tồn sản phẩm". */
    private ProcurementProductStockResponse toStockResponse(Product product,
                                                            Map<Integer, Long> stockByProduct,
                                                            Map<Integer, Productunit> mainUnitByProduct,
                                                            Map<Integer, Productunit> baseUnitByProduct,
                                                            Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct,
                                                            Map<Integer, PreferredSupplierOption> preferredSupplierByProduct) {
        ProcurementProductSearchResponse search = toSearchResponse(
                product, stockByProduct, mainUnitByProduct, baseUnitByProduct, unitsByProduct,
                preferredSupplierByProduct);

        return new ProcurementProductStockResponse(
                search.getProductID(),
                search.getName(),
                search.getCode(),
                search.getBarcode(),
                product.getMinStock(),
                product.getMaxStock(),
                search.getCurrentStock(),
                search.getStockUnit(),
                search.getUnit(),
                search.getUnitRatio(),
                search.getEstimatedPrice(),
                search.getUnits(),
                search.getCurrentSellPrice(),
                search.getPreferredSupplierId(),
                search.getPreferredSupplierCostPrice()
        );
    }

    /**
     * ID các sản phẩm đang cần nhập thêm (tồn <= minStock), dùng cho nút "Tạo dự trù hàng cần nhập"
     * ở Danh sách hàng hóa — mở form tạo dự trù với sẵn TẤT CẢ sản phẩm này thay vì phải bấm từng
     * sản phẩm một. Loại trừ sản phẩm đã ngừng kinh doanh, cùng tiêu chí "còn hàng hay không"
     * {@link com.example.project.service.ProductService} đang dùng cho các thẻ thống kê trên màn Danh sách hàng hóa.
     */
    @Transactional(readOnly = true)
    public List<Integer> findRestockNeededProductIds() {
        Map<Integer, Long> stockByProduct = buildStockByProduct();

        return productRepository.findAllWithRelations().stream()
                .filter(product -> Boolean.TRUE.equals(product.getStatus()))
                .filter(product -> {
                    long stock = stockByProduct.getOrDefault(product.getProductID(), 0L);
                    int minStock = product.getMinStock() == null ? 0 : product.getMinStock();
                    return stock <= minStock;
                })
                .sorted(Comparator.comparing(product -> product.getName() == null ? "" : product.getName()))
                .map(Product::getProductID)
                .toList();
    }

    /** Thông tin sản phẩm đã chọn trên form — hiển thị lại các dòng chi tiết. */
    @Transactional(readOnly = true)
    public List<ProcurementProductSearchResponse> listProductsForDetails(ProcurementPlanCreateRequest form) {
        if (form == null || form.getDetails() == null) {
            return List.of();
        }

        Set<Integer> productIds = new HashSet<>();
        for (ProcurementPlanDetailCreateRequest detail : form.getDetails()) {
            if (detail.getProductId() != null) {
                productIds.add(detail.getProductId());
            }
        }

        if (productIds.isEmpty()) {
            return List.of();
        }

        Map<Integer, Long> stockByProduct = buildStockByProduct();
        Map<Integer, Productunit> mainUnitByProduct = loadMainUnitByProduct();
        Map<Integer, Productunit> baseUnitByProduct = loadBaseUnitByProduct();
        Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct = loadUnitsByProduct();
        Map<Integer, PreferredSupplierOption> preferredSupplierByProduct = loadPreferredSupplierByProduct();

        return productRepository.findAllById(productIds)
                .stream()
                .map(product -> toSearchResponse(product, stockByProduct, mainUnitByProduct, baseUnitByProduct,
                        unitsByProduct, preferredSupplierByProduct))
                .toList();
    }

    /** Tất cả đơn vị đang hoạt động của sản phẩm (bé → lớn theo ratio). */
    private Map<Integer, List<ProcurementProductUnitResponse>> loadUnitsByProduct() {
        Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct = new HashMap<>();
        for (Productunit unit : productunitRepository.findAllWithProduct()) {
            if (!Boolean.TRUE.equals(unit.getIsActive()) || unit.getProductID() == null) {
                continue;
            }

            Integer productId = unit.getProductID().getProductID();
            unitsByProduct.computeIfAbsent(productId, ignored -> new ArrayList<>())
                    .add(new ProcurementProductUnitResponse(
                            unit.getUnitName(),
                            unit.getRatio(),
                            Boolean.TRUE.equals(unit.getIsBaseUnit())));
        }

        unitsByProduct.values().forEach(units -> units.sort(Comparator
                .comparing(ProcurementProductUnitResponse::getRatio, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ProcurementProductUnitResponse::getUnitName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))));
        return unitsByProduct;
    }

    /** Đơn vị nhập mặc định (ưu tiên default/base) theo sản phẩm. */
    private Map<Integer, Productunit> loadMainUnitByProduct() {
        Map<Integer, Productunit> mainUnitByProduct = new HashMap<>();
        for (Productunit unit : productunitRepository.findAllWithProduct()) {
            if (!Boolean.TRUE.equals(unit.getIsActive()) || unit.getProductID() == null) {
                continue;
            }

            Integer productId = unit.getProductID().getProductID();
            Productunit existing = mainUnitByProduct.get(productId);
            if (existing == null || isPreferredUnit(unit, existing)) {
                mainUnitByProduct.put(productId, unit);
            }
        }
        return mainUnitByProduct;
    }

    /** Đơn vị cơ sở (nhỏ nhất) theo sản phẩm — hiển thị tồn kho. */
    private Map<Integer, Productunit> loadBaseUnitByProduct() {
        Map<Integer, Productunit> baseUnitByProduct = new HashMap<>();
        for (Productunit unit : productunitRepository.findAllWithProduct()) {
            if (!Boolean.TRUE.equals(unit.getIsActive())
                    || !Boolean.TRUE.equals(unit.getIsBaseUnit())
                    || unit.getProductID() == null) {
                continue;
            }

            Integer productId = unit.getProductID().getProductID();
            baseUnitByProduct.putIfAbsent(productId, unit);
        }
        return baseUnitByProduct;
    }

    /** Chọn đơn vị nhập ưu tiên hơn giữa hai candidate. */
    private boolean isPreferredUnit(Productunit candidate, Productunit current) {
        if (Boolean.TRUE.equals(candidate.getIsDefault()) && !Boolean.TRUE.equals(current.getIsDefault())) {
            return true;
        }
        return Boolean.TRUE.equals(candidate.getIsBaseUnit()) && !Boolean.TRUE.equals(current.getIsDefault());
    }

    /** Map {@link Product} sang DTO tìm kiếm/autocomplete trên form dự trù. */
    private ProcurementProductSearchResponse toSearchResponse(Product product,
                                                              Map<Integer, Long> stockByProduct,
                                                              Map<Integer, Productunit> mainUnitByProduct,
                                                              Map<Integer, Productunit> baseUnitByProduct,
                                                              Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct,
                                                              Map<Integer, PreferredSupplierOption> preferredSupplierByProduct) {
        Productunit mainUnit = mainUnitByProduct.get(product.getProductID());
        Productunit baseUnit = baseUnitByProduct.get(product.getProductID());
        int stock = stockByProduct.getOrDefault(product.getProductID(), 0L).intValue();
        List<ProcurementProductUnitResponse> units = unitsByProduct.getOrDefault(product.getProductID(), List.of());
        PreferredSupplierOption preferred = preferredSupplierByProduct.get(product.getProductID());

        return new ProcurementProductSearchResponse(
                product.getProductID(),
                product.getName(),
                product.getCode(),
                product.getBarcode(),
                stock,
                product.getMaxStock(),
                baseUnit != null ? baseUnit.getUnitName() : null,
                mainUnit != null ? mainUnit.getUnitName() : null,
                mainUnit != null ? mainUnit.getRatio() : null,
                mainUnit != null ? mainUnit.getSellPrice() : null,
                units,
                mainUnit != null ? mainUnit.getSellPrice() : null,
                preferred != null ? preferred.supplierId() : null,
                preferred != null ? preferred.costPrice() : null
        );
    }

    /** NCC ưu tiên (isPreferred) còn active theo sản phẩm — tự chọn khi thêm dòng dự trù. */
    private Map<Integer, PreferredSupplierOption> loadPreferredSupplierByProduct() {
        Map<Integer, PreferredSupplierOption> preferredByProduct = new HashMap<>();
        for (Supplierproduct supplierProduct : supplierproductRepository.findByIsPreferredTrue()) {
            if (!isActiveSupplierProduct(supplierProduct)) {
                continue;
            }

            Product product = supplierProduct.getProductID();
            Supplier supplier = supplierProduct.getSupplierID();
            if (product == null || product.getProductID() == null || supplier == null || supplier.getId() == null) {
                continue;
            }

            preferredByProduct.putIfAbsent(product.getProductID(), new PreferredSupplierOption(
                    supplier.getId(),
                    supplierProduct.getCostPrice()));
        }
        return preferredByProduct;
    }

    private record PreferredSupplierOption(Integer supplierId, BigDecimal costPrice) {}

    /** Kiểm tra sản phẩm khớp prefix mã, tên hoặc barcode. */
    private boolean matchesKeyword(Product product, String normalizedKeyword) {
        return startsWithNormalized(product.getCode(), normalizedKeyword)
                || startsWithNormalized(product.getName(), normalizedKeyword)
                || startsWithNormalized(product.getBarcode(), normalizedKeyword);
    }

    /** So khớp đầu chuỗi sau khi chuẩn hóa — dùng cho tìm kiếm prefix. */
    private boolean startsWithNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).startsWith(normalizedKeyword);
    }

    /** So khớp chuỗi con sau khi chuẩn hóa — tìm nhà cung cấp. */
    private boolean containsNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).contains(normalizedKeyword);
    }

    /** Chuẩn hóa chuỗi tìm kiếm: bỏ dấu tiếng Việt, chữ thường. */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replace("Đ", "D").replace("đ", "d");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    /** Giá nhập của nhà cung cấp cho sản phẩm — từ bảng {@code Supplierproduct}. */
    @Transactional(readOnly = true)
    public BigDecimal getSupplierCostPrice(Integer supplierId, Integer productId) {
        if (supplierId == null || productId == null) {
            return null;
        }

        return supplierproductRepository.findBySupplierID_IdAndProductID_ProductID(supplierId, productId)
                .filter(this::isActiveSupplierProduct)
                .map(Supplierproduct::getCostPrice)
                .orElse(null);
    }

    /** Kiểm tra liên kết nhà cung cấp–sản phẩm còn active. */
    private boolean isActiveSupplierProduct(Supplierproduct supplierProduct) {
        return supplierProduct.getIsActive() == null || Boolean.TRUE.equals(supplierProduct.getIsActive());
    }

    /** Danh sách nhà cung cấp — dropdown trên form dự trù. */
    @Transactional(readOnly = true)
    public List<Supplier> listSuppliers() {
        return supplierRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(supplier -> supplier.getName() == null ? "" : supplier.getName()))
                .toList();
    }

    /** Tìm nhà cung cấp theo tên; ẩn NCC có liên kết supplierproduct bị deactive với sản phẩm đã chọn. */
    @Transactional(readOnly = true)
    public List<ProcurementSupplierSearchResponse> searchSuppliersForProduct(Integer productId, String keyword) {
        String normalizedKeyword = normalize(keyword);
        Set<Integer> deactivatedSupplierIds = loadDeactivatedSupplierIds(productId);

        return supplierRepository.findAll()
                .stream()
                .filter(supplier -> normalizedKeyword.isEmpty()
                        || containsNormalized(supplier.getName(), normalizedKeyword))
                .filter(supplier -> supplier.getId() == null
                        || !deactivatedSupplierIds.contains(supplier.getId()))
                .sorted(Comparator.comparing(supplier -> supplier.getName() == null ? "" : supplier.getName()))
                .map(supplier -> new ProcurementSupplierSearchResponse(
                        supplier.getId(),
                        supplier.getName(),
                        productId != null ? getSupplierCostPrice(supplier.getId(), productId) : null
                ))
                .toList();
    }

    /** Id các NCC bị deactive (isActive = 0) cho sản phẩm — loại khỏi modal chọn NCC. */
    private Set<Integer> loadDeactivatedSupplierIds(Integer productId) {
        if (productId == null) {
            return Set.of();
        }

        Set<Integer> deactivated = new HashSet<>();
        for (Supplierproduct supplierProduct : supplierproductRepository.findByProductID_ProductID(productId)) {
            if (Boolean.FALSE.equals(supplierProduct.getIsActive())) {
                Supplier supplier = supplierProduct.getSupplierID();
                if (supplier != null && supplier.getId() != null) {
                    deactivated.add(supplier.getId());
                }
            }
        }
        return deactivated;
    }

    /** Tổng tồn kho theo sản phẩm — gom từ các lô ({@link com.example.project.entity.Batch}). */
    @Transactional(readOnly = true)
    public Map<Integer, Long> buildStockByProduct() {
        Map<Integer, Long> stockByProduct = new HashMap<>();
        for (Object[] row : batchRepository.sumStorageGroupedByProduct()) {
            stockByProduct.put((Integer) row[0], (Long) row[1]);
        }
        return stockByProduct;
    }

    /** Tạo phiếu dự trù mới — sinh mã {@code DT-xxxxxx} và lưu chi tiết. */
    @Transactional
    public Integer create(ProcurementPlanCreateRequest request) {
        List<ProcurementPlanDetailCreateRequest> details = normalizeDetails(request);
        validateCreateRequest(details);

        LocalDateTime now = LocalDateTime.now(VN_ZONE);
        Procurementplan plan = new Procurementplan();
        plan.setProcurementCode(generateProcurementCode());
        plan.setDate(now);
        plan.setStatus(DEFAULT_STATUS);
        plan.setNote(trimToNull(request.getNote()));
        plan.setCreatedAt(now);

        Procurementplan savedPlan = procurementplanRepository.save(plan);
        saveDetails(savedPlan, details);

        return savedPlan.getId();
    }

    /** Cập nhật phiếu dự trù — thay toàn bộ dòng chi tiết, không sửa phiếu đã hoàn thành. */
    @Transactional
    public void update(Integer id, ProcurementPlanCreateRequest request) {
        Procurementplan plan = procurementplanRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dự trù mua hàng"));
        ensureNotCompleted(plan);

        List<ProcurementPlanDetailCreateRequest> details = normalizeDetails(request);
        validateCreateRequest(details);

        plan.setNote(trimToNull(request.getNote()));
        String status = normalizeStatus(request.getStatus());
        if (COMPLETED_STATUS.equals(status)) {
            validateReadyForCompletion(details);
        }
        plan.setStatus(status);
        plan.setDate(LocalDateTime.now(VN_ZONE));
        procurementplanRepository.save(plan);

        procurementplandetailRepository.deleteByProcurementID_Id(id);
        saveDetails(plan, details);
    }

    /** Xóa phiếu dự trù và toàn bộ dòng chi tiết — không xóa phiếu đã hoàn thành. */
    @Transactional
    public void delete(Integer id) {
        if (id == null) {
            throw new IllegalArgumentException("Không tìm thấy dự trù mua hàng");
        }

        Procurementplan plan = procurementplanRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dự trù mua hàng"));
        ensureNotCompleted(plan);

        procurementplandetailRepository.deleteByProcurementID_Id(id);
        procurementplanRepository.deleteById(id);
    }

    /** Lưu các dòng chi tiết dự trù kèm tồn hiện tại và giá ước tính. */
    private void saveDetails(Procurementplan plan, List<ProcurementPlanDetailCreateRequest> details) {
        Map<Integer, Productunit> mainUnitByProduct = loadMainUnitByProduct();
        for (ProcurementPlanDetailCreateRequest item : details) {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm"));

            Supplier supplier = null;
            if (item.getSupplierId() != null) {
                supplier = supplierRepository.findById(item.getSupplierId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp"));
            }

            Procurementplandetail detail = new Procurementplandetail();
            detail.setProcurementID(plan);
            detail.setProductID(product);
            detail.setRequestedQuantity(item.getRequestedQuantity());
            String requestedUnit = trimToNull(item.getUnit());
            Productunit defaultImportUnit = mainUnitByProduct.get(product.getProductID());
            detail.setUnit(requestedUnit != null
                    ? requestedUnit
                    : defaultImportUnit != null ? trimToNull(defaultImportUnit.getUnitName()) : null);
            detail.setEstimatedPrice(resolveEstimatedPrice(item));
            detail.setSupplierID(supplier);
            detail.setCurrentStock((int) batchRepository.sumStorageByProduct(product.getProductID()));

            procurementplandetailRepository.save(detail);
        }
    }

    /** Tính giá dự kiến một dòng — lấy từ form hoặc nhân giá nhập × số lượng. */
    private BigDecimal resolveEstimatedPrice(ProcurementPlanDetailCreateRequest item) {
        if (item.getEstimatedPrice() != null) {
            return item.getEstimatedPrice().setScale(2, RoundingMode.HALF_UP);
        }

        Integer supplierId = item.getSupplierId();
        Integer productId = item.getProductId();
        Integer quantity = item.getRequestedQuantity();

        if (supplierId == null || productId == null || quantity == null || quantity <= 0) {
            return null;
        }

        BigDecimal unitCost = getSupplierCostPrice(supplierId, productId);
        if (unitCost == null) {
            return null;
        }

        return unitCost.multiply(BigDecimal.valueOf(quantity.longValue()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Bỏ các dòng chưa chọn sản phẩm khỏi form trước khi lưu. */
    private List<ProcurementPlanDetailCreateRequest> normalizeDetails(ProcurementPlanCreateRequest request) {
        if (request.getDetails() == null) {
            return List.of();
        }

        return request.getDetails().stream()
                .filter(item -> item.getProductId() != null)
                .toList();
    }

    /** Validate dữ liệu trước khi tạo/cập nhật — ít nhất một dòng hợp lệ. */
    private void validateCreateRequest(List<ProcurementPlanDetailCreateRequest> details) {
        if (details.isEmpty()) {
            throw new IllegalArgumentException("Dự trù mua hàng phải có ít nhất một sản phẩm");
        }

        for (ProcurementPlanDetailCreateRequest detail : details) {
            if (detail.getRequestedQuantity() == null || detail.getRequestedQuantity() <= 0) {
                throw new IllegalArgumentException("Số lượng dự trù phải lớn hơn 0");
            }

            if (detail.getEstimatedPrice() != null && detail.getEstimatedPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Giá dự kiến không được âm");
            }

            productRepository.findDetailById(detail.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm"));
        }
    }

    /** Bắt buộc trước khi chuyển sang {@link #COMPLETED_STATUS}. */
    private void validateReadyForCompletion(List<ProcurementPlanDetailCreateRequest> details) {
        if (details.isEmpty()) {
            throw new IllegalArgumentException("Dự trù mua hàng phải có ít nhất một sản phẩm trước khi hoàn thành");
        }

        for (int i = 0; i < details.size(); i++) {
            ProcurementPlanDetailCreateRequest detail = details.get(i);
            int lineNo = i + 1;

            if (detail.getSupplierId() == null) {
                throw new IllegalArgumentException("Dòng " + lineNo + ": vui lòng chọn nhà cung cấp trước khi hoàn thành");
            }

            BigDecimal estimatedPrice = detail.getEstimatedPrice();
            if (estimatedPrice == null || estimatedPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Dòng " + lineNo + ": vui lòng nhập giá ước tính trước khi hoàn thành");
            }
        }
    }

    /** Sinh mã phiếu dự trù {@code DT-000001} theo id kế tiếp. */
    private String generateProcurementCode() {
        int nextId = procurementplanRepository.findAll().stream()
                .map(Procurementplan::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        return "DT-" + String.format("%06d", nextId);
    }

    /** Trim chuỗi; trả {@code null} nếu rỗng. */
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** Chuẩn hóa trạng thái phiếu — mặc định "Đang thực hiện" nếu không nhập. */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return DEFAULT_STATUS;
        }

        String normalized = status.trim();
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Trạng thái dự trù không hợp lệ");
        }
        return normalized;
    }

    /** Chặn sửa/xóa phiếu đã hoàn thành. */
    private void ensureNotCompleted(Procurementplan plan) {
        if (COMPLETED_STATUS.equals(plan.getStatus())) {
            throw new IllegalArgumentException("Dự trù mua hàng đã hoàn thành, không thể chỉnh sửa hoặc xóa");
        }
    }

    /** Lọc phiếu dự trù theo khoảng ngày người dùng chọn. */
    private boolean matchesDate(Procurementplan plan, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        if (plan.getDate() == null) {
            return false;
        }
        LocalDate date = plan.getDate().toLocalDate();
        if (from != null && date.isBefore(from)) {
            return false;
        }
        return to == null || !date.isAfter(to);
    }

    /** Parse chuỗi ngày ({@code yyyy-MM-dd}) từ filter form. */
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value);
    }

    /** Định dạng ngày giờ hiển thị trên trang in. */
    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(dateTime);
    }
}
