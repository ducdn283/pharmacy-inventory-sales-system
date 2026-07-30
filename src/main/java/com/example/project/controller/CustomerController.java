package com.example.project.controller;

import com.example.project.constant.RoleConstants;
import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.CustomerRequest;
import com.example.project.dto.response.CustomerResponse;
import com.example.project.service.CustomerService;
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
 * Shared Customer module: Owner and Pharmacist get full create/edit rights, Accountant is
 * view-only (per the PHÂN QUYỀN MÀN HÌNH matrix). Since all 3 roles share this one
 * {@code /customer/**} path (no role-prefixed variants), the view-only restriction is enforced
 * here with {@link #requireCanManage()} rather than at the route level.
 */
@Controller
@RequestMapping("/customer")
public class CustomerController {

    private final CustomerService customerService;
    private final CurrentUserContext currentUserContext;

    public CustomerController(CustomerService customerService, CurrentUserContext currentUserContext) {
        this.customerService = customerService;
        this.currentUserContext = currentUserContext;
    }

    private void requireCanManage() {
        if (RoleConstants.ACCOUNTANT.equals(currentUserContext.getCurrentRole())) {
            throw new AccessDeniedException("Kế toán chỉ được xem khách hàng, không được tạo/sửa");
        }
    }

    // ------------------------------------------------------------------ list

    @GetMapping
    public String list(@RequestParam(name = "keyword", required = false) String keyword,
                       @RequestParam(name = "type", required = false) String type,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "size", defaultValue = "10") int size,
                       Model model) {
        if (page < 0) page = 0;
        if (size <= 0) size = 10;

        Pageable pageable = PageRequest.of(page, size);
        Page<CustomerResponse> customerPage = customerService.list(keyword, type, pageable);
        CustomerService.CustomerStats stats = customerService.getStats();

        model.addAttribute("customers", customerPage.getContent());
        model.addAttribute("keyword", keyword);
        model.addAttribute("type", type);
        model.addAttribute("currentPage", customerPage.getNumber());
        model.addAttribute("totalPages", customerPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", customerPage.getTotalElements());

        model.addAttribute("totalCustomers", stats.total());
        model.addAttribute("individualCustomers", stats.individual());
        model.addAttribute("companyCustomers", stats.company());
        model.addAttribute("withDebtCustomers", stats.withDebt());

        model.addAttribute("pageTitle", "Danh sách khách hàng");
        return "customer/list";
    }

    // ------------------------------------------------------------------ create

    @GetMapping("/create")
    public String createForm(Model model) {
        requireCanManage();
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CustomerRequest());
        }
        model.addAttribute("pageTitle", "Tạo khách hàng");
        return "customer/create";
    }

    @PostMapping("/create")
    public String create(@Valid @ModelAttribute("form") CustomerRequest form,
                         BindingResult bindingResult,
                         @RequestParam(name = "action", defaultValue = "create") String action,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        requireCanManage();
        if (bindingResult.hasErrors()) {
            model.addAttribute("pageTitle", "Tạo khách hàng");
            return "customer/create";
        }

        Integer newId;
        try {
            newId = customerService.create(form);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("pageTitle", "Tạo khách hàng");
            return "customer/create";
        }

        redirectAttributes.addFlashAttribute("successMessage", "Tạo khách hàng thành công");

        if ("createAndSelect".equals(action)) {
            return "redirect:/customer/" + newId;
        }
        return "redirect:/customer";
    }

    // ------------------------------------------------------------------ kiểm trùng (AJAX)

    /**
     * Kiểm trùng cho màn tạo/sửa, gọi lúc rời ô nhập. Chỉ là lớp báo SỚM cho người dùng — chặn thật
     * vẫn nằm ở {@code CustomerService.validate}, vì màn hình có thể bị bỏ qua (gọi thẳng POST, JS
     * lỗi), và ca hai người nhập cùng lúc thì do UNIQUE index ở DB chặn.
     *
     * @param field    {@code phoneNumber} hoặc {@code taxCode}
     * @param id       id bản ghi đang sửa — bỏ qua chính nó (null khi tạo mới)
     */
    @GetMapping("/check-duplicate")
    @ResponseBody
    public Map<String, Boolean> checkDuplicate(@RequestParam("field") String field,
                                               @RequestParam("value") String value,
                                               @RequestParam(name = "id", required = false) Integer id) {
        requireCanManage();
        boolean duplicate = switch (field) {
            case "phoneNumber" -> customerService.isPhoneTaken(value, id);
            case "taxCode" -> customerService.isTaxCodeTaken(value, id);
            default -> throw new IllegalArgumentException("Trường kiểm trùng không hợp lệ: " + field);
        };
        return Map.of("duplicate", duplicate);
    }

    // ------------------------------------------------------------------ detail / update

    @GetMapping("/{id}")
    public String detail(@PathVariable Integer id, Model model) {
        CustomerResponse customer = customerService.getById(id);
        model.addAttribute("customer", customer);

        if (!model.containsAttribute("form")) {
            model.addAttribute("form", toForm(customer));
        }

        model.addAttribute("recentInvoices", customerService.getRecentInvoices(id));
        model.addAttribute("totalDebt", customerService.getTotalDebt(id));
        model.addAttribute("pageTitle", "Chi tiết khách hàng");
        return "customer/detail";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Integer id,
                         @Valid @ModelAttribute("form") CustomerRequest form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        requireCanManage();
        if (bindingResult.hasErrors()) {
            populateDetail(model, id);
            model.addAttribute("showEditForm", true);
            return "customer/detail";
        }

        try {
            customerService.update(id, form);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            populateDetail(model, id);
            model.addAttribute("showEditForm", true);
            return "customer/detail";
        }

        redirectAttributes.addFlashAttribute("successMessage", "Cập nhật khách hàng thành công");
        return "redirect:/customer/" + id;
    }

    // ------------------------------------------------------------------ helpers

    private void populateDetail(Model model, Integer id) {
        model.addAttribute("customer", customerService.getById(id));
        model.addAttribute("recentInvoices", customerService.getRecentInvoices(id));
        model.addAttribute("totalDebt", customerService.getTotalDebt(id));
        model.addAttribute("pageTitle", "Chi tiết khách hàng");
    }

    private CustomerRequest toForm(CustomerResponse customer) {
        CustomerRequest form = new CustomerRequest();
        form.setCustomerType(customer.getCustomerType());
        form.setName(customer.getName());
        form.setPhoneNumber(customer.getPhoneNumber());
        form.setTaxCode(customer.getTaxCode());
        form.setAddress(customer.getAddress());
        form.setBankAccountNumber(customer.getBankAccountNumber());
        form.setBankName(customer.getBankName());
        form.setNote(customer.getNote());
        return form;
    }
}