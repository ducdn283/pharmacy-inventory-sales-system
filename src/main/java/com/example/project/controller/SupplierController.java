package com.example.project.controller;

import com.example.project.constant.RoleConstants;
import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.SupplierRequest;
import com.example.project.dto.response.SupplierResponse;
import com.example.project.service.SupplierService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * Shared Supplier module: only the Owner may create/edit suppliers and their supplied products;
 * Pharmacist and Accountant are view-only (per the PHÂN QUYỀN MÀN HÌNH matrix — Supplier/
 * SupplierProduct = "Chỉ xem" for both non-owner roles, "Toàn quyền" for Owner). All 3 roles
 * share this one {@code /supplier/**} path, so the write restriction is enforced here with
 * {@link #requireOwner()} rather than at the route level.
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
            throw new AccessDeniedException("Chỉ Chủ nhà thuốc được tạo/sửa nhà cung cấp");
        }
    }

    // ------------------------------------------------------------------ list

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

    // ------------------------------------------------------------------ create

    @GetMapping("/create")
    public String createForm(Model model) {
        requireOwner();
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
        requireOwner();
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

    // ------------------------------------------------------------------ kiểm trùng (AJAX)

    /**
     * Kiểm trùng cho màn tạo/sửa, gọi lúc rời ô nhập. Chỉ là lớp báo SỚM — chặn thật vẫn nằm ở
     * {@code SupplierService.validateUnique}, vì màn hình có thể bị bỏ qua (gọi thẳng POST, JS lỗi,
     * hai người nhập cùng lúc) và bảng {@code supplier} không có ràng buộc UNIQUE nào ở DB.
     *
     * @param field {@code phone}, {@code email} hoặc {@code taxCode}
     * @param id    id bản ghi đang sửa — bỏ qua chính nó (null khi tạo mới)
     */
    @GetMapping("/check-duplicate")
    @ResponseBody
    public Map<String, Boolean> checkDuplicate(@RequestParam("field") String field,
                                               @RequestParam("value") String value,
                                               @RequestParam(name = "id", required = false) Integer id) {
        requireOwner();
        boolean duplicate = switch (field) {
            case "phone" -> supplierService.isPhoneTaken(value, id);
            case "email" -> supplierService.isEmailTaken(value, id);
            case "taxCode" -> supplierService.isTaxCodeTaken(value, id);
            default -> throw new IllegalArgumentException("Trường kiểm trùng không hợp lệ: " + field);
        };
        return Map.of("duplicate", duplicate);
    }

    // ------------------------------------------------------------------ detail / update

    @GetMapping("/{id}")
    public String detail(@PathVariable Integer id, Model model) {
        SupplierResponse supplier = supplierService.getById(id);
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
        model.addAttribute("pageTitle", "Chi tiết nhà cung cấp");
        return "supplier/detail";
    }

    // --------------------------------------------------- add supplied products

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

    @PostMapping("/{id}")
    public String update(@PathVariable Integer id,
                         @Valid @ModelAttribute("form") SupplierRequest form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        requireOwner();
        if (bindingResult.hasErrors()) {
            model.addAttribute("supplier", supplierService.getById(id));
            model.addAttribute("supplierProducts", supplierService.getProducts(id));
            model.addAttribute("availableProducts", supplierService.getAvailableProducts(id));
            model.addAttribute("showEditForm", true);
            model.addAttribute("pageTitle", "Chi tiết nhà cung cấp");
            return "supplier/detail";
        }

        try {
            supplierService.update(id, form);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("supplier", supplierService.getById(id));
            model.addAttribute("supplierProducts", supplierService.getProducts(id));
            model.addAttribute("availableProducts", supplierService.getAvailableProducts(id));
            model.addAttribute("showEditForm", true);
            model.addAttribute("pageTitle", "Chi tiết nhà cung cấp");
            return "supplier/detail";
        }

        redirectAttributes.addFlashAttribute("successMessage", "Cập nhật nhà cung cấp thành công");
        return "redirect:/supplier/" + id;
    }
}
