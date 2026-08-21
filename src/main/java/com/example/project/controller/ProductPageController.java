package com.example.project.controller;

import com.example.project.dto.request.ProductCreateRequest;
import com.example.project.dto.request.ProductUnitCreateRequest;
import com.example.project.dto.response.ProductBarcodeOptionResponse;
import com.example.project.dto.response.ProductBarcodePrintItemResponse;
import com.example.project.dto.response.ProductDetailResponse;
import com.example.project.dto.response.ProductRowResponse;
import com.example.project.dto.response.PurchaseInvoiceBarcodeOptionResponse;
import com.example.project.entity.Type;
import com.example.project.service.ProcurementplanService;
import com.example.project.service.ProductBarcodeService;
import com.example.project.service.ProductService;
import com.example.project.service.ProductValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Controller cho các màn Danh sách / Chi tiết / Tạo / Sửa hàng hóa.
 *
 * Các chức năng Tạo và Sửa chỉ dành cho Owner.
 * Chức năng in barcode được sử dụng tại màn hình danh sách sản phẩm.
 */
@Controller
public class ProductPageController {

    private final ProductService productService;
    private final ProcurementplanService procurementplanService;
    private final ProductBarcodeService productBarcodeService;

    public ProductPageController(
            ProductService productService,
            ProcurementplanService procurementplanService,
            ProductBarcodeService productBarcodeService
    ) {
        this.productService = productService;
        this.procurementplanService = procurementplanService;
        this.productBarcodeService = productBarcodeService;
    }

    /**
     * Hiển thị danh sách hàng hóa.
     */
    @GetMapping({
            "/owner/products",
            "/pharmacist/products",
            "/accountant/products"
    })
    public String listProducts(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "typeId", required = false) Integer typeId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "sortOrder", required = false) String sortOrder,
            @RequestParam(name = "nearExpiryOnly", defaultValue = "false")
            boolean nearExpiryOnly,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            HttpServletRequest request,
            Model model
    ) {
        if (page < 0) {
            page = 0;
        }

        if (size <= 0) {
            size = 10;
        }

        String basePath = resolveBasePath(request);
        boolean canFilterBusinessStatus =
                "/owner/products".equals(basePath);

        String stockStatus =
                ProductService.STOCK_STATUS_IN.equals(status)
                        || ProductService.STOCK_STATUS_OUT.equals(status)
                        ? status
                        : null;

        String businessStatus =
                ProductService.BUSINESS_STATUS_ACTIVE.equals(status)
                        || ProductService.BUSINESS_STATUS_INACTIVE.equals(status)
                        ? status
                        : null;

        Page<ProductRowResponse> productPage =
                productService.searchProducts(
                        keyword,
                        null,
                        null,
                        null,
                        null,
                        typeId,
                        stockStatus,
                        nearExpiryOnly,
                        businessStatus,
                        sortOrder,
                        PageRequest.of(page, size)
                );

        model.addAttribute("productPage", productPage);
        model.addAttribute("products", productPage.getContent());
        model.addAttribute("stats", productService.getStats());
        model.addAttribute("types", productService.listTypes());

        model.addAttribute("keyword", keyword);
        model.addAttribute("filterTypeId", typeId);

        model.addAttribute(
                "filterStatus",
                canFilterBusinessStatus || businessStatus == null
                        ? status
                        : null
        );

        model.addAttribute(
                "canFilterBusinessStatus",
                canFilterBusinessStatus
        );

        model.addAttribute("sortOrder", sortOrder);
        model.addAttribute("nearExpiryOnly", nearExpiryOnly);

        model.addAttribute(
                "currentPage",
                productPage.getNumber()
        );

        model.addAttribute(
                "totalPages",
                productPage.getTotalPages()
        );

        model.addAttribute("pageSize", size);

        model.addAttribute(
                "totalItems",
                productPage.getTotalElements()
        );

        model.addAttribute("basePath", basePath);

        model.addAttribute(
                "restockProducts",
                "/owner/products".equals(basePath)
                        ? procurementplanService.listRestockNeededProducts()
                        : List.of()
        );

        return "product/list";
    }

    /**
     * Tìm sản phẩm để thêm vào danh sách in barcode.
     */
    @GetMapping({
            "/owner/products/barcode/search-products",
            "/pharmacist/products/barcode/search-products",
            "/accountant/products/barcode/search-products"
    })
    @ResponseBody
    public List<ProductBarcodeOptionResponse> searchBarcodeProducts(
            @RequestParam(name = "keyword", required = false)
            String keyword
    ) {
        return productBarcodeService.searchProducts(keyword);
    }

    /**
     * Tìm hóa đơn nhập để thêm sản phẩm theo hóa đơn.
     */
    @GetMapping({
            "/owner/products/barcode/search-purchase-invoices",
            "/pharmacist/products/barcode/search-purchase-invoices",
            "/accountant/products/barcode/search-purchase-invoices"
    })
    @ResponseBody
    public List<PurchaseInvoiceBarcodeOptionResponse>
    searchPurchaseInvoices(
            @RequestParam(name = "keyword", required = false)
            String keyword
    ) {
        return productBarcodeService.searchPurchaseInvoices(keyword);
    }

    /**
     * Lấy danh sách sản phẩm và số lượng từ một hóa đơn nhập.
     */
    @GetMapping({
            "/owner/products/barcode/purchase-invoices/{purchaseId}/products",
            "/pharmacist/products/barcode/purchase-invoices/{purchaseId}/products",
            "/accountant/products/barcode/purchase-invoices/{purchaseId}/products"
    })
    @ResponseBody
    public ResponseEntity<?> getProductsFromPurchaseInvoice(
            @PathVariable Integer purchaseId
    ) {
        try {
            List<ProductBarcodePrintItemResponse> products =
                    productBarcodeService
                            .getProductsFromPurchaseInvoice(purchaseId);

            return ResponseEntity.ok(products);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", exception.getMessage())
            );
        }
    }

    /**
     * Nhận danh sách sản phẩm từ modal và chuyển sang trang in barcode.
     */
    @PostMapping({
            "/owner/products/barcode/print",
            "/pharmacist/products/barcode/print",
            "/accountant/products/barcode/print"
    })
    public String printBarcodes(
            @RequestParam(
                    name = "productIds",
                    required = false
            )
            List<Integer> productIds,

            @RequestParam(
                    name = "quantities",
                    required = false
            )
            List<Integer> quantities,

            HttpServletRequest request,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        String basePath = resolveBasePath(request);

        try {
            List<ProductBarcodePrintItemResponse> printItems =
                    productBarcodeService.buildPrintItems(
                            productIds,
                            quantities
                    );

            int totalLabels = printItems.stream()
                    .mapToInt(item ->
                            item.getQuantity() == null
                                    ? 0
                                    : item.getQuantity()
                    )
                    .sum();

            model.addAttribute("printItems", printItems);
            model.addAttribute("totalLabels", totalLabels);
            model.addAttribute("basePath", basePath);

            return "product/barcode-print";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:" + basePath;
        }
    }

    /**
     * Trả ảnh barcode PNG cho một sản phẩm.
     */
    @GetMapping(
            value = {
                    "/owner/products/barcode/image/{productId}",
                    "/pharmacist/products/barcode/image/{productId}",
                    "/accountant/products/barcode/image/{productId}"
            },
            produces = MediaType.IMAGE_PNG_VALUE
    )
    public ResponseEntity<byte[]> barcodeImage(
            @PathVariable Integer productId
    ) {
        try {
            byte[] barcodeImage =
                    productBarcodeService
                            .generateBarcodePng(productId);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noCache())
                    .contentType(MediaType.IMAGE_PNG)
                    .body(barcodeImage);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Hiển thị form tạo hàng hóa.
     */
    @GetMapping("/owner/products/create")
    public String createProductForm(Model model) {
        model.addAttribute(
                "form",
                newFormWithBaseUnit()
        );

        addCreateFormReferenceData(
                model,
                false,
                null
        );

        return "product/create";
    }

    /**
     * Xử lý tạo hàng hóa mới.
     */
    @PostMapping("/owner/products/create")
    public String createProduct(
            @ModelAttribute("form")
            ProductCreateRequest form,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            Integer newProductId =
                    productService.createProduct(form);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã tạo hàng hóa mới"
            );

            return "redirect:/owner/products/"
                    + newProductId;
        } catch (ProductValidationException exception) {
            model.addAttribute(
                    "errorMessages",
                    exception.getErrors()
            );

            addCreateFormReferenceData(
                    model,
                    false,
                    null
            );

            return "product/create";
        }
    }

    /**
     * Tạo form trống với một đơn vị cơ bản.
     */
    private ProductCreateRequest newFormWithBaseUnit() {
        ProductCreateRequest form =
                new ProductCreateRequest();

        form.setStatus(Boolean.TRUE);

        ProductUnitCreateRequest baseUnit =
                new ProductUnitCreateRequest();

        baseUnit.setBaseUnit(true);
        baseUnit.setDefaultUnit(true);
        baseUnit.setActive(true);
        baseUnit.setQuantityRelativeToPrevious(1);

        form.getUnits().add(baseUnit);

        return form;
    }

    /**
     * Nạp dữ liệu tham chiếu cho form tạo/sửa.
     */
    private void addCreateFormReferenceData(
            Model model,
            boolean isEdit,
            Integer productId
    ) {
        List<Type> types = productService.listTypes();

        model.addAttribute("types", types);

        model.addAttribute(
                "typeGroups",
                types.stream()
                        .map(Type::getSortType)
                        .filter(sortType ->
                                sortType != null
                                        && !sortType.isBlank()
                        )
                        .distinct()
                        .toList()
        );

        model.addAttribute(
                "producers",
                productService.listProducers()
        );

        model.addAttribute(
                "origins",
                productService.listOrigins()
        );

        model.addAttribute(
                "ingredientNames",
                productService.listIngredientNames()
        );

        model.addAttribute(
                "ingredientStrengths",
                productService.listIngredientStrengths()
        );

        model.addAttribute(
                "nextCode",
                isEdit
                        ? null
                        : productService.previewNextProductCode()
        );

        model.addAttribute(
                "basePath",
                "/owner/products"
        );

        model.addAttribute("isEdit", isEdit);
        model.addAttribute("productId", productId);
    }

    /**
     * Hiển thị form sửa hàng hóa.
     */
    @GetMapping("/owner/products/{productId}/edit")
    public String editProductForm(
            @PathVariable Integer productId,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        Optional<ProductCreateRequest> form =
                productService.getEditForm(productId);

        if (form.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy hàng hóa"
            );

            return "redirect:/owner/products";
        }

        model.addAttribute("form", form.get());

        addCreateFormReferenceData(
                model,
                true,
                productId
        );

        return "product/create";
    }

    /**
     * Xử lý cập nhật hàng hóa.
     */
    @PostMapping("/owner/products/{productId}/edit")
    public String updateProduct(
            @PathVariable Integer productId,
            @ModelAttribute("form")
            ProductCreateRequest form,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            productService.updateProduct(
                    productId,
                    form
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã cập nhật hàng hóa"
            );

            return "redirect:/owner/products/"
                    + productId;
        } catch (ProductValidationException exception) {
            model.addAttribute(
                    "errorMessages",
                    exception.getErrors()
            );

            addCreateFormReferenceData(
                    model,
                    true,
                    productId
            );

            return "product/create";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/owner/products";
        }
    }

    /**
     * Hiển thị chi tiết hàng hóa.
     */
    @GetMapping({
            "/owner/products/{productId}",
            "/pharmacist/products/{productId}",
            "/accountant/products/{productId}"
    })
    public String productDetail(
            @PathVariable Integer productId,
            @RequestParam(
                    name = "backSupplierId",
                    required = false
            )
            Integer backSupplierId,
            HttpServletRequest request,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        String basePath = resolveBasePath(request);

        Optional<ProductDetailResponse> detail =
                productService.getProductDetail(productId);

        if (detail.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy hàng hóa"
            );

            return "redirect:" + basePath;
        }

        ProductDetailResponse product = detail.get();

        model.addAttribute("product", product);
        model.addAttribute("basePath", basePath);

        model.addAttribute(
                "canViewRecentHistory",
                product.isCanViewRecentHistory()
        );

        model.addAttribute(
                "backSupplierId",
                backSupplierId
        );

        return "product/detail";
    }

    /**
     * Xác định đường dẫn sản phẩm theo role hiện tại.
     */
    private String resolveBasePath(
            HttpServletRequest request
    ) {
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