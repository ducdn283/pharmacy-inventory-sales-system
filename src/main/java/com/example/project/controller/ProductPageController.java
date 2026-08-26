package com.example.project.controller;

import com.example.project.dto.request.ProductCreateRequest;
import com.example.project.dto.request.ProductUnitCreateRequest;
import com.example.project.dto.response.ProductDetailResponse;
import com.example.project.dto.response.ProductBarcodeOptionResponse;
import com.example.project.dto.response.ProductBarcodePrintResponse;
import com.example.project.dto.response.ProductRowResponse;
import com.example.project.dto.response.PurchaseInvoiceBarcodeOptionResponse;
import com.example.project.entity.Type;
import com.example.project.service.ProductService;
import com.example.project.service.ProductBarcodeService;
import com.example.project.service.ProductBarcodePdfService;
import com.example.project.service.ProductValidationException;
import com.example.project.service.ProcurementplanService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;

/**
 * Controller cho các màn Danh sách / Chi tiết / Tạo / Sửa Hàng hóa. Dùng chung 1 view cho cả 3
 * role (Owner / Pharmacist / Accountant) — riêng Tạo và Sửa chỉ Owner mới được phép.
 */
@Controller
public class ProductPageController {

    private final ProductService productService;
    private final ProcurementplanService procurementplanService;
    private final ProductBarcodeService productBarcodeService;
    private final ProductBarcodePdfService productBarcodePdfService;

    public ProductPageController(ProductService productService,
                                 ProcurementplanService procurementplanService,
                                 ProductBarcodeService productBarcodeService,
                                 ProductBarcodePdfService productBarcodePdfService) {
        this.productService = productService;
        this.procurementplanService = procurementplanService;
        this.productBarcodeService = productBarcodeService;
        this.productBarcodePdfService = productBarcodePdfService;
    }

    // Hiển thị danh sách hàng hóa có tìm kiếm/lọc/phân trang, dùng chung cho cả 3 role.
    @GetMapping({
            "/owner/products",
            "/pharmacist/products",
            "/accountant/products"
    })
    public String listProducts(@RequestParam(name = "keyword", required = false) String keyword,
                               @RequestParam(name = "typeId", required = false) Integer typeId,
                               @RequestParam(name = "status", required = false) String status,
                               @RequestParam(name = "sortOrder", required = false) String sortOrder,
                               @RequestParam(name = "nearExpiryOnly", defaultValue = "false") boolean nearExpiryOnly,
                               @RequestParam(name = "page", defaultValue = "0") int page,
                               @RequestParam(name = "size", defaultValue = "10") int size,
                               HttpServletRequest request,
                               Model model) {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = 10;
        }

        String basePath = resolveBasePath(request);
        boolean canFilterBusinessStatus = "/owner/products".equals(basePath);
        String stockStatus = ProductService.STOCK_STATUS_IN.equals(status)
                || ProductService.STOCK_STATUS_OUT.equals(status) ? status : null;
        String businessStatus = ProductService.BUSINESS_STATUS_ACTIVE.equals(status)
                || ProductService.BUSINESS_STATUS_INACTIVE.equals(status) ? status : null;

        Page<ProductRowResponse> productPage = productService.searchProducts(keyword, null, null,
                null, null, typeId, stockStatus, nearExpiryOnly, businessStatus, sortOrder,
                PageRequest.of(page, size));

        model.addAttribute("productPage", productPage);
        model.addAttribute("products", productPage.getContent());
        model.addAttribute("stats", productService.getStats());

        model.addAttribute("types", productService.listTypes());

        model.addAttribute("keyword", keyword);
        model.addAttribute("filterTypeId", typeId);
        // Một dropdown trạng thái dùng chung. ProductService mới là nơi chặn thật sự (luôn ẩn hàng
        // ngừng kinh doanh với role không phải Owner), ở đây chỉ là hiển thị.
        model.addAttribute("filterStatus", canFilterBusinessStatus
                || businessStatus == null ? status : null);
        model.addAttribute("canFilterBusinessStatus", canFilterBusinessStatus);
        model.addAttribute("sortOrder", sortOrder);
        model.addAttribute("nearExpiryOnly", nearExpiryOnly);

        model.addAttribute("currentPage", productPage.getNumber());
        model.addAttribute("totalPages", productPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", productPage.getTotalElements());

        model.addAttribute("basePath", basePath);
        model.addAttribute("restockProducts", "/owner/products".equals(basePath)
                ? procurementplanService.listRestockNeededProducts()
                : List.of());

        return "product/list";
    }

    /** Form tạo hàng hóa — chỉ Owner. Mapping literal {@code /create} được Spring ưu tiên hơn {@code /{productId}}. */
    @GetMapping("/owner/products/create")
    public String createProductForm(Model model) {
        model.addAttribute("form", newFormWithBaseUnit());
        addCreateFormReferenceData(model, false, null);
        return "product/create";
    }

    // Xử lý submit form tạo hàng hóa mới; nếu lỗi validate thì hiển thị lại form kèm thông báo lỗi.
    @PostMapping("/owner/products/create")
    public String createProduct(@ModelAttribute("form") ProductCreateRequest form,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        try {
            Integer newProductId = productService.createProduct(form);
            redirectAttributes.addFlashAttribute("successMessage", "Đã tạo hàng hóa mới");
            return "redirect:/owner/products/" + newProductId;
        } catch (ProductValidationException exception) {
            model.addAttribute("errorMessages", exception.getErrors());
            addCreateFormReferenceData(model, false, null);
            return "product/create";
        }
    }

    // Tạo form trống với sẵn 1 đơn vị cơ bản (base unit) mặc định để người dùng điền tiếp.
    private ProductCreateRequest newFormWithBaseUnit() {
        ProductCreateRequest form = new ProductCreateRequest();
        form.setStatus(Boolean.TRUE);
        ProductUnitCreateRequest baseUnit = new ProductUnitCreateRequest();
        baseUnit.setBaseUnit(true);
        baseUnit.setDefaultUnit(true);
        baseUnit.setActive(true);
        baseUnit.setQuantityRelativeToPrevious(1);
        form.getUnits().add(baseUnit);
        return form;
    }

    // Nạp dữ liệu tham chiếu (loại hàng, nhà sản xuất, nguồn gốc, hoạt chất...) cho form tạo/sửa hàng hóa.
    private void addCreateFormReferenceData(Model model, boolean isEdit, Integer productId) {
        List<Type> types = productService.listTypes();
        model.addAttribute("types", types);
        // "Nhóm mặt hàng" lấy từ các giá trị Type.sortType khác nhau; dropdown Loại hàng sẽ được
        // lọc client-side theo nhóm đã chọn để 2 ô luôn khớp nhau.
        model.addAttribute("typeGroups", types.stream()
                .map(Type::getSortType)
                .filter(sortType -> sortType != null && !sortType.isBlank())
                .distinct()
                .toList());
        model.addAttribute("producers", productService.listProducers());
        model.addAttribute("origins", productService.listOrigins());
        model.addAttribute("ingredientNames", productService.listIngredientNames());
        model.addAttribute("ingredientStrengths", productService.listIngredientStrengths());
        // Chỉ có ý nghĩa khi Tạo mới — khi Sửa hiển thị mã thật (không đổi được) của sản phẩm.
        model.addAttribute("nextCode", isEdit ? null : productService.previewNextProductCode());
        model.addAttribute("basePath", "/owner/products");
        model.addAttribute("isEdit", isEdit);
        model.addAttribute("productId", productId);
    }

    /** Form sửa hàng hóa — chỉ Owner. Dùng chung template {@code create.html}, model mang {@code isEdit=true}. */
    @GetMapping("/owner/products/{productId}/edit")
    public String editProductForm(@PathVariable Integer productId,
                                  Model model,
                                  RedirectAttributes redirectAttributes) {
        Optional<ProductCreateRequest> form = productService.getEditForm(productId);
        if (form.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không tìm thấy hàng hóa");
            return "redirect:/owner/products";
        }

        model.addAttribute("form", form.get());
        addCreateFormReferenceData(model, true, productId);
        return "product/create";
    }

    // Xử lý submit form sửa hàng hóa; nếu lỗi validate hoặc dữ liệu không hợp lệ thì báo lỗi tương ứng.
    @PostMapping("/owner/products/{productId}/edit")
    public String updateProduct(@PathVariable Integer productId,
                                @ModelAttribute("form") ProductCreateRequest form,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        try {
            productService.updateProduct(productId, form);
            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật hàng hóa");
            return "redirect:/owner/products/" + productId;
        } catch (ProductValidationException exception) {
            model.addAttribute("errorMessages", exception.getErrors());
            addCreateFormReferenceData(model, true, productId);
            return "product/create";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/owner/products";
        }
    }

    // Hiển thị trang chi tiết 1 hàng hóa (thông tin chung, đơn vị, lô hàng, lịch sử tồn kho gần đây).
    @GetMapping({
            "/owner/products/{productId}",
            "/pharmacist/products/{productId}",
            "/accountant/products/{productId}"
    })
    public String productDetail(@PathVariable Integer productId,
                                @RequestParam(name = "backSupplierId", required = false) Integer backSupplierId,
                                HttpServletRequest request,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);

        Optional<ProductDetailResponse> detail = productService.getProductDetail(productId);
        if (detail.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không tìm thấy hàng hóa");
            return "redirect:" + basePath;
        }

        ProductDetailResponse product = detail.get();
        model.addAttribute("product", product);
        model.addAttribute("basePath", basePath);
        model.addAttribute("canViewRecentHistory", product.isCanViewRecentHistory());
        // Khi mở từ trang chi tiết nhà cung cấp: cho phép quay lại đúng NCC đó.
        model.addAttribute("backSupplierId", backSupplierId);

        return "product/detail";
    }

    @GetMapping({
            "/owner/products/barcode/search-products"
    })
    @ResponseBody
    public List<ProductBarcodeOptionResponse> searchBarcodeProducts(
            @RequestParam(name = "keyword", required = false) String keyword) {
        return productBarcodeService.searchProducts(keyword);
    }

    @GetMapping({
            "/owner/products/barcode/search-purchase-invoices"
    })
    @ResponseBody
    public List<PurchaseInvoiceBarcodeOptionResponse> searchBarcodePurchaseInvoices(
            @RequestParam(name = "keyword", required = false) String keyword) {
        return productBarcodeService.searchPurchaseInvoices(keyword);
    }

    @GetMapping({
            "/owner/products/barcode/purchase-invoices/{purchaseId}/products"
    })
    @ResponseBody
    public List<ProductBarcodeOptionResponse> getPurchaseInvoiceBarcodeProducts(
            @PathVariable Integer purchaseId) {
        return productBarcodeService.getProductsFromPurchaseInvoice(purchaseId);
    }

    /** Trang xem trước A5 của toàn bộ sản phẩm đã chọn trong modal. */
    @PostMapping({
            "/owner/products/barcode/print"
    })
    public String previewBarcodes(@RequestParam("productIds") List<Integer> productIds,
                                  @RequestParam("quantities") List<Integer> quantities,
                                  HttpServletRequest request,
                                  Model model) {
        List<ProductBarcodePrintResponse> items =
                productBarcodeService.buildPrintItems(productIds, quantities);
        model.addAttribute("barcodeItems", items);
        model.addAttribute("basePath", resolveBasePath(request));
        model.addAttribute("totalLabels", items.stream()
                .mapToInt(ProductBarcodePrintResponse::getQuantity).sum());
        return "product/barcode-print";
    }

    /** Tải file PDF A5 để kiểm tra hoặc in sau. */
    @PostMapping(value = {
            "/owner/products/barcode/pdf"
    }, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportBarcodePdf(
            @RequestParam("productIds") List<Integer> productIds,
            @RequestParam("quantities") List<Integer> quantities) {
        List<ProductBarcodePrintResponse> items =
                productBarcodeService.buildPrintItems(productIds, quantities);
        byte[] pdf = productBarcodePdfService.generateA5Pdf(items);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=barcode-a5.pdf; filename*=UTF-8''barcode-a5.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }

    /** Trả ảnh barcode PNG được sinh từ Product.code. */
    @GetMapping(value = {
            "/owner/products/barcode/image/{productId}"
    }, produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> barcodeImage(@PathVariable Integer productId) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noCache())
                    .contentType(MediaType.IMAGE_PNG)
                    .body(productBarcodeService.generateBarcodePng(productId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    // Xác định basePath (/owner, /pharmacist, /accountant) từ URL để dựng link/redirect đúng role.
    private String resolveBasePath(HttpServletRequest request) {
        String uri = request.getRequestURI();

        if (uri.startsWith("/owner/products")) {
            return "/owner/products";
        }
        if (uri.startsWith("/pharmacist/products")) {
            return "/pharmacist/products";
        }
        if (uri.startsWith("/accountant/products")) {
            return "/accountant/products";
        }
        return "/owner/products";
    }
}