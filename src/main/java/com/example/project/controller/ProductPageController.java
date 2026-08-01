package com.example.project.controller;

import com.example.project.dto.request.ProductCreateRequest;
import com.example.project.dto.request.ProductUnitCreateRequest;
import com.example.project.dto.response.ProductDetailResponse;
import com.example.project.dto.response.ProductRowResponse;
import com.example.project.entity.Type;
import com.example.project.service.ProductService;
import com.example.project.service.ProductValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;

/**
 * Thymeleaf page controller for the Product List / Detail / Create / Edit screens. Serves the
 * same screen under each role prefix (Owner / Pharmacist / Accountant) — Create and Edit are
 * Owner-only.
 */
@Controller
public class ProductPageController {

    private final ProductService productService;

    public ProductPageController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping({
            "/owner/products",
            "/pharmacist/products",
            "/accountant/products"
    })
    public String listProducts(@RequestParam(name = "keyword", required = false) String keyword,
                               @RequestParam(name = "typeId", required = false) Integer typeId,
                               @RequestParam(name = "producerId", required = false) Integer producerId,
                               @RequestParam(name = "stockStatus", required = false) String stockStatus,
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

        Page<ProductRowResponse> productPage =
                productService.searchProducts(keyword, typeId, producerId, stockStatus, PageRequest.of(page, size));

        model.addAttribute("productPage", productPage);
        model.addAttribute("products", productPage.getContent());
        model.addAttribute("stats", productService.getStats());

        model.addAttribute("types", productService.listTypes());
        model.addAttribute("producers", productService.listProducers());

        model.addAttribute("keyword", keyword);
        model.addAttribute("filterTypeId", typeId);
        model.addAttribute("filterProducerId", producerId);
        model.addAttribute("filterStockStatus", stockStatus);

        model.addAttribute("currentPage", productPage.getNumber());
        model.addAttribute("totalPages", productPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", productPage.getTotalElements());

        model.addAttribute("basePath", resolveBasePath(request));

        return "product/list";
    }

    /**
     * Create Product form — Owner only (the {@code /owner/**} security rule blocks other roles with
     * 403). Mapped on the literal {@code /create} path, which Spring prefers over the
     * {@code /{productId}} detail mapping.
     */
    @GetMapping("/owner/products/create")
    public String createProductForm(Model model) {
        model.addAttribute("form", newFormWithBaseUnit());
        addCreateFormReferenceData(model, false, null);
        return "product/create";
    }

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

    private void addCreateFormReferenceData(Model model, boolean isEdit, Integer productId) {
        List<Type> types = productService.listTypes();
        model.addAttribute("types", types);
        // "Nhóm mặt hàng" is derived from the distinct Type.sortType values; the Type dropdown is
        // then filtered client-side to the selected group so the two boxes stay consistent.
        model.addAttribute("typeGroups", types.stream()
                .map(Type::getSortType)
                .filter(sortType -> sortType != null && !sortType.isBlank())
                .distinct()
                .toList());
        model.addAttribute("producers", productService.listProducers());
        model.addAttribute("origins", productService.listOrigins());
        model.addAttribute("ingredientNames", productService.listIngredientNames());
        model.addAttribute("ingredientStrengths", productService.listIngredientStrengths());
        // Only meaningful on Create — Edit shows the product's real, immutable code instead.
        model.addAttribute("nextCode", isEdit ? null : productService.previewNextProductCode());
        model.addAttribute("basePath", "/owner/products");
        model.addAttribute("isEdit", isEdit);
        model.addAttribute("productId", productId);
    }

    /**
     * Edit Product form — Owner only, same access rule as Create. Reuses {@code create.html} in
     * edit mode: the model carries {@code isEdit=true} and {@code productId} so the template posts
     * to the edit route and shows the real (immutable) product code instead of a generated preview.
     */
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
