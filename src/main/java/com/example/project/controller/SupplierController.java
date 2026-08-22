package com.example.project.controller;

import com.example.project.constant.RoleConstants;
import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.SupplierProductUpdateRequest;
import com.example.project.dto.request.SupplierRequest;
import com.example.project.dto.response.SupplierResponse;
import com.example.project.service.SupplierService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * Module Nhà cung cấp dùng chung: Owner toàn quyền; Kế toán được xem danh sách/chi tiết và tạo NCC
 * mới (BA, 2026-08-11 — Kế toán là người trực tiếp làm việc với NCC khi lập Phiếu nhập, nên cần tự
 * thêm NCC ngay tại chỗ); Dược sĩ chỉ xem. Sửa NCC đã có và mọi thao tác trên danh sách sản phẩm
 * cung ứng vẫn Owner-only — danh sách đó là căn cứ lập dự trù mua hàng, không phải dữ liệu thao tác
 * hằng ngày của Kế toán (BA chốt 2026-08-15).
 * Cả 3 role dùng chung một đường dẫn {@code /supplier/**}, nên giới hạn quyền ghi được chặn ở đây
 * bằng {@link #requireOwner()}/{@link #requireCanCreate()} thay vì ở tầng route.
 */
@Controller
@RequestMapping("/supplier")
public class SupplierController {

    private final SupplierService supplierService;
    private final CurrentUserContext currentUserContext;

    public SupplierController(SupplierService supplierService, CurrentUserContext currentUserContext) {
        this.supplierService = supplierService;
        this.currentUserContext = currentUserContext;
    }

    private void requireOwner() {
        if (!RoleConstants.OWNER.equals(currentUserContext.getCurrentRole())) {
            throw new AccessDeniedException("Chỉ Chủ nhà thuốc được sửa nhà cung cấp");
        }
    }

    /** Creating a new supplier: Owner or Accountant. Pharmacist stays view-only. */
    private void requireCanCreate() {
        String role = currentUserContext.getCurrentRole();
        if (!RoleConstants.OWNER.equals(role) && !RoleConstants.ACCOUNTANT.equals(role)) {
            throw new AccessDeniedException("Chỉ Chủ nhà thuốc hoặc Kế toán được tạo nhà cung cấp");
        }
    }

    // ------------------------------------------------------------------ danh sách

    @GetMapping
    public String list(@RequestParam(name = "keyword", required = false) String keyword,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "size", defaultValue = "10") int size,
                       Model model) {
        if (page < 0) page = 0;
        if (size <= 0) size = 10;

        Pageable pageable = PageRequest.of(page, size);
        Page<SupplierResponse> supplierPage = supplierService.list(keyword, pageable);
        SupplierService.SupplierStats stats = supplierService.getStats();

        model.addAttribute("suppliers", supplierPage.getContent());
        model.addAttribute("supplierPage", supplierPage);
        model.addAttribute("keyword", keyword);
        model.addAttribute("currentPage", supplierPage.getNumber());
        model.addAttribute("totalPages", supplierPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", supplierPage.getTotalElements());

        model.addAttribute("totalSuppliers", stats.total());
        model.addAttribute("withProducts", stats.withProducts());
        model.addAttribute("withoutProducts", stats.withoutProducts());
        model.addAttribute("totalProducts", stats.totalProducts());

        model.addAttribute("pageTitle", "Danh sách nhà cung cấp");
        return "supplier/list";
    }

    // ------------------------------------------------------------------ tạo

    @GetMapping("/create")
    public String createForm(Model model) {
        requireCanCreate();
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new SupplierRequest());
        }
        model.addAttribute("pageTitle", "Tạo nhà cung cấp");
        return "supplier/create";
    }

    @PostMapping("/create")
    public String create(@Valid @ModelAttribute("form") SupplierRequest form,
                         BindingResult bindingResult,
                         @RequestParam(name = "action", defaultValue = "create") String action,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        requireCanCreate();
        if (bindingResult.hasErrors()) {
            model.addAttribute("pageTitle", "Tạo nhà cung cấp");
            return "supplier/create";
        }

        Integer newId;
        try {
            newId = supplierService.create(form);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("pageTitle", "Tạo nhà cung cấp");
            return "supplier/create";
        }

        redirectAttributes.addFlashAttribute("successMessage", "Tạo nhà cung cấp thành công");

        if ("createAndAdd".equals(action)) {
            return "redirect:/supplier/" + newId;
        }
        return "redirect:/supplier";
    }

    // Thêm nhanh từ nút "+" ở màn tạo Phiếu nhập (Owner hoặc Kế toán — ai tạo được NCC thì dùng
    // được nút này, xem requireCanCreate()). Cùng khuôn JSON vào/ra với endpoint "thêm khách hàng
    // nhanh" ở màn bán hàng (InvoiceController.createCustomerFromSelling) và nút "+" nhà sản xuất
    // ở màn tạo sản phẩm (ProducerController.quickCreateProducer).
    @PostMapping(value = "/quick-create",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> quickCreate(@Valid @RequestBody SupplierRequest request,
                                         BindingResult bindingResult) {
        requireCanCreate();
        if (bindingResult.hasErrors()) {
            String message = bindingResult.getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .findFirst()
                    .orElse("Dữ liệu không hợp lệ");
            return ResponseEntity.badRequest().body(Map.of("message", message));
        }
        try {
            Integer newId = supplierService.create(request);
            return ResponseEntity.ok(supplierService.getById(newId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    // ------------------------------------------------------------------ kiểm trùng (AJAX)

    /**
     * Kiểm trùng cho màn tạo/sửa, gọi lúc rời ô nhập. Chỉ là lớp báo SỚM — chặn thật vẫn nằm ở
     * {@code SupplierService.validateUnique}, vì màn hình có thể bị bỏ qua (gọi thẳng POST, JS lỗi),
     * và ca hai người nhập cùng lúc thì do UNIQUE index ở DB chặn.
     *
     * @param field {@code phone}, {@code email} hoặc {@code taxCode}
     * @param id    id bản ghi đang sửa — bỏ qua chính nó (null khi tạo mới)
     */
    @GetMapping("/check-duplicate")
    @ResponseBody
    public Map<String, Boolean> checkDuplicate(@RequestParam("field") String field,
                                               @RequestParam("value") String value,
                                               @RequestParam(name = "id", required = false) Integer id) {
        requireCanCreate();
        boolean duplicate = switch (field) {
            case "phone" -> supplierService.isPhoneTaken(value, id);
            case "email" -> supplierService.isEmailTaken(value, id);
            case "taxCode" -> supplierService.isTaxCodeTaken(value, id);
            default -> throw new IllegalArgumentException("Trường kiểm trùng không hợp lệ: " + field);
        };
        return Map.of("duplicate", duplicate);
    }

    // ------------------------------------------------------------------ chi tiết / sửa

    @GetMapping("/{id}")
    public String detail(@PathVariable Integer id, Model model,
                         RedirectAttributes redirectAttributes) {
        SupplierResponse supplier;
        try {
            supplier = supplierService.getById(id);
        } catch (IllegalArgumentException exception) {
            // Gõ tay id không tồn tại: về danh sách kèm thông báo thay vì rơi vào trang lỗi chung.
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/supplier";
        }
        model.addAttribute("supplier", supplier);

        if (!model.containsAttribute("form")) {
            SupplierRequest form = new SupplierRequest();
            form.setName(supplier.getName());
            form.setPhone(supplier.getPhone());
            form.setEmail(supplier.getEmail());
            form.setAddress(supplier.getAddress());
            form.setTaxCode(supplier.getTaxCode());
            model.addAttribute("form", form);
        }

        model.addAttribute("supplierProducts", supplierService.getProducts(id));
        model.addAttribute("availableProducts", supplierService.getAvailableProducts(id));
        model.addAttribute("supplierDebts", supplierService.getOutstandingPurchaseInvoices(id));
        model.addAttribute("totalSupplierDebt", supplierService.getTotalDebt(id));
        model.addAttribute("productBasePath", productBasePath());
        model.addAttribute("purchaseInvoiceBasePath", purchaseInvoiceBasePath());
        model.addAttribute("pageTitle", "Chi tiết nhà cung cấp");
        return "supplier/detail";
    }

    /** Nạp lại dữ liệu màn chi tiết khi phải render lại trang vì form cập nhật lỗi. */
    private void populateDetail(Model model, Integer id) {
        model.addAttribute("supplier", supplierService.getById(id));
        model.addAttribute("supplierProducts", supplierService.getProducts(id));
        model.addAttribute("availableProducts", supplierService.getAvailableProducts(id));
        model.addAttribute("supplierDebts", supplierService.getOutstandingPurchaseInvoices(id));
        model.addAttribute("totalSupplierDebt", supplierService.getTotalDebt(id));
        model.addAttribute("productBasePath", productBasePath());
        model.addAttribute("purchaseInvoiceBasePath", purchaseInvoiceBasePath());
        model.addAttribute("pageTitle", "Chi tiết nhà cung cấp");
    }

    /**
     * Đường dẫn màn chi tiết hàng hóa theo vai trò đang đăng nhập. Cả 3 role đều có màn này nhưng
     * mỗi role chỉ vào được nhánh của mình ({@code /owner|pharmacist|accountant/products/{id}}),
     * nên viết cứng {@code /owner/products} là Kế toán và Dược sĩ bấm "Xem" sẽ ăn trang 403.
     */
    private String productBasePath() {
        String role = currentUserContext.getCurrentRole();
        return RoleConstants.isValid(role) ? "/" + RoleConstants.urlPrefix(role) + "/products" : null;
    }

    /**
     * Đường dẫn màn chi tiết phiếu nhập, hoặc {@code null} với Dược sĩ — module Phiếu nhập chỉ mở
     * cho Owner và Kế toán, nên dòng công nợ của Dược sĩ không có link để bấm.
     */
    private String purchaseInvoiceBasePath() {
        String role = currentUserContext.getCurrentRole();
        if (!RoleConstants.OWNER.equals(role) && !RoleConstants.ACCOUNTANT.equals(role)) {
            return null;
        }
        return "/" + RoleConstants.urlPrefix(role) + "/purchase-invoices";
    }

    // --------------------------------------------------- thêm sản phẩm cung ứng

    @PostMapping("/{id}/products")
    public String addProducts(@PathVariable Integer id,
                              @RequestParam(name = "productIds", required = false) java.util.List<Integer> productIds,
                              RedirectAttributes redirectAttributes) {
        requireOwner();
        if (productIds == null || productIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Vui lòng chọn ít nhất một sản phẩm để thêm");
            return "redirect:/supplier/" + id;
        }

        try {
            int added = supplierService.addProducts(id, productIds);
            redirectAttributes.addFlashAttribute("successMessage", "Đã thêm " + added + " sản phẩm cung ứng");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/supplier/" + id;
    }

    /**
     * Sửa một dòng sản phẩm cung ứng: trạng thái cung ứng, cờ ưu tiên, ghi chú. Owner-only như mọi
     * thao tác ghi khác của màn này.
     */
    @PostMapping("/{id}/products/{supplierProductId}")
    public String updateSupplierProduct(@PathVariable Integer id,
                                        @PathVariable Integer supplierProductId,
                                        @Valid @ModelAttribute("supplierProductForm")
                                        SupplierProductUpdateRequest form,
                                        BindingResult bindingResult,
                                        RedirectAttributes redirectAttributes) {
        requireOwner();
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", bindingResult.getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .findFirst()
                    .orElse("Dữ liệu không hợp lệ"));
            return "redirect:/supplier/" + id;
        }

        try {
            supplierService.updateSupplierProduct(id, supplierProductId, form);
            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật sản phẩm cung ứng");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:/supplier/" + id;
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Integer id,
                         @Valid @ModelAttribute("form") SupplierRequest form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        requireOwner();
        if (bindingResult.hasErrors()) {
            populateDetail(model, id);
            model.addAttribute("showEditForm", true);
            return "supplier/detail";
        }

        try {
            supplierService.update(id, form);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            populateDetail(model, id);
            model.addAttribute("showEditForm", true);
            return "supplier/detail";
        }

        redirectAttributes.addFlashAttribute("successMessage", "Cập nhật nhà cung cấp thành công");
        return "redirect:/supplier/" + id;
    }
}
