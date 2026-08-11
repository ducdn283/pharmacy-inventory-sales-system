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
import com.example.project.dto.response.ProductResponse;
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

    // Stock-status filter values exchanged with the screen.
    public static final String STOCK_STATUS_IN = "IN_STOCK";
    public static final String STOCK_STATUS_LOW = "LOW";
    public static final String STOCK_STATUS_OUT = "OUT";

    private static final String LABEL_IN = "Còn hàng";
    private static final String LABEL_LOW = "Sắp hết";
    private static final String LABEL_OUT = "Hết hàng";

    private static final String CSS_IN = "status-instock";
    private static final String CSS_LOW = "status-low";
    private static final String CSS_OUT = "status-out";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int RECENT_HISTORY_LIMIT = 10;
    // Stockadjustmentdetail.direction values (see StockadjustmentService) — a COUNT slip carries
    // both, so "Lịch sử tồn kho" can no longer assume every adjustment line is a stock-out.
    private static final String DIRECTION_IN = "IN";
    private static final String DIRECTION_OUT = "OUT";
    // Return.returnType (see ReturnService/ReturnPurchaseService) — a SUPPLIER return deducts stock,
    // not adds it; the `return`/`returndetail` tables carry both kinds, undistinguished otherwise.
    private static final String TYPE_SUPPLIER_RETURN = "SUPPLIER";
    // Used by toInstantForSort() below.
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    // normalize("Thuốc kê đơn") — there is no boolean column for this, only the Type's name.
    private static final String PRESCRIPTION_TYPE_NAME = "thuoc ke don";
    // Same threshold StockadjustmentService uses for its own "sắp hết hạn" checks.
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
     * Setter injection (not constructor) so the existing git-ignored ProductServiceTest, which
     * constructs ProductService directly, doesn't need a matching new argument — same pattern
     * already used for InventoryAlertEventService/NotificationRealtimeService elsewhere in this
     * codebase. Only used to look up the canonical Vietnamese label for a Stockadjustment's
     * adjustmentType (adjustmentTypeLabels()) so "Lịch sử tồn kho gần đây" never drifts from the
     * labels Stock Adjustment itself shows — falls back to the raw type code if unset (unit tests).
     */
    private StockadjustmentService stockadjustmentService;

    /** Country names for the "Xuất xứ" autocomplete — sourced from the JDK's ISO-3166 locale data instead of a hardcoded DB table. */
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

    @org.springframework.beans.factory.annotation.Autowired
    public void setStockadjustmentService(StockadjustmentService stockadjustmentService) {
        this.stockadjustmentService = stockadjustmentService;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getAll() {
        return productRepository.findAll()
                .stream()
                .map(ProductResponse::from)
                .toList();
    }

    /**
     * Product List search: keyword over code/name/barcode plus type / producer / stock-status
     * filters, then in-memory pagination. Mirrors the load-all + filter approach already used by
     * {@code StockoutService} for consistency across listing screens.
     */
    @Transactional(readOnly = true)
    public Page<ProductRowResponse> searchProducts(String keyword,
                                                   Integer typeId,
                                                   String producerQuery,
                                                   String stockStatus,
                                                   Pageable pageable) {
        return searchProducts(keyword, null, null, null, producerQuery, typeId, stockStatus, false, pageable);
    }

    /**
     * Product List search: either the single combined {@code keyword} (legacy) or the four
     * expandable-search fields (mã sản phẩm / tên / mã vạch / nhà sản xuất — ANDed when more than
     * one is filled), plus type / stock-status / near-expiry filters, then in-memory pagination.
     * Mirrors the load-all + filter approach already used by {@code StockoutService} for
     * consistency across listing screens.
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
        final String normalizedKeyword = normalize(keyword);
        final String normalizedCode = normalize(codeQuery);
        final String normalizedName = normalize(nameQuery);
        final String normalizedBarcode = normalize(barcodeQuery);
        final String normalizedProducer = normalize(producerQuery);

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
                .filter(product -> nearExpiryProductIds == null || nearExpiryProductIds.contains(product.getProductID()))
                .map(product -> toRow(product, stockByProduct, mainUnitByProduct, ingredientByProduct))
                .filter(row -> stockStatusMatches(row, stockStatus))
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());

        List<ProductRowResponse> content = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public ProductListStatsResponse getStats() {
        Map<Integer, Long> stockByProduct = loadStockByProduct();

        long total = 0;
        long inStock = 0;
        long low = 0;
        long out = 0;

        for (Product product : productRepository.findAll()) {
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

    /**
     * Full Product Detail payload. The store is single, so stock is one system-wide total (no
     * per-branch breakdown). Returns {@link Optional#empty()} when the product does not exist, so
     * the controller can 404 / redirect.
     */
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

    /**
     * Whether the current user may see the "recent inventory history" block. Only Owner may;
     * Pharmacist may not.
     */
    public boolean canViewRecentHistory() {
        String role = currentUserContext.getCurrentRole();
        return RoleConstants.OWNER.equals(role);
    }

    @Transactional(readOnly = true)
    public List<Type> listTypes() {
        return typeRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(type -> type.getName() == null ? "" : type.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Producer> listProducers() {
        return producerRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(producer -> producer.getName() == null ? "" : producer.getName()))
                .toList();
    }

    public List<String> listOrigins() {
        return COUNTRY_NAMES;
    }

    /** Existing ingredient names, for the Create Product autocomplete (reuse instead of retyping). */
    @Transactional(readOnly = true)
    public List<String> listIngredientNames() {
        return medicineapiRepository.findDistinctApiNames();
    }

    /** Existing ingredient strengths, for the Create Product autocomplete. */
    @Transactional(readOnly = true)
    public List<String> listIngredientStrengths() {
        return medicineapiRepository.findDistinctStrengths();
    }

    /** Preview of the next auto-generated internal product code, shown read-only on the create form. */
    @Transactional(readOnly = true)
    public String previewNextProductCode() {
        return formatProductCode(productRepository.findMaxProductCodeSequence() + 1);
    }

    /**
     * Next free internal product code: "SP" + the highest existing sequence + 1, skipping any code
     * already taken (defensive against gaps/races; the unique constraint is the final backstop).
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

    // --- Create Product ------------------------------------------------------

    /**
     * Creates a product master in one transaction: {@code Product} + its {@code ProductUnit}s,
     * plus optional {@code MedicineAPI} ingredients and {@code Position}s. It does NOT create any
     * stock ({@code Batch}) or purchase/invoice data — stock arises only from goods-receipt later.
     *
     * @return the new product id
     * @throws ProductValidationException when validation fails; the transaction rolls back so
     *                                    nothing is persisted.
     */
    @Transactional
    public Integer createProduct(ProductCreateRequest request) {
        List<String> errors = new ArrayList<>();

        String name = trimToNull(request.getName());
        String barcode = trimToNull(request.getBarcode());

        if (name == null) {
            errors.add("Tên hàng hóa không được để trống");
        }
        // Mã hàng is an internal code — auto-generated ("SP" + sequence), not entered by the user.
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

        if (request.getIngredients() != null) {
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

    // --- Edit Product ----------------------------------------------------------

    /**
     * Builds the Edit Product form, prefilled from the existing {@code Product} + its units,
     * ingredients and storage positions. Units are listed smallest-to-largest (matching the Create
     * form's convention) so {@code quantityRelativeToPrevious} can be reconstructed from the stored
     * cumulative ratios. Returns {@link Optional#empty()} when the product does not exist.
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

        for (Medicineapi api : medicineapiRepository.findByProductId(productId)) {
            ProductIngredientCreateRequest row = new ProductIngredientCreateRequest();
            row.setApiName(api.getApiName());
            row.setStrength(api.getStrength());
            form.getIngredients().add(row);
        }

        for (Position position : positionRepository.findByProductId(productId)) {
            ProductPositionCreateRequest row = new ProductPositionCreateRequest();
            row.setName(position.getName());
            form.getPositions().add(row);
        }

        return Optional.of(form);
    }

    /**
     * Saves edits to an existing product. Scope is deliberately narrower than
     * {@link #createProduct}: existing {@code ProductUnit} rows can only be edited in place (name /
     * ratio / sell price / flags) and new rows may be appended, but an existing row can never be
     * removed here, because {@code Batch}, {@code InvoiceDetail}, {@code StockOutDetail} and
     * {@code ReturnDetail} all hold FK references to a specific {@code ProductUnit} row, so shrinking
     * the row count would risk orphaning that history. Adding a row carries no such risk — it's a
     * brand new row with no existing FK pointing at it. Ingredients and storage positions carry no
     * such references, so both lists are simply replaced wholesale (delete-all-then-recreate), same
     * as on create.
     *
     * @throws ProductValidationException when validation fails (including removing an existing unit
     *                                    row); the transaction rolls back so nothing is persisted.
     * @throws IllegalArgumentException   when {@code productId} does not refer to an existing product
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
        // Any rows beyond the existing count are brand new (appended via "+ Thêm đơn vị" on the
        // Edit screen) — no existing FK can point at them yet, so they're safe to insert outright.
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
        if (request.getIngredients() != null) {
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
     * Validates the unit rows and resolves each to a cumulative ratio (relative to the smallest
     * unit) and a final sell price (actual if given, else base price × ratio). Any problem is added
     * to {@code errors}; the returned list is only meaningful when {@code errors} stays empty.
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

        // The base unit is the smallest unit → must be the first row (ratio = 1).
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
                finalPrice = basePrice.multiply(BigDecimal.valueOf(ratios[i]));   // suggested price
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

    /** Create checks every row; edit checks every row but the one being edited. */
    private boolean isTaken(Integer productId, BooleanSupplier onCreate, BooleanSupplier onUpdate) {
        return productId == null ? onCreate.getAsBoolean() : onUpdate.getAsBoolean();
    }

    /**
     * Tồn tối thiểu / tối đa: cả hai đều không được âm và min không được lớn hơn max. Ô nhập trên
     * form là {@code type=number min=0}, nhưng ràng buộc đó chỉ là của trình duyệt — một POST thẳng
     * vẫn gửi được số âm, nên phải kiểm lại ở đây. Dùng chung cho cả tạo mới lẫn sửa.
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

    /** Nhà sản xuất, xuất xứ và loại hàng đều bắt buộc phải chọn/điền khi tạo hoặc sửa hàng hóa. */
    private void validateRequiredFields(ProductCreateRequest request, List<String> errors) {
        if (request.getProducerId() == null) {
            errors.add("Nhà sản xuất không được để trống");
        }
        if (trimToNull(request.getOrigin()) == null) {
            errors.add("Xuất xứ không được để trống");
        }
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

    /** A unit row resolved to its cumulative ratio and final price, ready to persist. */
    private record ResolvedUnit(String name, long ratio, BigDecimal sellPrice,
                                boolean baseUnit, boolean defaultUnit, boolean active) {
    }

    // --- helpers -------------------------------------------------------------

    /** On-hand stock per product = SUM(Batch.storageQuantity) (single store — no branch scoping). */
    private Map<Integer, Long> loadStockByProduct() {
        List<Object[]> rows = batchRepository.sumStorageGroupedByProduct();

        return rows.stream()
                .filter(row -> row[0] != null)
                .collect(Collectors.toMap(
                        row -> ((Number) row[0]).intValue(),
                        row -> ((Number) row[1]).longValue()
                ));
    }

    private Map<Integer, Productunit> loadMainUnitByProduct() {
        return productunitRepository.findAllWithProduct().stream()
                .filter(unit -> unit.getProductID() != null)
                .collect(Collectors.groupingBy(
                        unit -> unit.getProductID().getProductID(),
                        Collectors.collectingAndThen(Collectors.toList(), this::pickMainUnit)
                ));
    }

    /** Default unit first, then base unit, then the lowest-id unit. */
    private Productunit pickMainUnit(List<Productunit> units) {
        return units.stream()
                .filter(unit -> Boolean.TRUE.equals(unit.getIsDefault()) && !Boolean.FALSE.equals(unit.getIsActive()))
                .findFirst()
                .or(() -> units.stream().filter(unit -> Boolean.TRUE.equals(unit.getIsBaseUnit())).findFirst())
                .orElseGet(() -> units.stream()
                        .min(Comparator.comparing(Productunit::getId))
                        .orElse(null));
    }

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

    // --- Product Detail mappers ---------------------------------------------

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
                formatInstant(batch.getImportDate()),
                formatDate(batch.getProductionDate()),
                formatDate(batch.getExpirationDate()),
                batch.getStorageQuantity(),
                batch.getImportUnitID() != null ? batch.getImportUnitID().getUnitName() : "—",
                batch.getImportPrice(),
                Boolean.TRUE.equals(batch.getStatus()) ? "Còn hiệu lực" : "Ngừng",
                batch.getNote()
        );
    }

    // --- recent stock-movement preview (union of 4 sources) ------------------

    /**
     * One candidate row before it's cut down to {@link #RECENT_HISTORY_LIMIT} and before the
     * running product-wide balance is computed — carries {@code baseUnitDelta} alongside the
     * display fields so the two stay in lockstep through the sort/limit step (a plain
     * {@code List<ProductRecentHistoryResponse>} would lose that correlation).
     */
    private record HistoryRow(Instant occurredAt, String timeDisplay, String changeType, String reference,
                              String lotNumber, int displayQuantity, String unitName, String note,
                              int baseUnitDelta) {
    }

    private List<ProductRecentHistoryResponse> loadRecentHistory(Integer productId, long currentTotalStock) {
        Pageable top = PageRequest.of(0, RECENT_HISTORY_LIMIT);
        List<HistoryRow> rows = new ArrayList<>();
        // Sales/stock-outs/returns all report base-unit counts (baseQtyDeducted/baseQtyRestored) —
        // resolve once per call and reuse, since loadRecentHistory is scoped to a single product.
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
                    formatCode("SO", adjustment.getId()),
                    lotNumber(detail.getBatchID()), delta, baseUnit, detail.getNote(), delta));
        }

        for (Returndetail detail : returndetailRepository.findRecentReturnsByProduct(productId, top)) {
            // Return.returnType — CUSTOMER (khách trả hàng về kho, cộng tồn) vs SUPPLIER (mình trả
            // hàng lại NCC, trừ tồn — ReturnPurchaseService.applyReturnEffect() does
            // batch.setStorageQuantity(available - qty)). baseQtyRestored is always a positive
            // magnitude on both, never pre-signed — treating every return as an addition here was
            // the actual bug behind the "Tổng tồn sau thay đổi" numbers not matching reality.
            boolean isSupplierReturn = TYPE_SUPPLIER_RETURN.equals(detail.getReturnID().getReturnType());
            int magnitude = nullSafe(detail.getBaseQtyRestored());
            int delta = isSupplierReturn ? -magnitude : magnitude;
            rows.add(new HistoryRow(
                    detail.getReturnID().getReturnDate(), formatInstant(detail.getReturnID().getReturnDate()),
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

        // Walk newest → oldest: the newest row's "after" is the PRODUCT's real current total stock
        // (all batches combined — the batch table above the history already shows each individual
        // batch's own current total, so this column is deliberately product-wide instead of
        // repeating that). Each older row then derives its own "after" from the next-more-recent
        // row's "after" minus that row's delta. Best-effort by nature — this preview only ever sees
        // the top N rows per source table, so an event outside that window breaks the chain for
        // anything older than it; a reconstructed value going negative is the tell (stock can't
        // really be negative), so that row shows "—" instead of a wrong number.
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

    /** {@code Stockadjustmentdetail.baseQtyDeducted} is always a positive magnitude regardless of
     *  direction (see StockadjustmentService) — sign it here from the line's own direction instead
     *  of assuming every line is a deduction. */
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

    /** Base-unit equivalent of a batch's import quantity (importQtyInUnit × import unit's ratio) —
     *  used only to keep the running-balance math correct; the displayed "SL thay đổi" for a "Nhập
     *  kho" row still shows the import unit as-is (e.g. "+10 Hộp"), unchanged. */
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

    /** The product's single base ProductUnit's name, or "" if the product has none set up yet. */
    private String baseUnitName(Integer productId) {
        return productunitRepository.findByProductId(productId).stream()
                .filter(unit -> Boolean.TRUE.equals(unit.getIsBaseUnit()))
                .findFirst()
                .map(Productunit::getUnitName)
                .orElse("");
    }

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
        return DATE_TIME.withZone(ZoneId.systemDefault()).format(instant);
    }

    /** Invoice.date is stored as a VN wall-clock LocalDateTime — format directly, no zone conversion. */
    private String formatLocalDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return DATE_TIME.format(dateTime);
    }

    /** Converts Invoice.date (VN wall-clock LocalDateTime) to a real Instant, only for cross-source sorting. */
    private Instant toInstantForSort(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(VN_ZONE).toInstant();
    }

    private String formatDate(LocalDate date) {
        return date != null ? date.format(DATE) : "—";
    }

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

    /** "Có" when the product's Type is "Thuốc kê đơn", "Không" for any other declared type, "—" if none. */
    private String prescriptionDisplay(Product product) {
        if (product.getTypeID() == null) {
            return "—";
        }
        return PRESCRIPTION_TYPE_NAME.equals(normalize(product.getTypeID().getName())) ? "Có" : "Không";
    }

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

    private boolean matchesKeyword(Product product, String normalizedKeyword) {
        if (normalizedKeyword.isBlank()) {
            return true;
        }
        return containsNormalized(product.getCode(), normalizedKeyword)
                || containsNormalized(product.getName(), normalizedKeyword)
                || containsNormalized(product.getBarcode(), normalizedKeyword)
                || containsNormalized(String.valueOf(product.getProductID()), normalizedKeyword);
    }

    // --- expandable search fields (Mã sản phẩm / Tên sản phẩm / Mã vạch) — ANDed when combined ---

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

    /** Products carrying at least one in-stock batch expiring within {@link #NEAR_EXPIRY_DAYS} days. */
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
