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

@Service
public class ProcurementplanService {
    private static final String DEFAULT_STATUS = "Đang thực hiện";
    private static final String COMPLETED_STATUS = "Đã hoàn thành";
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<String> ALLOWED_STATUSES = Set.of(DEFAULT_STATUS, COMPLETED_STATUS);
    /** {@link com.example.project.entity.Type#getSortType()} value for bundle products excluded from procurement. */
    private static final String SORT_COMBO = "combo";

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


    // lấy danh sách dự trù
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

        // phân trang
        int start = (int) pageable.getOffset(); //số lượng phần tử bỏ qua
        int end = Math.min(start + pageable.getPageSize(), filtered.size()); // lấy các phần tử tiếp theo

        List<ProcurementplanResponse> content = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    // lấy các trạng thái của phiếu dự trù
    @Transactional(readOnly = true)
    public List<String> listStatuses() {
        return List.copyOf(ALLOWED_STATUSES);
    }

    // kiểm tra xem phiếu dự trù đã hoàn thành chưa
    @Transactional(readOnly = true)
    public boolean isCompleted(Integer id) {
        return procurementplanRepository.findById(id)
                .map(plan -> COMPLETED_STATUS.equals(plan.getStatus()))
                .orElse(false);
    }

    // đếm tất cả số phiếu dự trù
    @Transactional(readOnly = true)
    public long countAll() {
        return procurementplanRepository.count();
    }

    // đếm tất cả số phiếu dự trù đã hoàn thành
    @Transactional(readOnly = true)
    public long countCompleted() {
        return procurementplanRepository.countByStatus(COMPLETED_STATUS);
    }

    // đếm tất cả số phiếu dự trù chưa hoàn thành
    @Transactional(readOnly = true)
    public long countInProgress() {
        return procurementplanRepository.countByStatus(DEFAULT_STATUS);
    }

    // lấy phiếu dự trù theo id
    @Transactional(readOnly = true)
    public ProcurementplanResponse getById(Integer id) {
        return procurementplanRepository.findById(id)
                .map(ProcurementplanResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dự trù mua hàng"));
    }

    /* Đọc dữ liệu của một Procurement Plan từ database và chuyển thành
     ProcurementPlanCreateRequest để hiển thị lên form Update */
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

    // Lấy toàn bộ thông tin của một Procurement Plan để in
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

    /* Chuyển một Procurementplandetail (Entity) thành ProcurementPlanPrintLineResponse (DTO)
       để hiển thị trên trang in. */
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

    private String formatUnitRatio(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal normalized = value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return normalized.toPlainString();
    }

    @Transactional(readOnly = true)
    public List<ProcurementProductSearchResponse> searchProducts(String keyword, int limit) {
        String normalizedKeyword = normalize(keyword);
        // nếu tìm kiếm rỗng ko tìm nữa (tức là ko hiển thị j cả)
        if (normalizedKeyword.isBlank()) {
            return List.of();
        }

        int maxResults = limit <= 0 ? 12 : Math.min(limit, 30);
        Map<Integer, Long> stockByProduct = buildStockByProduct();
        Map<Integer, Productunit> mainUnitByProduct = loadMainUnitByProduct();
        Map<Integer, Productunit> baseUnitByProduct = loadBaseUnitByProduct();
        Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct = loadUnitsByProduct();

        return productRepository.findAllWithRelations()
                .stream()
                .filter(product -> Boolean.TRUE.equals(product.getStatus()))
                .filter(product -> !isComboProduct(product))
                .filter(product -> matchesKeyword(product, normalizedKeyword))
                .sorted(Comparator.comparing(product -> product.getName() == null ? "" : product.getName()))
                .limit(maxResults)
                .map(product -> toSearchResponse(product, stockByProduct, mainUnitByProduct, baseUnitByProduct, unitsByProduct))
                .toList();
    }

    /** Danh sách tồn kho tất cả sản phẩm (dùng modal "Xem tồn sản phẩm" trên form tạo dự trù). */
    @Transactional(readOnly = true)
    public List<ProcurementProductStockResponse> listAllProductStocks(String stockSort) {
        Map<Integer, Long> stockByProduct = buildStockByProduct();
        Map<Integer, Productunit> mainUnitByProduct = loadMainUnitByProduct();
        Map<Integer, Productunit> baseUnitByProduct = loadBaseUnitByProduct();
        Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct = loadUnitsByProduct();
        boolean ascending = "asc".equalsIgnoreCase(normalize(stockSort));

        Comparator<Product> byStock = ascending
                ? Comparator.comparing(product -> stockByProduct.getOrDefault(product.getProductID(), 0L))
                : Comparator.<Product, Long>comparing(product -> stockByProduct.getOrDefault(product.getProductID(), 0L)).reversed();

        return productRepository.findAllWithRelations().stream()
                .filter(product -> Boolean.TRUE.equals(product.getStatus()))
                .filter(product -> !isComboProduct(product))
                .sorted(byStock.thenComparing(product -> product.getName() == null ? "" : product.getName()))
                .map(product -> toStockResponse(
                        product, stockByProduct, mainUnitByProduct, baseUnitByProduct, unitsByProduct))
                .toList();
    }

    private ProcurementProductStockResponse toStockResponse(Product product,
                                                            Map<Integer, Long> stockByProduct,
                                                            Map<Integer, Productunit> mainUnitByProduct,
                                                            Map<Integer, Productunit> baseUnitByProduct,
                                                            Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct) {
        ProcurementProductSearchResponse search = toSearchResponse(
                product, stockByProduct, mainUnitByProduct, baseUnitByProduct, unitsByProduct);

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
                search.getCurrentSellPrice()
        );
    }

    /**
     * ID các sản phẩm đang cần nhập thêm (tồn <= minStock), dùng cho nút "Tạo dự trù hàng cần nhập"
     * ở Danh sách hàng hóa — mở form tạo dự trù với sẵn TẤT CẢ sản phẩm này thay vì phải bấm từng
     * sản phẩm một. Loại trừ combo (không thể mua trực tiếp) và sản phẩm đã ngừng kinh doanh, cùng
     * tiêu chí "còn hàng hay không" {@link com.example.project.service.ProductService} đang dùng
     * cho các thẻ thống kê trên màn Danh sách hàng hóa.
     */
    @Transactional(readOnly = true)
    public List<Integer> findRestockNeededProductIds() {
        Map<Integer, Long> stockByProduct = buildStockByProduct();

        return productRepository.findAllWithRelations().stream()
                .filter(product -> Boolean.TRUE.equals(product.getStatus()))
                .filter(product -> !isComboProduct(product))
                .filter(product -> {
                    long stock = stockByProduct.getOrDefault(product.getProductID(), 0L);
                    int minStock = product.getMinStock() == null ? 0 : product.getMinStock();
                    return stock <= minStock;
                })
                .sorted(Comparator.comparing(product -> product.getName() == null ? "" : product.getName()))
                .map(Product::getProductID)
                .toList();
    }

    /* Lấy thông tin đầy đủ của các sản phẩm đã có trong form
       và chuyển chúng thành ProcurementProductSearchResponse để hiển thị trên giao diện. */
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

        return productRepository.findAllById(productIds)
                .stream()
                .map(product -> toSearchResponse(product, stockByProduct, mainUnitByProduct, baseUnitByProduct, unitsByProduct))
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

    // lấy đơn vị (nhập từ nhà cung cấp) của sản phẩm
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

    // lấy đơn vị nhỏ nhất của sản phẩm
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

    // kiểm tra xem nhà cung cấp có ưu tiên cho sản phẩm này ko
    private boolean isPreferredUnit(Productunit candidate, Productunit current) {
        if (Boolean.TRUE.equals(candidate.getIsDefault()) && !Boolean.TRUE.equals(current.getIsDefault())) {
            return true;
        }
        return Boolean.TRUE.equals(candidate.getIsBaseUnit()) && !Boolean.TRUE.equals(current.getIsDefault());
    }

    // tìm kiếm số lượng và đơn vị tồn, mặc định, nhỏ nhất của sản phẩm
    private ProcurementProductSearchResponse toSearchResponse(Product product,
                                                              Map<Integer, Long> stockByProduct,
                                                              Map<Integer, Productunit> mainUnitByProduct,
                                                              Map<Integer, Productunit> baseUnitByProduct,
                                                              Map<Integer, List<ProcurementProductUnitResponse>> unitsByProduct) {
        Productunit mainUnit = mainUnitByProduct.get(product.getProductID());
        Productunit baseUnit = baseUnitByProduct.get(product.getProductID());
        int stock = stockByProduct.getOrDefault(product.getProductID(), 0L).intValue();
        List<ProcurementProductUnitResponse> units = unitsByProduct.getOrDefault(product.getProductID(), List.of());

        return new ProcurementProductSearchResponse(
                product.getProductID(),
                product.getName(),
                product.getCode(),
                product.getBarcode(),
                stock,
                baseUnit != null ? baseUnit.getUnitName() : null,
                mainUnit != null ? mainUnit.getUnitName() : null,
                mainUnit != null ? mainUnit.getRatio() : null,
                mainUnit != null ? mainUnit.getSellPrice() : null,
                units,
                mainUnit != null ? mainUnit.getSellPrice() : null
        );
    }

    /* Kiểm tra sản phẩm khớp từ khóa prefix (mã, tên hoặc barcode bắt đầu bằng keyword). */
    private boolean matchesKeyword(Product product, String normalizedKeyword) {
        return startsWithNormalized(product.getCode(), normalizedKeyword)
                || startsWithNormalized(product.getName(), normalizedKeyword)
                || startsWithNormalized(product.getBarcode(), normalizedKeyword);
    }

    private boolean startsWithNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).startsWith(normalizedKeyword);
    }

    private boolean isComboProduct(Product product) {
        if (product == null || product.getTypeID() == null || product.getTypeID().getSortType() == null) {
            return false;
        }
        return SORT_COMBO.equals(normalize(product.getTypeID().getSortType()));
    }

    // kiểm tra xem một chuỗi có chứa từ khóa sau khi đã được chuẩn hóa (normalize) hay không.
    private boolean containsNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).contains(normalizedKeyword);
    }

    // tìm kiếm không phân biệt dấu, chữ hoa/thường và khoảng trắng.
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replace("Đ", "D").replace("đ", "d");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    // lấy giá nhập cảu 1 nhà cung cấp
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

    // kiểm tra đơn vị sản phẩm còn active ko
    private boolean isActiveSupplierProduct(Supplierproduct supplierProduct) {
        return supplierProduct.getIsActive() == null || Boolean.TRUE.equals(supplierProduct.getIsActive());
    }

    // danh sách nhà cung cấp
    @Transactional(readOnly = true)
    public List<Supplier> listSuppliers() {
        return supplierRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(supplier -> supplier.getName() == null ? "" : supplier.getName()))
                .toList();
    }

    /* tìm kiếm danh sách nhà cung cấp (Supplier)
    và nếu đã biết sản phẩm (productId) thì trả kèm giá nhập của sản phẩm đó từ từng nhà cung cấp. */
    @Transactional(readOnly = true)
    public List<ProcurementSupplierSearchResponse> searchSuppliersForProduct(Integer productId, String keyword) {
        String normalizedKeyword = normalize(keyword);

        return supplierRepository.findAll()
                .stream()
                .filter(supplier -> normalizedKeyword.isEmpty()
                        || containsNormalized(supplier.getName(), normalizedKeyword))
                .sorted(Comparator.comparing(supplier -> supplier.getName() == null ? "" : supplier.getName()))
                .map(supplier -> new ProcurementSupplierSearchResponse(
                        supplier.getId(),
                        supplier.getName(),
                        productId != null ? getSupplierCostPrice(supplier.getId(), productId) : null
                ))
                .toList();
    }

    // số lượng tồn của sản phẩm
    @Transactional(readOnly = true)
    public Map<Integer, Long> buildStockByProduct() {
        Map<Integer, Long> stockByProduct = new HashMap<>();
        for (Object[] row : batchRepository.sumStorageGroupedByProduct()) {
            stockByProduct.put((Integer) row[0], (Long) row[1]);
        }
        return stockByProduct;
    }

    // tạo phếu dự trù
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

    // sửa phiếu dự trù
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

    // xóa phiếu dự trù
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

    // lưu chi tiết dự trù
    private void saveDetails(Procurementplan plan, List<ProcurementPlanDetailCreateRequest> details) {
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
            detail.setUnit(trimToNull(item.getUnit()));
            detail.setEstimatedPrice(resolveEstimatedPrice(item));
            detail.setSupplierID(supplier);
            detail.setCurrentStock((int) batchRepository.sumStorageByProduct(product.getProductID()));

            procurementplandetailRepository.save(detail);
        }
    }

    //Xác định giá dự kiến (estimatedPrice) cho một dòng chi tiết của Procurement Plan.
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

    // Chuẩn hóa danh sách chi tiết (details) của Procurement Plan bằng cách loại bỏ những dòng chưa chọn sản phẩm.
    private List<ProcurementPlanDetailCreateRequest> normalizeDetails(ProcurementPlanCreateRequest request) {
        if (request.getDetails() == null) {
            return List.of();
        }

        return request.getDetails().stream()
                .filter(item -> item.getProductId() != null)
                .toList();
    }

    // kiểm tra dữ liệu (validation) trước khi lưu Procurement Plan vào database
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

            Product product = productRepository.findDetailById(detail.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm"));
            if (isComboProduct(product)) {
                throw new IllegalArgumentException("Không thể dự trù sản phẩm loại combo");
            }
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

    // tạo mã dự trù
    private String generateProcurementCode() {
        int nextId = procurementplanRepository.findAll().stream()
                .map(Procurementplan::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        return "DT-" + String.format("%06d", nextId);
    }

    //Nếu chuỗi rỗng hoặc chỉ chứa khoảng trắng thì chuyển thành null;
    // nếu có nội dung thì xóa khoảng trắng ở đầu và cuối.
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    //Nếu người dùng không nhập trạng thái → dùng trạng thái mặc định
    //Nếu có nhập → kiểm tra xem trạng thái đó có nằm trong danh sách cho phép (ALLOWED_STATUSES) hay không
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

    // kiểm tra xem một Procurement Plan đã hoàn thành hay chưa.
    //Nếu đã ở trạng thái Completed thì không cho phép chỉnh sửa hoặc xóa.
    private void ensureNotCompleted(Procurementplan plan) {
        if (COMPLETED_STATUS.equals(plan.getStatus())) {
            throw new IllegalArgumentException("Dự trù mua hàng đã hoàn thành, không thể chỉnh sửa hoặc xóa");
        }
    }

    // kiểm tra xem ngày của một Procurementplan có nằm trong khoảng thời gian người dùng chọn hay không. (filter theo ngay)
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

    // chuyển chuỗi ngày tháng (String) thành đối tượng LocalDate
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value);
    }

    // chuyển một LocalDateTime thành chuỗi (String)
    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(dateTime);
    }
}
