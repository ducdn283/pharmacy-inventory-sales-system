package com.example.project.service;

import com.example.project.constant.RoleConstants;
import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.ProductCreateRequest;
import com.example.project.dto.request.ProductIngredientCreateRequest;
import com.example.project.dto.request.ProductPositionCreateRequest;
import com.example.project.dto.request.ProductUnitCreateRequest;
import com.example.project.dto.response.ProductBatchDetailResponse;
import com.example.project.dto.response.ProductDetailResponse;
import com.example.project.dto.response.ProductListStatsResponse;
import com.example.project.dto.response.ProductRecentHistoryResponse;
import com.example.project.dto.response.ProductRowResponse;
import com.example.project.dto.response.ProductUnitDetailResponse;
import com.example.project.entity.*;
import com.example.project.repository.BatchRepository;
import com.example.project.repository.InvoicedetailRepository;
import com.example.project.repository.MedicineapiRepository;
import com.example.project.repository.PositionRepository;
import com.example.project.repository.ProducerRepository;
import com.example.project.repository.ProductRepository;
import com.example.project.repository.ProductunitRepository;
import com.example.project.repository.ReturndetailRepository;
import com.example.project.repository.StockadjustmentdetailRepository;
import com.example.project.repository.TypeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.text.Normalizer;
import java.util.Arrays;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

@Service
public class ProductService {

    // Các giá trị filter trạng thái tồn kho trao đổi với màn hình.
    public static final String STOCK_STATUS_IN = "IN_STOCK";
    public static final String STOCK_STATUS_LOW = "LOW";
    public static final String STOCK_STATUS_OUT = "OUT";

    public static final String BUSINESS_STATUS_ACTIVE = "ACTIVE";
    public static final String BUSINESS_STATUS_INACTIVE = "INACTIVE";

    public static final String SORT_NAME_ASC = "NAME_ASC";
    public static final String SORT_NAME_DESC = "NAME_DESC";
    public static final String SORT_STOCK_ASC = "STOCK_ASC";
    public static final String SORT_STOCK_DESC = "STOCK_DESC";

    private static final String LABEL_IN = "Còn hàng";
    private static final String LABEL_LOW = "Sắp hết";
    private static final String LABEL_OUT = "Hết hàng";

    private static final String CSS_IN = "status-instock";
    private static final String CSS_LOW = "status-low";
    private static final String CSS_OUT = "status-out";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int RECENT_HISTORY_LIMIT = 10;
    // Giá trị Stockadjustmentdetail.direction (xem StockadjustmentService) — một phiếu COUNT có cả
    // 2 chiều, nên "Lịch sử tồn kho" không được mặc định mọi dòng điều chỉnh đều là xuất kho.
    private static final String DIRECTION_IN = "IN";
    private static final String DIRECTION_OUT = "OUT";
    // Return.returnType (xem ReturnService/ReturnPurchaseService) — trả hàng cho NCC (SUPPLIER) thì
    // trừ tồn chứ không cộng; bảng return/returndetail chứa cả 2 loại, không phân biệt sẵn.
    private static final String TYPE_SUPPLIER_RETURN = "SUPPLIER";
    private static final String INVOICE_TYPE_REPLACEMENT = "Thay thế";
    // Dùng bởi toInstantForSort() bên dưới.
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    // normalize("Thuốc kê đơn") — không có cột boolean riêng, chỉ dựa vào tên Type.
    private static final String PRESCRIPTION_TYPE_NAME = "thuoc ke don";
    // MedicineAPI/hoạt chất chỉ áp dụng cho sản phẩm có Type.sortType thuộc nhóm thuốc.
    private static final String SORT_MEDICINE = "thuoc";
    // Các loại thiết bị y tế không theo dõi hạn dùng trên lô hàng.
    private static final String SORT_MEDICAL_DEVICE = "thiet bi y te";
    private static final String DEVICE_MACHINE_MARK = "may";
    private static final String DEVICE_NO_EXPIRY_MARK = "khong han";
    // Cùng ngưỡng mà StockadjustmentService dùng để xác định "sắp hết hạn".
    private static final int NEAR_EXPIRY_DAYS = 90;

    private final ProductRepository productRepository;
    private final BatchRepository batchRepository;
    private final ProductunitRepository productunitRepository;
    private final MedicineapiRepository medicineapiRepository;
    private final TypeRepository typeRepository;
    private final ProducerRepository producerRepository;
    private final InvoicedetailRepository invoicedetailRepository;
    private final StockadjustmentdetailRepository stockadjustmentdetailRepository;
    private final ReturndetailRepository returndetailRepository;
    private final PositionRepository positionRepository;
    private final CurrentUserContext currentUserContext;
    private final ProductImageStorageService productImageStorageService;
    /*
     * Inject qua setter (không qua constructor) để test cũ không cần sửa theo. Chỉ dùng để tra
     * nhãn tiếng Việt chuẩn của một adjustmentType (adjustmentTypeLabels()), đảm bảo "Lịch sử tồn
     * kho gần đây" luôn khớp nhãn với màn Stock Adjustment — nếu chưa gán thì dùng luôn mã gốc.
     */
    private StockadjustmentService stockadjustmentService;

    /** Danh sách tên quốc gia cho autocomplete "Xuất xứ" — lấy từ dữ liệu ISO-3166 của JDK, không cần bảng DB riêng. */
    private static final List<String> COUNTRY_NAMES = Arrays.stream(Locale.getISOCountries())
            .map(code -> new Locale("", code).getDisplayCountry(new Locale("vi")))
            .distinct()
            .sorted(Collator.getInstance(new Locale("vi")))
            .toList();

    public ProductService(ProductRepository productRepository,
                          BatchRepository batchRepository,
                          ProductunitRepository productunitRepository,
                          MedicineapiRepository medicineapiRepository,
                          TypeRepository typeRepository,
                          ProducerRepository producerRepository,
                          InvoicedetailRepository invoicedetailRepository,
                          StockadjustmentdetailRepository stockadjustmentdetailRepository,
                          ReturndetailRepository returndetailRepository,
                          PositionRepository positionRepository,
                          CurrentUserContext currentUserContext,
                          ProductImageStorageService productImageStorageService) {
        this.productRepository = productRepository;
        this.batchRepository = batchRepository;
        this.productunitRepository = productunitRepository;
        this.medicineapiRepository = medicineapiRepository;
        this.typeRepository = typeRepository;
        this.producerRepository = producerRepository;
        this.invoicedetailRepository = invoicedetailRepository;
        this.stockadjustmentdetailRepository = stockadjustmentdetailRepository;
        this.returndetailRepository = returndetailRepository;
        this.positionRepository = positionRepository;
        this.currentUserContext = currentUserContext;
        this.productImageStorageService = productImageStorageService;
    }

    // Inject qua setter để phá vòng phụ thuộc vòng tròn với StockadjustmentService (xem javadoc field ở trên).
    @org.springframework.beans.factory.annotation.Autowired
    public void setStockadjustmentService(StockadjustmentService stockadjustmentService) {
        this.stockadjustmentService = stockadjustmentService;
    }

    /** Tìm kiếm Danh sách hàng hóa: keyword trên mã/tên/barcode kèm filter loại/nhà sản xuất/trạng thái tồn, phân trang in-memory. */
    @Transactional(readOnly = true)
    public Page<ProductRowResponse> searchProducts(String keyword,
                                                   Integer typeId,
                                                   String producerQuery,
                                                   String stockStatus,
                                                   Pageable pageable) {
        return searchProducts(keyword, null, null, null, producerQuery, typeId, stockStatus, false, pageable);
    }

    /**
     * Tìm kiếm Danh sách hàng hóa: dùng {@code keyword} gộp (kiểu cũ) hoặc 4 ô tìm kiếm mở rộng
     * (mã sản phẩm / tên / mã vạch / nhà sản xuất — AND với nhau nếu điền nhiều ô), kèm filter
     * loại hàng / trạng thái tồn / sắp hết hạn, rồi phân trang in-memory.
     */
    @Transactional(readOnly = true)
    public Page<ProductRowResponse> searchProducts(String keyword,
                                                   String codeQuery,
                                                   String nameQuery,
                                                   String barcodeQuery,
                                                   String producerQuery,
                                                   Integer typeId,
                                                   String stockStatus,
                                                   boolean nearExpiryOnly,
                                                   Pageable pageable) {
        return searchProducts(keyword, codeQuery, nameQuery, barcodeQuery, producerQuery, typeId,
                stockStatus, nearExpiryOnly, null, null, pageable);
    }

    /**
     * Bản đầy đủ của tìm kiếm Danh sách hàng hóa, có thêm filter trạng thái kinh doanh và sắp xếp.
     * Trạng thái kinh doanh chỉ Owner được lọc — role khác có tự chế {@code ?businessStatus=...}
     * cũng bị bỏ qua ở đây. Sắp xếp áp dụng sau khi lọc và trước khi phân trang để ổn định trên
     * toàn bộ tập kết quả.
     */
    @Transactional(readOnly = true)
    public Page<ProductRowResponse> searchProducts(String keyword,
                                                   String codeQuery,
                                                   String nameQuery,
                                                   String barcodeQuery,
                                                   String producerQuery,
                                                   Integer typeId,
                                                   String stockStatus,
                                                   boolean nearExpiryOnly,
                                                   String businessStatus,
                                                   String sortOrder,
                                                   Pageable pageable) {
        final String normalizedKeyword = normalize(keyword);
        final String normalizedCode = normalize(codeQuery);
        final String normalizedName = normalize(nameQuery);
        final String normalizedBarcode = normalize(barcodeQuery);
        final String normalizedProducer = normalize(producerQuery);
        // Mặc định ẩn sản phẩm ngừng kinh doanh. Chỉ Owner chủ động chọn filter INACTIVE mới thấy;
        // tham số tự chế từ role khác vẫn bị ép về ACTIVE ở đây.
        final String requestedBusinessStatus = validBusinessStatus(businessStatus);
        final String effectiveBusinessStatus = RoleConstants.OWNER.equals(currentUserContext.getCurrentRole())
                && BUSINESS_STATUS_INACTIVE.equals(requestedBusinessStatus)
                ? BUSINESS_STATUS_INACTIVE
                : BUSINESS_STATUS_ACTIVE;

        Map<Integer, Long> stockByProduct = loadStockByProduct();
        Map<Integer, Productunit> mainUnitByProduct = loadMainUnitByProduct();
        Map<Integer, String> ingredientByProduct = loadIngredientByProduct();
        Set<Integer> nearExpiryProductIds = nearExpiryOnly ? loadNearExpiryProductIds() : null;

        List<ProductRowResponse> filtered = productRepository.findAllWithRelations().stream()
                .filter(product -> matchesKeyword(product, normalizedKeyword))
                .filter(product -> matchesCode(product, normalizedCode))
                .filter(product -> matchesName(product, normalizedName))
                .filter(product -> matchesBarcode(product, normalizedBarcode))
                .filter(product -> matchesProducer(product, normalizedProducer))
                .filter(product -> typeMatches(product, typeId))
                .filter(product -> businessStatusMatches(product, effectiveBusinessStatus))
                .filter(product -> nearExpiryProductIds == null || nearExpiryProductIds.contains(product.getProductID()))
                .map(product -> toRow(product, stockByProduct, mainUnitByProduct, ingredientByProduct))
                .filter(row -> stockStatusMatches(row, stockStatus))
                .collect(Collectors.toCollection(ArrayList::new));

        Comparator<ProductRowResponse> comparator = productSortComparator(sortOrder);
        if (comparator != null) {
            filtered.sort(comparator);
        }

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());

        List<ProductRowResponse> content = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    /** Thống kê tổng quan cho các thẻ trên đầu Danh sách hàng hóa: tổng số SP còn kinh doanh, số còn hàng/sắp hết/hết hàng. */
    @Transactional(readOnly = true)
    public ProductListStatsResponse getStats() {
        Map<Integer, Long> stockByProduct = loadStockByProduct();

        long total = 0;
        long inStock = 0;
        long low = 0;
        long out = 0;

        for (Product product : productRepository.findAll()) {
            if (!Boolean.TRUE.equals(product.getStatus())) {
                continue;
            }
            total++;
            long stock = stockByProduct.getOrDefault(product.getProductID(), 0L);
            switch (stockStatusCode(product, stock)) {
                case STOCK_STATUS_IN -> inStock++;
                case STOCK_STATUS_LOW -> low++;
                default -> out++;
            }
        }

        return new ProductListStatsResponse(total, inStock, low, out);
    }

    /** Toàn bộ payload cho màn Chi tiết hàng hóa. Trả về rỗng nếu sản phẩm không tồn tại (để controller redirect). */
    @Transactional(readOnly = true)
    public Optional<ProductDetailResponse> getProductDetail(Integer productId) {
        Optional<Product> productOpt = productRepository.findDetailById(productId);
        if (productOpt.isEmpty()) {
            return Optional.empty();
        }
        Product product = productOpt.get();

        List<ProductUnitDetailResponse> units = productunitRepository.findByProductId(productId).stream()
                .sorted(Comparator.comparing(Productunit::getRatio,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toUnitDetail)
                .toList();
        String baseUnitName = units.stream()
                .filter(ProductUnitDetailResponse::isBaseUnit)
                .findFirst()
                .map(ProductUnitDetailResponse::getUnitName)
                .orElse("");

        List<String> ingredients = medicineapiRepository.findByProductId(productId).stream()
                .map(this::formatIngredient)
                .toList();

        long totalStock = batchRepository.sumStorageByProduct(productId);
        String totalStatus = stockStatusCode(product, totalStock);

        List<ProductBatchDetailResponse> batches =
                batchRepository.findInStockBatchesByProduct(productId).stream()
                        .map(this::toBatchDetail)
                        .toList();

        boolean canViewHistory = canViewRecentHistory();
        List<ProductRecentHistoryResponse> recentHistory =
                canViewHistory ? loadRecentHistory(productId, totalStock) : List.of();

        ProductDetailResponse response = new ProductDetailResponse();
        response.setProductId(product.getProductID());
        response.setCode(product.getCode());
        response.setName(product.getName());
        response.setBarcode(product.getBarcode());
        response.setImageUrl(product.getImage());
        response.setTypeName(product.getTypeID() != null ? product.getTypeID().getName() : "—");
        response.setTracksExpirationDate(tracksExpirationDate(product.getTypeID()));
        response.setProducerName(product.getProducerID() != null ? product.getProducerID().getName() : "—");
        response.setOriginName(product.getOrigin() != null ? product.getOrigin() : "—");
        response.setRegistrationNumber(product.getRegistrationNumber());
        response.setStatusActive(Boolean.TRUE.equals(product.getStatus()));
        response.setStatusLabel(Boolean.TRUE.equals(product.getStatus()) ? "Đang kinh doanh" : "Ngừng kinh doanh");
        response.setMinStock(product.getMinStock());
        response.setMaxStock(product.getMaxStock());
        response.setNote(product.getNote());
        response.setIngredients(ingredients);
        response.setUnits(units);
        response.setBaseUnitName(baseUnitName);
        response.setTotalStock(totalStock);
        response.setStockStatusLabel(stockStatusLabel(totalStatus));
        response.setStockStatusCss(stockStatusCss(totalStatus));
        response.setBatches(batches);
        response.setCanViewRecentHistory(canViewHistory);
        response.setRecentHistory(recentHistory);
        return Optional.of(response);
    }

    /** Người dùng hiện tại có được xem khối "lịch sử tồn kho gần đây" không — chỉ Owner. */
    public boolean canViewRecentHistory() {
        String role = currentUserContext.getCurrentRole();
        return RoleConstants.OWNER.equals(role);
    }

    /** Danh sách Loại hàng, sắp xếp theo tên — dùng cho dropdown lọc/tạo hàng hóa. */
    @Transactional(readOnly = true)
    public List<Type> listTypes() {
        return typeRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(type -> type.getName() == null ? "" : type.getName()))
                .toList();
    }

    /** Danh sách Nhà sản xuất, sắp xếp theo tên — dùng cho dropdown tạo/sửa hàng hóa. */
    @Transactional(readOnly = true)
    public List<Producer> listProducers() {
        return producerRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(producer -> producer.getName() == null ? "" : producer.getName()))
                .toList();
    }

    /** Danh sách tên quốc gia cho autocomplete "Xuất xứ". */
    public List<String> listOrigins() {
        return COUNTRY_NAMES;
    }

    /** Danh sách tên hoạt chất đã có, cho autocomplete ở form tạo hàng hóa. */
    @Transactional(readOnly = true)
    public List<String> listIngredientNames() {
        return medicineapiRepository.findDistinctApiNames();
    }

    /** Danh sách hàm lượng đã có, cho autocomplete ở form tạo hàng hóa. */
    @Transactional(readOnly = true)
    public List<String> listIngredientStrengths() {
        return medicineapiRepository.findDistinctStrengths();
    }

    /** Xem trước mã sản phẩm tiếp theo sẽ tự sinh, hiển thị read-only trên form tạo. */
    @Transactional(readOnly = true)
    public String previewNextProductCode() {
        return formatProductCode(productRepository.findMaxProductCodeSequence() + 1);
    }

    /**
     * Mã sản phẩm tiếp theo còn trống: "SP" + số thứ tự lớn nhất hiện có + 1, bỏ qua mã đã bị
     * chiếm (phòng trường hợp lệch số/đụng độ; ràng buộc UNIQUE là chốt chặn cuối cùng).
     */
    private String generateNextProductCode() {
        long seq = productRepository.findMaxProductCodeSequence();
        String code;
        do {
            seq++;
            code = formatProductCode(seq);
        } while (productRepository.existsByCode(code));
        return code;
    }

    private String formatProductCode(long sequence) {
        return "SP" + String.format("%06d", sequence);
    }

    // --- Tạo hàng hóa ----------------------------------------------------------

    /**
     * Tạo sản phẩm gốc trong 1 transaction: {@code Product} + các {@code ProductUnit}, kèm
     * {@code MedicineAPI} và {@code Position} tuỳ chọn. KHÔNG tạo tồn kho ({@code Batch}) hay
     * chứng từ nhập/bán — tồn kho chỉ phát sinh khi nhập hàng sau này.
     *
     * @return id sản phẩm vừa tạo
     * @throws ProductValidationException khi validate thất bại; transaction rollback, không lưu gì.
     */
    @Transactional
    public Integer createProduct(ProductCreateRequest request) {
        List<String> errors = new ArrayList<>();

        String name = trimToNull(request.getName());
        String barcode = trimToNull(request.getBarcode());

        if (name == null) {
            errors.add("Tên hàng hóa không được để trống");
        }
        // Mã hàng là mã nội bộ tự sinh ("SP" + số thứ tự), người dùng không tự nhập.
        validateUniqueness(name, barcode, trimToNull(request.getRegistrationNumber()), null, errors);
        validateStockBounds(request, errors);
        validateRequiredFields(request, errors);
        if (request.getTypeId() != null && !typeRepository.existsById(request.getTypeId())) {
            errors.add("Loại hàng không hợp lệ");
        }
        if (request.getProducerId() != null && !producerRepository.existsById(request.getProducerId())) {
            errors.add("Nhà sản xuất không hợp lệ");
        }

        List<ResolvedUnit> resolvedUnits = validateAndResolveUnits(request.getUnits(), errors);

        if (!errors.isEmpty()) {
            throw new ProductValidationException(errors);
        }

        Product product = new Product();
        product.setName(name);
        product.setCode(generateNextProductCode());
        product.setBarcode(barcode);
        product.setRegistrationNumber(trimToNull(request.getRegistrationNumber()));
        product.setMinStock(request.getMinStock());
        product.setMaxStock(request.getMaxStock());
        product.setStatus(request.getStatus() == null ? Boolean.TRUE : request.getStatus());
        product.setNote(trimToNull(request.getNote()));
        product.setOrigin(trimToNull(request.getOrigin()));
        if (request.getTypeId() != null) {
            product.setTypeID(typeRepository.getReferenceById(request.getTypeId()));
        }
        if (request.getProducerId() != null) {
            product.setProducerID(producerRepository.getReferenceById(request.getProducerId()));
        }
        if (request.getImageFile() != null && !request.getImageFile().isEmpty()) {
            String typeName = product.getTypeID() != null ? product.getTypeID().getName() : null;
            try {
                product.setImage(productImageStorageService.upload(request.getImageFile(), product.getCode(), typeName));
            } catch (RuntimeException e) {
                throw new ProductValidationException(List.of("Không thể tải ảnh sản phẩm lên. Vui lòng thử lại."));
            }
        }
        Product saved = productRepository.save(product);

        for (ResolvedUnit resolved : resolvedUnits) {
            Productunit unit = new Productunit();
            unit.setProductID(saved);
            unit.setUnitName(resolved.name());
            unit.setRatio(BigDecimal.valueOf(resolved.ratio()));
            unit.setSellPrice(resolved.sellPrice());
            unit.setIsBaseUnit(resolved.baseUnit());
            unit.setIsDefault(resolved.defaultUnit());
            unit.setIsActive(resolved.active());
            productunitRepository.save(unit);
        }

        if (supportsIngredients(saved.getTypeID()) && request.getIngredients() != null) {
            for (ProductIngredientCreateRequest ingredient : request.getIngredients()) {
                String apiName = trimToNull(ingredient.getApiName());
                if (apiName == null) {
                    continue;
                }
                Medicineapi api = new Medicineapi();
                api.setProductID(saved);
                api.setApiName(apiName);
                api.setStrength(trimToNull(ingredient.getStrength()));
                medicineapiRepository.save(api);
            }
        }

        if (request.getPositions() != null) {
            for (ProductPositionCreateRequest position : request.getPositions()) {
                String positionName = trimToNull(position.getName());
                if (positionName == null) {
                    continue;
                }
                Position entity = new Position();
                entity.setProductID(saved);
                entity.setName(positionName);
                positionRepository.save(entity);
            }
        }

        return saved.getProductID();
    }

    // --- Sửa hàng hóa ------------------------------------------------------------

    /**
     * Dựng form Sửa hàng hóa, điền sẵn từ {@code Product} hiện có + đơn vị/hoạt chất/vị trí. Đơn vị
     * liệt kê từ nhỏ đến lớn (giống form Tạo) để dựng lại {@code quantityRelativeToPrevious} từ
     * ratio tích luỹ đã lưu. Trả về rỗng nếu sản phẩm không tồn tại.
     */
    @Transactional(readOnly = true)
    public Optional<ProductCreateRequest> getEditForm(Integer productId) {
        Optional<Product> productOpt = productRepository.findDetailById(productId);
        if (productOpt.isEmpty()) {
            return Optional.empty();
        }
        Product product = productOpt.get();

        ProductCreateRequest form = new ProductCreateRequest();
        form.setName(product.getName());
        form.setCode(product.getCode());
        form.setBarcode(product.getBarcode());
        form.setTypeId(product.getTypeID() != null ? product.getTypeID().getId() : null);
        form.setItemGroup(product.getTypeID() != null ? product.getTypeID().getSortType() : null);
        form.setProducerId(product.getProducerID() != null ? product.getProducerID().getId() : null);
        form.setOrigin(product.getOrigin());
        form.setRegistrationNumber(product.getRegistrationNumber());
        form.setMinStock(product.getMinStock());
        form.setMaxStock(product.getMaxStock());
        form.setStatus(product.getStatus());
        form.setNote(product.getNote());
        form.setExistingImageUrl(product.getImage());

        List<Productunit> units = productunitRepository.findByProductId(productId).stream()
                .sorted(Comparator.comparing(Productunit::getRatio, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        BigDecimal previousRatio = null;
        for (Productunit unit : units) {
            ProductUnitCreateRequest row = new ProductUnitCreateRequest();
            row.setUnitName(unit.getUnitName());
            row.setSellPrice(unit.getSellPrice());
            row.setBaseUnit(Boolean.TRUE.equals(unit.getIsBaseUnit()));
            row.setDefaultUnit(Boolean.TRUE.equals(unit.getIsDefault()));
            row.setActive(!Boolean.FALSE.equals(unit.getIsActive()));
            row.setQuantityRelativeToPrevious(previousRatio == null || previousRatio.signum() == 0
                    ? null
                    : unit.getRatio().divide(previousRatio, 0, RoundingMode.HALF_UP).intValue());
            previousRatio = unit.getRatio();
            form.getUnits().add(row);
        }

        if (supportsIngredients(product.getTypeID())) {
            for (Medicineapi api : medicineapiRepository.findByProductId(productId)) {
                ProductIngredientCreateRequest row = new ProductIngredientCreateRequest();
                row.setApiName(api.getApiName());
                row.setStrength(api.getStrength());
                form.getIngredients().add(row);
            }
        }

        for (Position position : positionRepository.findByProductId(productId)) {
            ProductPositionCreateRequest row = new ProductPositionCreateRequest();
            row.setName(position.getName());
            form.getPositions().add(row);
        }

        return Optional.of(form);
    }

    /**
     * Lưu chỉnh sửa cho sản phẩm đã có. Phạm vi hẹp hơn {@link #createProduct}: các dòng
     * {@code ProductUnit} cũ chỉ được sửa tại chỗ (tên/ratio/giá/cờ), có thể thêm dòng mới nhưng
     * KHÔNG được xoá dòng cũ — vì {@code Batch}, {@code InvoiceDetail}, {@code StockOutDetail},
     * {@code ReturnDetail} đều tham chiếu FK tới từng {@code ProductUnit} cụ thể, xoá đi sẽ làm mồ
     * côi dữ liệu lịch sử. Thêm dòng mới thì an toàn vì chưa có FK nào trỏ tới. Hoạt chất và vị trí
     * lưu kho không bị ràng buộc FK như vậy nên được thay thế toàn bộ (xoá hết rồi tạo lại), giống
     * lúc tạo mới.
     *
     * @throws ProductValidationException khi validate thất bại (kể cả trường hợp xoá dòng đơn vị cũ)
     * @throws IllegalArgumentException   khi {@code productId} không tồn tại
     */
    @Transactional
    public void updateProduct(Integer productId, ProductCreateRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hàng hóa"));

        List<String> errors = new ArrayList<>();

        String name = trimToNull(request.getName());
        String barcode = trimToNull(request.getBarcode());

        if (name == null) {
            errors.add("Tên hàng hóa không được để trống");
        }
        validateUniqueness(name, barcode, trimToNull(request.getRegistrationNumber()), productId, errors);
        validateStockBounds(request, errors);
        validateRequiredFields(request, errors);
        if (request.getTypeId() != null && !typeRepository.existsById(request.getTypeId())) {
            errors.add("Loại hàng không hợp lệ");
        }
        if (request.getProducerId() != null && !producerRepository.existsById(request.getProducerId())) {
            errors.add("Nhà sản xuất không hợp lệ");
        }

        List<Productunit> existingUnits = productunitRepository.findByProductId(productId).stream()
                .sorted(Comparator.comparing(Productunit::getRatio, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<ProductUnitCreateRequest> requestedUnitRows = request.getUnits() == null ? List.of()
                : request.getUnits().stream().filter(u -> trimToNull(u.getUnitName()) != null).toList();
        if (requestedUnitRows.size() < existingUnits.size()) {
            errors.add("Không thể xoá đơn vị tính đã có khi sửa hàng hóa");
        }

        List<ResolvedUnit> resolvedUnits = validateAndResolveUnits(request.getUnits(), errors);

        if (!errors.isEmpty()) {
            throw new ProductValidationException(errors);
        }

        product.setName(name);
        product.setBarcode(barcode);
        product.setRegistrationNumber(trimToNull(request.getRegistrationNumber()));
        product.setMinStock(request.getMinStock());
        product.setMaxStock(request.getMaxStock());
        product.setStatus(request.getStatus() == null ? Boolean.TRUE : request.getStatus());
        product.setNote(trimToNull(request.getNote()));
        product.setOrigin(trimToNull(request.getOrigin()));
        String oldTypeName = product.getTypeID() != null ? product.getTypeID().getName() : null;
        product.setTypeID(request.getTypeId() != null ? typeRepository.getReferenceById(request.getTypeId()) : null);
        String newTypeName = product.getTypeID() != null ? product.getTypeID().getName() : null;
        product.setProducerID(request.getProducerId() != null
                ? producerRepository.getReferenceById(request.getProducerId()) : null);
        if (request.getImageFile() != null && !request.getImageFile().isEmpty()) {
            try {
                product.setImage(productImageStorageService.upload(request.getImageFile(), product.getCode(), newTypeName));
            } catch (RuntimeException e) {
                throw new ProductValidationException(List.of("Không thể tải ảnh sản phẩm lên. Vui lòng thử lại."));
            }
        } else if (Boolean.TRUE.equals(request.getRemoveImage()) && product.getImage() != null) {
            productImageStorageService.delete(product.getCode());
            product.setImage(null);
        } else if (product.getImage() != null) {
            String oldTag = productImageStorageService.typeTag(oldTypeName);
            String newTag = productImageStorageService.typeTag(newTypeName);
            if (!oldTag.equals(newTag)) {
                productImageStorageService.retag(product.getCode(), oldTag, newTag);
            }
        }
        productRepository.save(product);

        for (int i = 0; i < existingUnits.size(); i++) {
            Productunit unit = existingUnits.get(i);
            ResolvedUnit resolved = resolvedUnits.get(i);
            unit.setUnitName(resolved.name());
            unit.setRatio(BigDecimal.valueOf(resolved.ratio()));
            unit.setSellPrice(resolved.sellPrice());
            unit.setIsBaseUnit(resolved.baseUnit());
            unit.setIsDefault(resolved.defaultUnit());
            unit.setIsActive(resolved.active());
            productunitRepository.save(unit);
        }
        // Các dòng vượt quá số dòng cũ là dòng mới thêm (qua "+ Thêm đơn vị") — chưa có FK nào trỏ
        // tới nên chèn thẳng là an toàn.
        for (int i = existingUnits.size(); i < resolvedUnits.size(); i++) {
            ResolvedUnit resolved = resolvedUnits.get(i);
            Productunit unit = new Productunit();
            unit.setProductID(product);
            unit.setUnitName(resolved.name());
            unit.setRatio(BigDecimal.valueOf(resolved.ratio()));
            unit.setSellPrice(resolved.sellPrice());
            unit.setIsBaseUnit(resolved.baseUnit());
            unit.setIsDefault(resolved.defaultUnit());
            unit.setIsActive(resolved.active());
            productunitRepository.save(unit);
        }

        medicineapiRepository.deleteAll(medicineapiRepository.findByProductId(productId));
        if (supportsIngredients(product.getTypeID()) && request.getIngredients() != null) {
            for (ProductIngredientCreateRequest ingredient : request.getIngredients()) {
                String apiName = trimToNull(ingredient.getApiName());
                if (apiName == null) {
                    continue;
                }
                Medicineapi api = new Medicineapi();
                api.setProductID(product);
                api.setApiName(apiName);
                api.setStrength(trimToNull(ingredient.getStrength()));
                medicineapiRepository.save(api);
            }
        }

        positionRepository.deleteAll(positionRepository.findByProductId(productId));
        if (request.getPositions() != null) {
            for (ProductPositionCreateRequest position : request.getPositions()) {
                String positionName = trimToNull(position.getName());
                if (positionName == null) {
                    continue;
                }
                Position entity = new Position();
                entity.setProductID(product);
                entity.setName(positionName);
                positionRepository.save(entity);
            }
        }
    }

    /**
     * Validate các dòng đơn vị và quy đổi mỗi dòng thành ratio tích luỹ (so với đơn vị nhỏ nhất) +
     * giá bán cuối (giá thật nếu có, không thì giá cơ bản × ratio). Lỗi được thêm vào
     * {@code errors}; kết quả trả về chỉ có ý nghĩa khi {@code errors} vẫn rỗng.
     */
    private List<ResolvedUnit> validateAndResolveUnits(List<ProductUnitCreateRequest> units, List<String> errors) {
        List<ResolvedUnit> resolved = new ArrayList<>();

        List<ProductUnitCreateRequest> rows = units == null ? List.of()
                : units.stream().filter(u -> trimToNull(u.getUnitName()) != null).toList();
        if (rows.isEmpty()) {
            errors.add("Phải có ít nhất 1 đơn vị");
            return resolved;
        }

        long baseCount = rows.stream().filter(ProductUnitCreateRequest::isBaseUnit).count();
        if (baseCount == 0) {
            errors.add("Phải có đúng 1 đơn vị cơ bản (isBaseUnit)");
        } else if (baseCount > 1) {
            errors.add("Chỉ được có đúng 1 đơn vị cơ bản");
        }

        long defaultCount = rows.stream().filter(ProductUnitCreateRequest::isDefaultUnit).count();
        if (defaultCount == 0) {
            errors.add("Phải có đúng 1 đơn vị mặc định (isDefault)");
        } else if (defaultCount > 1) {
            errors.add("Chỉ được có đúng 1 đơn vị mặc định");
        }

        Set<String> seenNames = new HashSet<>();
        for (ProductUnitCreateRequest row : rows) {
            String key = normalize(row.getUnitName());
            if (!seenNames.add(key)) {
                errors.add("Tên đơn vị bị trùng: " + trimToNull(row.getUnitName()));
            }
        }

        // Đơn vị cơ bản là đơn vị nhỏ nhất → phải là dòng đầu tiên (ratio = 1).
        if (baseCount == 1 && !rows.get(0).isBaseUnit()) {
            errors.add("Đơn vị cơ bản phải là đơn vị nhỏ nhất (dòng đầu tiên)");
        }

        long[] ratios = new long[rows.size()];
        ratios[0] = 1L;
        for (int i = 1; i < rows.size(); i++) {
            String unitName = trimToNull(rows.get(i).getUnitName());
            Integer qty = rows.get(i).getQuantityRelativeToPrevious();
            if (qty == null || qty < 1) {
                errors.add("Số lượng quy đổi của '" + unitName + "' phải là số nguyên dương");
                ratios[i] = ratios[i - 1];
            } else {
                ratios[i] = ratios[i - 1] * qty;
                if (ratios[i] <= ratios[i - 1]) {
                    errors.add("Đơn vị '" + unitName + "' phải có tỷ lệ quy đổi lớn hơn đơn vị trước");
                }
            }
        }

        BigDecimal basePrice = rows.get(0).getSellPrice();
        if (basePrice == null || basePrice.signum() <= 0) {
            errors.add("Giá bán đơn vị cơ bản phải lớn hơn 0");
        }

        for (int i = 0; i < rows.size(); i++) {
            ProductUnitCreateRequest row = rows.get(i);
            String unitName = trimToNull(row.getUnitName());
            BigDecimal actual = row.getSellPrice();
            BigDecimal finalPrice;
            if (actual != null && actual.signum() > 0) {
                finalPrice = actual;
            } else if (basePrice != null && basePrice.signum() > 0) {
                finalPrice = basePrice.multiply(BigDecimal.valueOf(ratios[i]));   // giá đề xuất
            } else {
                finalPrice = null;
            }
            if (finalPrice == null || finalPrice.signum() <= 0) {
                errors.add("Giá bán của '" + unitName + "' phải lớn hơn 0");
                finalPrice = BigDecimal.ZERO;
            }
            resolved.add(new ResolvedUnit(unitName, ratios[i], finalPrice,
                    row.isBaseUnit(), row.isDefaultUnit(), row.isActive()));
        }

        return resolved;
    }

    /**
     * Ba trường định danh của hàng hóa, dùng chung cho cả tạo mới lẫn sửa.
     *
     * <p><strong>Tên hàng hóa: bắt buộc và không được trùng.</strong> So sánh do collation của cột
     * quyết định ({@code utf8mb4_0900_ai_ci}) nên "Paracetamol" / "paracetamol" / "Páracetamol"
     * được coi là một — đúng nghĩa "trùng tên" mà người dùng hiểu.</p>
     *
     * <p><strong>Barcode và Số đăng ký: chỉ kiểm khi có nhập.</strong> Bỏ trống nghĩa là "chưa khai
     * báo", không phải một giá trị rỗng dùng chung — nếu kiểm cả khi trống thì sản phẩm thứ hai
     * không có số đăng ký sẽ bị báo trùng với sản phẩm đầu tiên cũng không có, chặn nhầm một trường
     * hợp hoàn toàn hợp lệ. {@code trimToNull} ở nơi gọi đã biến chuỗi trắng thành {@code null},
     * nên ở đây chỉ cần kiểm {@code != null}.</p>
     *
     * <p>Không bảng nào trong số này có ràng buộc UNIQUE ở DB ngoài {@code code}/{@code barcode},
     * nên tầng service là chỗ chặn duy nhất — giống {@code CustomerService}/{@code SupplierService}.</p>
     *
     * @param productId id bản ghi đang sửa; {@code null} khi tạo mới. Bản ghi luôn "trùng với chính
     *                  nó", nên khi sửa phải loại nó ra khỏi phép kiểm.
     */
    private void validateUniqueness(String name, String barcode, String registrationNumber,
                                    Integer productId, List<String> errors) {
        if (name != null && isTaken(productId,
                () -> productRepository.existsByName(name),
                () -> productRepository.existsByNameExcludingProduct(name, productId))) {
            errors.add("Tên hàng hóa '" + name + "' đã tồn tại");
        }
        if (barcode != null && isTaken(productId,
                () -> productRepository.existsByBarcode(barcode),
                () -> productRepository.existsByBarcodeExcludingProduct(barcode, productId))) {
            errors.add("Barcode '" + barcode + "' đã tồn tại");
        }
        if (registrationNumber != null && isTaken(productId,
                () -> productRepository.existsByRegistrationNumber(registrationNumber),
                () -> productRepository.existsByRegistrationNumberExcludingProduct(registrationNumber, productId))) {
            errors.add("Số đăng ký '" + registrationNumber + "' đã tồn tại");
        }
    }

    /** Tạo mới: kiểm tất cả các dòng. Sửa: kiểm mọi dòng trừ chính dòng đang sửa. */
    private boolean isTaken(Integer productId, BooleanSupplier onCreate, BooleanSupplier onUpdate) {
        return productId == null ? onCreate.getAsBoolean() : onUpdate.getAsBoolean();
    }

    /**
     * Tồn tối thiểu / tối đa: cả hai đều bắt buộc, không được âm và min không được lớn hơn max. Ô
     * nhập trên form là {@code type=number min=0}, nhưng ràng buộc đó chỉ là của trình duyệt — một
     * POST thẳng vẫn gửi được số âm hoặc bỏ trống, nên phải kiểm lại ở đây. Dùng chung cho cả tạo
     * mới lẫn sửa.
     */
    private void validateStockBounds(ProductCreateRequest request, List<String> errors) {
        Integer minStock = request.getMinStock();
        Integer maxStock = request.getMaxStock();

        if (minStock == null) {
            errors.add("Tồn tối thiểu không được để trống");
        } else if (minStock < 0) {
            errors.add("Tồn tối thiểu không được âm");
        }
        if (maxStock == null) {
            errors.add("Tồn tối đa không được để trống");
        } else if (maxStock < 0) {
            errors.add("Tồn tối đa không được âm");
        }
        if (minStock != null && maxStock != null && minStock > maxStock) {
            errors.add("Tồn tối thiểu không được lớn hơn tồn tối đa");
        }
    }

    /** Loại hàng vẫn bắt buộc; nhà sản xuất/xuất xứ đã nới lỏng thành tuỳ chọn. */
    private void validateRequiredFields(ProductCreateRequest request, List<String> errors) {
        if (request.getTypeId() == null) {
            errors.add("Loại hàng không được để trống");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Một dòng đơn vị đã quy đổi ratio tích luỹ và giá cuối, sẵn sàng lưu DB. */
    private record ResolvedUnit(String name, long ratio, BigDecimal sellPrice,
                                boolean baseUnit, boolean defaultUnit, boolean active) {
    }

    // --- helpers -------------------------------------------------------------

    /** Tồn kho theo từng sản phẩm = SUM(Batch.storageQuantity) (1 cửa hàng, không chia chi nhánh). */
    private Map<Integer, Long> loadStockByProduct() {
        List<Object[]> rows = batchRepository.sumStorageGroupedByProduct();

        return rows.stream()
                .filter(row -> row[0] != null)
                .collect(Collectors.toMap(
                        row -> ((Number) row[0]).intValue(),
                        row -> ((Number) row[1]).longValue()
                ));
    }

    // Gom đơn vị tính theo sản phẩm rồi chọn ra "đơn vị bán chính" cho mỗi sản phẩm (xem pickMainUnit).
    private Map<Integer, Productunit> loadMainUnitByProduct() {
        return productunitRepository.findAllWithProduct().stream()
                .filter(unit -> unit.getProductID() != null)
                .collect(Collectors.groupingBy(
                        unit -> unit.getProductID().getProductID(),
                        Collectors.collectingAndThen(Collectors.toList(), this::pickMainUnit)
                ));
    }

    /** Ưu tiên đơn vị mặc định, rồi đến đơn vị cơ bản, cuối cùng là đơn vị có id nhỏ nhất. */
    private Productunit pickMainUnit(List<Productunit> units) {
        return units.stream()
                .filter(unit -> Boolean.TRUE.equals(unit.getIsDefault()) && !Boolean.FALSE.equals(unit.getIsActive()))
                .findFirst()
                .or(() -> units.stream().filter(unit -> Boolean.TRUE.equals(unit.getIsBaseUnit())).findFirst())
                .orElseGet(() -> units.stream()
                        .min(Comparator.comparing(Productunit::getId))
                        .orElse(null));
    }

    // Gom hoạt chất theo sản phẩm thành 1 chuỗi hiển thị (nối bằng dấu phẩy) cho mỗi sản phẩm.
    private Map<Integer, String> loadIngredientByProduct() {
        return medicineapiRepository.findAllWithProduct().stream()
                .filter(api -> api.getProductID() != null)
                .collect(Collectors.groupingBy(
                        api -> api.getProductID().getProductID(),
                        Collectors.mapping(this::formatIngredient,
                                Collectors.collectingAndThen(Collectors.toList(),
                                        parts -> String.join(", ", parts)))
                ));
    }

    private String formatIngredient(Medicineapi api) {
        if (api.getStrength() == null || api.getStrength().isBlank()) {
            return api.getApiName();
        }
        return api.getApiName() + " " + api.getStrength();
    }

    // --- Mapper cho màn Chi tiết hàng hóa -------------------------------------

    private ProductUnitDetailResponse toUnitDetail(Productunit unit) {
        return new ProductUnitDetailResponse(
                unit.getUnitName(),
                unit.getRatio(),
                unit.getSellPrice(),
                Boolean.TRUE.equals(unit.getIsDefault()),
                Boolean.TRUE.equals(unit.getIsBaseUnit()),
                Boolean.TRUE.equals(unit.getIsActive())
        );
    }

    private ProductBatchDetailResponse toBatchDetail(Batch batch) {
        return new ProductBatchDetailResponse(
                batch.getId(),
                batch.getBatchName(),
                batch.getLotNumber(),
                formatBatchImportDate(batch),
                formatDate(batch.getProductionDate()),
                formatDate(batch.getExpirationDate()),
                batch.getStorageQuantity(),
                batch.getImportUnitID() != null ? batch.getImportUnitID().getUnitName() : "—",
                batch.getImportPrice(),
                Boolean.TRUE.equals(batch.getStatus()) ? "Còn hiệu lực" : "Ngừng",
                batch.getNote()
        );
    }

    // --- Xem trước biến động tồn kho gần đây (gộp từ 4 nguồn) -----------------

    /**
     * Một dòng ứng viên, trước khi cắt còn {@link #RECENT_HISTORY_LIMIT} dòng và trước khi tính số
     * dư tồn kho tích luỹ — mang thêm {@code baseUnitDelta} đi kèm field hiển thị để 2 giá trị này
     * luôn khớp nhau qua bước sort/limit.
     */
    private record HistoryRow(Instant occurredAt, String timeDisplay, String changeType, String reference,
                              String lotNumber, int displayQuantity, String unitName, String note,
                              int baseUnitDelta) {
    }

    // Gộp top-N sự kiện gần đây từ 4 nguồn (nhập/bán/điều chỉnh kho/trả hàng), sắp xếp mới→cũ và
    // dựng lại số dư tồn kho tích luỹ sau mỗi dòng (xem ghi chú thuật toán ở dưới).
    private List<ProductRecentHistoryResponse> loadRecentHistory(Integer productId, long currentTotalStock) {
        Pageable top = PageRequest.of(0, RECENT_HISTORY_LIMIT);
        List<HistoryRow> rows = new ArrayList<>();
        // Bán hàng/xuất kho/trả hàng đều tính theo đơn vị cơ bản (baseQtyDeducted/baseQtyRestored)
        // — tra 1 lần và tái sử dụng, vì loadRecentHistory chỉ chạy cho 1 sản phẩm.
        String baseUnit = baseUnitName(productId);

        for (Batch batch : batchRepository.findRecentImportsByProduct(productId, top)) {
            String importUnit = batch.getImportUnitID() != null ? batch.getImportUnitID().getUnitName() : baseUnit;
            rows.add(new HistoryRow(
                    batch.getImportDate(), formatInstant(batch.getImportDate()), "Nhập kho",
                    batch.getBatchName(), batch.getLotNumber(),
                    importQuantity(batch), importUnit, null,
                    importBaseQuantity(batch)));
        }

        for (Invoicedetail detail : invoicedetailRepository.findRecentSalesByProduct(productId, top)) {
            Invoice invoice = detail.getInvoiceID();
            // Hóa đơn thay thế chỉ mô tả lại hàng khách còn giữ, không trừ tồn thêm lần nữa — hóa
            // đơn gốc và phiếu trả hàng mới là 2 biến động tồn kho thật.
            if (invoice != null && INVOICE_TYPE_REPLACEMENT.equalsIgnoreCase(invoice.getInvoiceType())) {
                continue;
            }
            int delta = -nullSafe(detail.getBaseQtyDeducted());
            rows.add(new HistoryRow(
                    toInstantForSort(invoice.getDate()), formatLocalDateTime(invoice.getDate()), "Bán hàng",
                    invoice.getInvoiceNumber(),
                    lotNumber(detail.getBatchID()), delta, baseUnit, null, delta));
        }

        for (Stockadjustmentdetail detail : stockadjustmentdetailRepository.findRecentStockOutsByProduct(productId, top)) {
            Stockadjustment adjustment = detail.getStockAdjustmentID();
            int delta = signedAdjustmentQuantity(detail);
            rows.add(new HistoryRow(
                    adjustment.getDate(), formatInstant(adjustment.getDate()),
                    stockAdjustmentMovementLabel(detail),
                    adjustment.getStockAdjustmentCode(),
                    lotNumber(detail.getBatchID()), delta, baseUnit, detail.getNote(), delta));
        }

        for (Returndetail detail : returndetailRepository.findRecentReturnsByProduct(productId, top)) {
            // Return.returnType — CUSTOMER (khách trả hàng về kho, cộng tồn) vs SUPPLIER (mình trả
            // lại NCC, trừ tồn). baseQtyRestored luôn là số dương, không tự mang dấu, nên phải xác
            // định dấu dựa vào returnType ở đây.
            boolean isSupplierReturn = TYPE_SUPPLIER_RETURN.equals(detail.getReturnID().getReturnType());
            int magnitude = nullSafe(detail.getBaseQtyRestored());
            int delta = isSupplierReturn ? -magnitude : magnitude;
            Instant occurredAt = normalizeVnEncoded(detail.getReturnID().getReturnDate());
            rows.add(new HistoryRow(
                    occurredAt, formatInstant(occurredAt),
                    isSupplierReturn ? "Trả hàng NCC" : "Khách trả hàng",
                    formatCode("RT", detail.getReturnID().getId()),
                    lotNumber(detail.getBatchID()), delta, baseUnit, null, delta));
        }

        // "Lịch sử tồn kho" chỉ nói về SỐ LƯỢNG tồn — loại bỏ những dòng không đổi số lượng (vd.
        // Stock Adjustment loại DATE_ADJUSTMENT/chỉnh hạn dùng, direction=NONE, baseQtyDeducted=0).
        // Việc chỉnh hạn dùng, đổi trạng thái kinh doanh... thuộc lịch sử khác của sản phẩm, không
        // phải biến động tồn kho.
        List<HistoryRow> limited = rows.stream()
                .filter(row -> row.baseUnitDelta() != 0)
                .sorted(Comparator.comparing(HistoryRow::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RECENT_HISTORY_LIMIT)
                .toList();

        // Duyệt từ mới → cũ: "tồn sau" của dòng mới nhất chính là tổng tồn thật hiện tại của sản
        // phẩm (gộp mọi lô). Mỗi dòng cũ hơn suy ra "tồn sau" của mình từ "tồn sau" của dòng gần
        // hơn trừ đi delta của dòng đó. Đây là ước lượng tốt nhất có thể — preview chỉ lấy top N
        // dòng mỗi nguồn, nên một sự kiện nằm ngoài phạm vi đó sẽ làm đứt chuỗi cho các dòng cũ
        // hơn nó; khi kết quả dựng lại bị âm (không hợp lý) thì hiển thị "—" thay vì số sai.
        long running = currentTotalStock;
        List<ProductRecentHistoryResponse> result = new ArrayList<>(limited.size());
        for (HistoryRow row : limited) {
            Integer after = running >= 0 ? (int) running : null;
            result.add(new ProductRecentHistoryResponse(
                    row.occurredAt(), row.timeDisplay(), row.changeType(), row.reference(), row.lotNumber(),
                    row.displayQuantity(), row.unitName(), row.note(), after));
            running -= row.baseUnitDelta();
        }
        return result;
    }

    /** "Nhập kho - <loại>" / "Xuất kho - <loại>" theo đúng chiều dòng thật của Stock Adjustment — một
     *  phiếu COUNT chứa cả dòng thừa (IN) lẫn dòng thiếu (OUT) trong cùng một phiếu, nên không thể
     *  gán cứng "Xuất kho" cho mọi dòng như trước nữa. Dòng direction=NONE (vd. chỉnh hạn dùng, không
     *  đổi số lượng) chỉ hiện tên loại, không có tiền tố nhập/xuất. */
    private String stockAdjustmentMovementLabel(Stockadjustmentdetail detail) {
        String rawType = detail.getStockAdjustmentID().getAdjustmentType();
        String typeLabel = stockadjustmentService != null
                ? stockadjustmentService.adjustmentTypeLabels().getOrDefault(rawType, rawType)
                : rawType;
        String direction = detail.getDirection();
        if (DIRECTION_IN.equals(direction)) {
            return "Nhập kho - " + typeLabel;
        }
        if (DIRECTION_OUT.equals(direction)) {
            return "Xuất kho - " + typeLabel;
        }
        return typeLabel;
    }

    /** {@code baseQtyDeducted} luôn là số dương bất kể chiều — gán dấu ở đây dựa vào direction của dòng. */
    private int signedAdjustmentQuantity(Stockadjustmentdetail detail) {
        int qty = nullSafe(detail.getBaseQtyDeducted());
        if (DIRECTION_IN.equals(detail.getDirection())) {
            return qty;
        }
        if (DIRECTION_OUT.equals(detail.getDirection())) {
            return -qty;
        }
        return 0;
    }

    /** Quy đổi số lượng nhập của lô hàng về đơn vị cơ bản (importQtyInUnit × ratio) — chỉ dùng để
     *  tính đúng số dư tích luỹ; cột "SL thay đổi" hiển thị vẫn giữ nguyên đơn vị nhập (vd. "+10 Hộp"). */
    private int importBaseQuantity(Batch batch) {
        if (batch.getImportUnitID() != null && batch.getImportQtyInUnit() != null
                && batch.getImportUnitID().getRatio() != null) {
            return BigDecimal.valueOf(batch.getImportQtyInUnit())
                    .multiply(batch.getImportUnitID().getRatio())
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValue();
        }
        return batch.getStorageQuantity() != null ? batch.getStorageQuantity() : 0;
    }

    /** Tên đơn vị cơ bản của sản phẩm, hoặc "" nếu chưa cấu hình. */
    private String baseUnitName(Integer productId) {
        return productunitRepository.findByProductId(productId).stream()
                .filter(unit -> Boolean.TRUE.equals(unit.getIsBaseUnit()))
                .findFirst()
                .map(Productunit::getUnitName)
                .orElse("");
    }

    // Số lượng nhập hiển thị theo đúng đơn vị nhập gốc (không quy đổi ra đơn vị cơ bản).
    private int importQuantity(Batch batch) {
        if (batch.getImportQtyInUnit() != null) {
            return batch.getImportQtyInUnit();
        }
        return batch.getStorageQuantity() != null ? batch.getStorageQuantity() : 0;
    }

    private int nullSafe(Integer value) {
        return value != null ? value : 0;
    }

    private String lotNumber(Batch batch) {
        return batch != null ? batch.getLotNumber() : null;
    }

    private String formatCode(String prefix, Integer id) {
        return id != null ? prefix + "-" + String.format("%06d", id) : prefix;
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        return DATE_TIME.withZone(VN_ZONE).format(instant);
    }

    /**
     * Lô hàng nhập mua có mốc thời gian UTC thật, còn lô do ReturnService tạo (từ trả hàng) lưu
     * theo kiểu cũ "giờ VN nhưng ghi như UTC". Lô trả hàng có mã cố định RT-{returnId}-L{batchId},
     * nên chỉ cần chuẩn hoá đúng nhóm này.
     */
    private String formatBatchImportDate(Batch batch) {
        if (batch == null) {
            return "";
        }
        Instant importDate = batch.getImportDate();
        String batchCode = batch.getBatchCode();
        if (batchCode != null && batchCode.startsWith("RT-")) {
            importDate = normalizeVnEncoded(importDate);
        }
        return formatInstant(importDate);
    }

    /** Đổi mốc giờ kiểu cũ của ReturnService (giờ VN lưu nhầm thành UTC) về Instant thật để sort chung với dữ liệu khác. */
    private Instant normalizeVnEncoded(Instant vnEncoded) {
        return vnEncoded == null ? null : vnEncoded.minus(Duration.ofHours(7));
    }

    /** Invoice.date lưu sẵn theo giờ VN (LocalDateTime) — format thẳng, không cần đổi múi giờ. */
    private String formatLocalDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return DATE_TIME.format(dateTime);
    }

    /** Đổi Invoice.date (giờ VN) sang Instant thật, chỉ để sort chung với các nguồn dữ liệu khác. */
    private Instant toInstantForSort(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(VN_ZONE).toInstant();
    }

    private String formatDate(LocalDate date) {
        return date != null ? date.format(DATE) : "—";
    }

    // Dựng 1 dòng của Danh sách hàng hóa từ Product + các Map tra cứu tồn/đơn vị chính/hoạt chất đã nạp sẵn.
    private ProductRowResponse toRow(Product product,
                                     Map<Integer, Long> stockByProduct,
                                     Map<Integer, Productunit> mainUnitByProduct,
                                     Map<Integer, String> ingredientByProduct) {
        Integer productId = product.getProductID();
        long stock = stockByProduct.getOrDefault(productId, 0L);
        Productunit mainUnit = mainUnitByProduct.get(productId);

        String statusCode = stockStatusCode(product, stock);

        return new ProductRowResponse(
                productId,
                product.getCode(),
                product.getName(),
                product.getTypeID() != null ? product.getTypeID().getName() : "—",
                ingredientByProduct.getOrDefault(productId, ""),
                mainUnit != null ? mainUnit.getUnitName() : "—",
                mainUnit != null ? mainUnit.getSellPrice() : null,
                stock,
                stockStatusLabel(statusCode),
                stockStatusCss(statusCode),
                prescriptionDisplay(product)
        );
    }

    /** "Có" nếu Loại hàng là "Thuốc kê đơn", "Không" nếu là loại khác, "—" nếu chưa gán loại. */
    private String prescriptionDisplay(Product product) {
        if (product.getTypeID() == null) {
            return "—";
        }
        return PRESCRIPTION_TYPE_NAME.equals(normalize(product.getTypeID().getName())) ? "Có" : "Không";
    }

    // Xác định trạng thái tồn kho: hết hàng (<=0) / sắp hết (<= minStock) / còn hàng.
    private String stockStatusCode(Product product, long stock) {
        if (stock <= 0) {
            return STOCK_STATUS_OUT;
        }
        int min = product.getMinStock() == null ? 0 : product.getMinStock();
        if (stock <= min) {
            return STOCK_STATUS_LOW;
        }
        return STOCK_STATUS_IN;
    }

    private String stockStatusLabel(String code) {
        return switch (code) {
            case STOCK_STATUS_IN -> LABEL_IN;
            case STOCK_STATUS_LOW -> LABEL_LOW;
            default -> LABEL_OUT;
        };
    }

    private String stockStatusCss(String code) {
        return switch (code) {
            case STOCK_STATUS_IN -> CSS_IN;
            case STOCK_STATUS_LOW -> CSS_LOW;
            default -> CSS_OUT;
        };
    }

    // Ô tìm kiếm gộp (kiểu cũ): khớp nếu keyword xuất hiện ở mã/tên/barcode/nhà sản xuất/id.
    private boolean matchesKeyword(Product product, String normalizedKeyword) {
        if (normalizedKeyword.isBlank()) {
            return true;
        }
        return containsNormalized(product.getCode(), normalizedKeyword)
                || containsNormalized(product.getName(), normalizedKeyword)
                || containsNormalized(product.getBarcode(), normalizedKeyword)
                || containsNormalized(product.getProducerID() != null ? product.getProducerID().getName() : null,
                        normalizedKeyword)
                || containsNormalized(String.valueOf(product.getProductID()), normalizedKeyword);
    }

    // --- các ô tìm kiếm mở rộng (Mã sản phẩm / Tên sản phẩm / Mã vạch) — AND với nhau khi kết hợp ---

    private boolean matchesCode(Product product, String normalizedCode) {
        return normalizedCode.isBlank() || containsNormalized(product.getCode(), normalizedCode);
    }

    private boolean matchesName(Product product, String normalizedName) {
        return normalizedName.isBlank() || containsNormalized(product.getName(), normalizedName);
    }

    private boolean matchesBarcode(Product product, String normalizedBarcode) {
        return normalizedBarcode.isBlank() || containsNormalized(product.getBarcode(), normalizedBarcode);
    }

    private boolean matchesProducer(Product product, String normalizedProducer) {
        return normalizedProducer.isBlank()
                || (product.getProducerID() != null && containsNormalized(product.getProducerID().getName(), normalizedProducer));
    }

    /** Các sản phẩm có ít nhất 1 lô còn tồn sắp hết hạn trong {@link #NEAR_EXPIRY_DAYS} ngày. */
    private Set<Integer> loadNearExpiryProductIds() {
        LocalDate today = LocalDate.now();
        return new HashSet<>(batchRepository.findProductIdsNearExpiry(today, today.plusDays(NEAR_EXPIRY_DAYS)));
    }

    private boolean typeMatches(Product product, Integer typeId) {
        if (typeId == null) {
            return true;
        }
        return product.getTypeID() != null && typeId.equals(product.getTypeID().getId());
    }

    // Lọc theo trạng thái tồn kho đã chọn trên bộ lọc; không lọc gì nếu không truyền tham số.
    private boolean stockStatusMatches(ProductRowResponse row, String stockStatus) {
        if (stockStatus == null || stockStatus.isBlank()) {
            return true;
        }
        String label = switch (stockStatus) {
            case STOCK_STATUS_IN -> LABEL_IN;
            case STOCK_STATUS_LOW -> LABEL_LOW;
            case STOCK_STATUS_OUT -> LABEL_OUT;
            default -> null;
        };
        return label != null && label.equals(row.getStockStatusLabel());
    }

    private String validBusinessStatus(String businessStatus) {
        return BUSINESS_STATUS_ACTIVE.equals(businessStatus) || BUSINESS_STATUS_INACTIVE.equals(businessStatus)
                ? businessStatus
                : null;
    }

    private boolean businessStatusMatches(Product product, String businessStatus) {
        if (businessStatus == null) {
            return true;
        }
        boolean active = Boolean.TRUE.equals(product.getStatus());
        return BUSINESS_STATUS_ACTIVE.equals(businessStatus) ? active : !active;
    }

    // Dựng Comparator theo lựa chọn sắp xếp (tên/tồn kho, tăng/giảm); tên luôn là tiêu chí phụ để ổn định thứ tự.
    private Comparator<ProductRowResponse> productSortComparator(String sortOrder) {
        if (sortOrder == null || sortOrder.isBlank()) {
            return null;
        }

        Collator vietnamese = Collator.getInstance(Locale.forLanguageTag("vi-VN"));
        vietnamese.setStrength(Collator.PRIMARY);
        Comparator<ProductRowResponse> byName = (left, right) -> {
            String leftName = left.getName() == null ? "" : left.getName();
            String rightName = right.getName() == null ? "" : right.getName();
            int compared = vietnamese.compare(leftName, rightName);
            if (compared != 0) {
                return compared;
            }
            return Comparator.nullsLast(Integer::compareTo)
                    .compare(left.getProductId(), right.getProductId());
        };

        return switch (sortOrder) {
            case SORT_NAME_ASC -> byName;
            case SORT_NAME_DESC -> byName.reversed();
            case SORT_STOCK_ASC -> Comparator.comparingLong(ProductRowResponse::getStock)
                    .thenComparing(byName);
            case SORT_STOCK_DESC -> Comparator.comparingLong(ProductRowResponse::getStock)
                    .reversed()
                    .thenComparing(byName);
            default -> null;
        };
    }

    private boolean containsNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).contains(normalizedKeyword);
    }

    // Chuẩn hoá chuỗi để so sánh/tìm kiếm không phân biệt dấu, hoa/thường: bỏ dấu, đổi đ/Đ, viết thường.
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replace("Đ", "D").replace("đ", "d");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    /** Chỉ nhóm Loại hàng "thuốc" mới có hoạt chất (MedicineAPI). */
    private boolean supportsIngredients(Type type) {
        return type != null && SORT_MEDICINE.equals(normalize(type.getSortType()));
    }

    /** Cùng quy tắc theo dõi hạn dùng mà màn tạo Phiếu nhập đang dùng. */
    private boolean tracksExpirationDate(Type type) {
        if (type == null || !SORT_MEDICAL_DEVICE.equals(normalize(type.getSortType()))) {
            return true;
        }
        String typeName = normalize(type.getName());
        return !(typeName.contains(DEVICE_MACHINE_MARK) || typeName.contains(DEVICE_NO_EXPIRY_MARK));
    }
}
