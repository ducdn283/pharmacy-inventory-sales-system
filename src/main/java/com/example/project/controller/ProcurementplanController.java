package com.example.project.controller;

import com.example.project.dto.request.ProcurementPlanCreateRequest;
import com.example.project.dto.request.ProcurementPlanDetailCreateRequest;
import com.example.project.dto.response.ProcurementPlanPrintPageResponse;
import com.example.project.dto.response.ProcurementPlanDetailRowView;
import com.example.project.dto.response.ProcurementProductSearchResponse;
import com.example.project.dto.response.ProcurementProductStockResponse;
import com.example.project.dto.response.ProcurementSupplierSearchResponse;
import com.example.project.dto.response.ProcurementplanResponse;
import com.example.project.dto.response.SupplierCostPriceResponse;
import com.example.project.entity.Supplier;
import com.example.project.service.ProcurementplanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.beans.propertyeditors.CustomNumberEditor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Màn quản lý dự trù mua hàng (danh sách / tạo / sửa / in / xóa).
 * Owner: toàn quyền; Accountant: xem danh sách, chi tiết và in.
 */
@Controller
public class ProcurementplanController {
    private static final String OWNER_BASE = "/owner/procurements";
    private static final String ACCOUNTANT_BASE = "/accountant/procurements";

    private final ProcurementplanService procurementplanService;

    public ProcurementplanController(ProcurementplanService procurementplanService) {
        this.procurementplanService = procurementplanService;
    }

    /** Chuyển giá trị form ({@code String}) sang {@link BigDecimal} / {@link Integer} khi binding. */
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(BigDecimal.class, new CustomNumberEditor(BigDecimal.class, true));
        binder.registerCustomEditor(Integer.class, new CustomNumberEditor(Integer.class, true));
    }

    /** Danh sách dự trù có phân trang, tìm kiếm theo mã và lọc theo ngày/trạng thái. */
    @GetMapping({OWNER_BASE, ACCOUNTANT_BASE})
    public String procurementPlanList(@RequestParam(name = "search", required = false) String search,
                                      @RequestParam(name = "fromDate", required = false) String fromDate,
                                      @RequestParam(name = "toDate", required = false) String toDate,
                                      @RequestParam(name = "status", required = false) String status,
                                      @RequestParam(name = "page", defaultValue = "0") int page,
                                      @RequestParam(name = "size", defaultValue = "5") int size,
                                      HttpServletRequest request,
                                      Model model) {
        if (page < 0) {
            page = 0;
        }

        if (size <= 0) {
            size = 5;
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date")
                .and(Sort.by(Sort.Direction.DESC, "id")));
        Page<ProcurementplanResponse> procurementPage = procurementplanService.list(
                search, fromDate, toDate, status, pageable);
        String basePath = resolveBasePath(request);

        model.addAttribute("procurementPlans", procurementPage.getContent());
        model.addAttribute("totalProcurementPlans", procurementplanService.countAll());
        model.addAttribute("completedProcurementPlans", procurementplanService.countCompleted());
        model.addAttribute("inProgressProcurementPlans", procurementplanService.countInProgress());
        model.addAttribute("statuses", procurementplanService.listStatuses());
        model.addAttribute("search", search);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterStatus", status);
        model.addAttribute("currentPage", procurementPage.getNumber());
        model.addAttribute("totalPages", procurementPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", procurementPage.getTotalElements());
        model.addAttribute("pageTitle", "Danh sách dự trù mua hàng");
        model.addAttribute("basePath", basePath);
        return "procurement-plan/procurement-plan-list";
    }

    /** API tìm sản phẩm trên form dự trù — trả JSON. */
    @GetMapping("/owner/procurements/products/search")
    @ResponseBody
    public List<ProcurementProductSearchResponse> searchProducts(@RequestParam(name = "keyword") String keyword,
                                                                 @RequestParam(name = "limit", defaultValue = "12") int limit) {
        return procurementplanService.searchProducts(keyword, limit);
    }

    /** API xem tồn kho tất cả sản phẩm — modal trên form tạo dự trù. */
    @GetMapping("/owner/procurements/products/stock-overview")
    @ResponseBody
    public List<ProcurementProductStockResponse> listProductStockOverview(
            @RequestParam(name = "sort", defaultValue = "desc") String sort) {
        return procurementplanService.listAllProductStocks(sort);
    }

    /** API lấy giá nhập của nhà cung cấp cho một sản phẩm. */
    @GetMapping("/owner/procurements/supplier-cost-price")
    @ResponseBody
    public SupplierCostPriceResponse getSupplierCostPrice(@RequestParam(name = "supplierId") Integer supplierId,
                                                          @RequestParam(name = "productId") Integer productId) {
        BigDecimal costPrice = procurementplanService.getSupplierCostPrice(supplierId, productId);
        return new SupplierCostPriceResponse(costPrice);
    }

    /** API tìm nhà cung cấp, kèm giá nhập nếu đã chọn sản phẩm. */
    @GetMapping("/owner/procurements/suppliers/search")
    @ResponseBody
    public List<ProcurementSupplierSearchResponse> searchSuppliers(
            @RequestParam(name = "productId", required = false) Integer productId,
            @RequestParam(name = "keyword", defaultValue = "") String keyword) {
        return procurementplanService.searchSuppliersForProduct(productId, keyword);
    }

    /**
     * Form tạo dự trù — giữ {@code procurementPlanForm} nếu redirect sau lỗi validate.
     * {@code restockAll}: mở form với sẵn sản phẩm sắp hết/hết hàng (nút "Tạo dự trù hàng cần nhập").
     */
    @GetMapping("/owner/procurements/create-procurementplan")
    public String createProcurementPlanForm(@RequestParam(name = "restockAll", required = false, defaultValue = "false") boolean restockAll,
                                            @RequestParam(name = "productIds", required = false) List<Integer> productIds,
                                            HttpServletRequest request, Model model) {
        if (!model.containsAttribute("procurementPlanForm")) {
            ProcurementPlanCreateRequest form = new ProcurementPlanCreateRequest();
            Set<Integer> requestedProductIds = new LinkedHashSet<>();
            if (productIds != null) {
                productIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .forEach(requestedProductIds::add);
            } else if (restockAll) {
                requestedProductIds.addAll(procurementplanService.findRestockNeededProductIds());
            }

            for (Integer productId : requestedProductIds) {
                ProcurementPlanDetailCreateRequest detail = new ProcurementPlanDetailCreateRequest();
                detail.setProductId(productId);
                form.getDetails().add(detail);
            }
            model.addAttribute("procurementPlanForm", form);
        }

        addFormPageData(request, model);
        model.addAttribute("pageTitle", "Tạo dự trù mua hàng");
        return "procurement-plan/create-procurementplan";
    }

    /** Xử lý submit tạo dự trù — validate rồi redirect về danh sách. */
    @PostMapping("/owner/procurements/create-procurementplan")
    public String createProcurementPlan(@Valid @ModelAttribute("procurementPlanForm") ProcurementPlanCreateRequest form,
                                        BindingResult bindingResult,
                                        HttpServletRequest request,
                                        Model model,
                                        RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);

        if (bindingResult.hasErrors()) {
            model.addAttribute("validationMessages", bindingResult.getAllErrors().stream()
                    .map(this::resolveValidationMessage)
                    .toList());
            addFormPageData(request, model);
            model.addAttribute("pageTitle", "Tạo dự trù mua hàng");
            return "procurement-plan/create-procurementplan";
        }

        try {
            procurementplanService.create(form);
            redirectAttributes.addFlashAttribute("successMessage", "Tạo dự trù mua hàng thành công");
            return "redirect:" + basePath;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            addFormPageData(request, model);
            model.addAttribute("pageTitle", "Tạo dự trù mua hàng");
            return "procurement-plan/create-procurementplan";
        }
    }

    /**
     * Form xem/sửa dự trù — Accountant và phiếu đã hoàn thành chỉ xem ({@code viewOnly}).
     */
    @GetMapping({OWNER_BASE + "/update-procurementplan/{id}",
            ACCOUNTANT_BASE + "/update-procurementplan/{id}"})
    public String updateProcurementPlanForm(@PathVariable Integer id,
                                            HttpServletRequest request,
                                            Model model,
                                            RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            ProcurementplanResponse procurementPlan = procurementplanService.getById(id);
            boolean viewOnly = request.getRequestURI().startsWith(ACCOUNTANT_BASE)
                    || procurementplanService.isCompleted(id);

            if (!model.containsAttribute("procurementPlanForm")) {
                model.addAttribute("procurementPlanForm", procurementplanService.buildUpdateForm(id));
            }

            model.addAttribute("procurementPlan", procurementPlan);
            model.addAttribute("viewOnly", viewOnly);
            addFormPageData(request, model);
            model.addAttribute("pageTitle", viewOnly ? "Xem chi tiết dự trù mua hàng" : "Cập nhật dự trù mua hàng");
            return "procurement-plan/update-procurementplan";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:" + basePath;
        }
    }

    /** Xử lý submit cập nhật dự trù — chỉ Owner. */
    @PostMapping("/owner/procurements/update-procurementplan/{id}")
    public String updateProcurementPlan(@PathVariable Integer id,
                                        @Valid @ModelAttribute("procurementPlanForm") ProcurementPlanCreateRequest form,
                                        BindingResult bindingResult,
                                        HttpServletRequest request,
                                        Model model,
                                        RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);

        if (bindingResult.hasErrors()) {
            model.addAttribute("validationMessages", bindingResult.getAllErrors().stream()
                    .map(this::resolveValidationMessage)
                    .toList());
            model.addAttribute("procurementPlan", procurementplanService.getById(id));
            model.addAttribute("viewOnly", procurementplanService.isCompleted(id));
            addFormPageData(request, model);
            model.addAttribute("pageTitle", procurementplanService.isCompleted(id)
                    ? "Xem chi tiết dự trù mua hàng"
                    : "Cập nhật dự trù mua hàng");
            return "procurement-plan/update-procurementplan";
        }

        try {
            procurementplanService.update(id, form);
            String successMessage = "Cập nhật dự trù mua hàng thành công";
            if ("Đã hoàn thành".equals(form.getStatus())) {
                successMessage = "Cập nhật dự trù mua hàng thành công. Dự trù đã hoàn thành nên không thể cập nhật hoặc xóa.";
            }
            redirectAttributes.addFlashAttribute("successMessage", successMessage);
            return "redirect:" + basePath;
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().contains("đã hoàn thành")) {
                redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
                return "redirect:" + basePath;
            }
            model.addAttribute("errorMessage", exception.getMessage());
            model.addAttribute("procurementPlan", procurementplanService.getById(id));
            model.addAttribute("viewOnly", procurementplanService.isCompleted(id));
            addFormPageData(request, model);
            model.addAttribute("pageTitle", procurementplanService.isCompleted(id)
                    ? "Xem chi tiết dự trù mua hàng"
                    : "Cập nhật dự trù mua hàng");
            return "procurement-plan/update-procurementplan";
        }
    }

    /** Trang in phiếu dự trù — Owner và Accountant. */
    @GetMapping({OWNER_BASE + "/{id}/print", ACCOUNTANT_BASE + "/{id}/print"})
    public String printPage(@PathVariable Integer id,
                            HttpServletRequest request,
                            Model model) {
        ProcurementPlanPrintPageResponse printData = procurementplanService.getPrintPage(id);
        model.addAttribute("printData", printData);
        model.addAttribute("basePath", resolveBasePath(request));
        return "procurement-plan/procurement-plan-print";
    }

    /** Xóa phiếu dự trù — chỉ Owner, không xóa được phiếu đã hoàn thành. */
    @PostMapping("/owner/procurements/delete/{id}")
    public String deleteProcurementPlan(@PathVariable Integer id,
                                        RedirectAttributes redirectAttributes) {
        try {
            procurementplanService.delete(id);
            redirectAttributes.addFlashAttribute("successMessage", "Xóa dự trù mua hàng thành công");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:/owner/procurements";
    }

    /** Nạp sản phẩm, dòng chi tiết và dropdown nhà cung cấp cho form tạo/sửa. */
    private void addFormPageData(HttpServletRequest request, Model model) {
        ProcurementPlanCreateRequest form = (ProcurementPlanCreateRequest) model.getAttribute("procurementPlanForm");
        model.addAttribute("initialProducts", procurementplanService.listProductsForDetails(form));
        model.addAttribute("initialDetailRows", buildInitialDetailRows(form));
        model.addAttribute("suppliers", toSupplierOptions(procurementplanService.listSuppliers()));
        model.addAttribute("basePath", resolveBasePath(request));
    }

    /** Chuyển {@code procurementPlanForm.details} sang view hiển thị lại các dòng trên form. */
    private String resolveValidationMessage(ObjectError error) {
        if (error instanceof FieldError fieldError
                && fieldError.isBindingFailure()
                && fieldError.getField().matches("details\\[\\d+\\]\\.requestedQuantity")) {
            return "Số lượng dự trù phải là số nguyên.";
        }
        return error.getDefaultMessage();
    }


    /**
     * Chuẩn bị các dòng chi tiết ban đầu để template render lại form tạo/cập nhật dự trù.
     * Bỏ qua các dòng chưa có sản phẩm và trả về danh sách rỗng khi form chưa có chi tiết.
     */
    private List<ProcurementPlanDetailRowView> buildInitialDetailRows(ProcurementPlanCreateRequest form) {
        if (form == null || form.getDetails() == null) {
            return List.of();
        }

        return form.getDetails().stream()
                .filter(detail -> detail.getProductId() != null)
                .map(detail -> new ProcurementPlanDetailRowView(
                        detail.getProductId(),
                        detail.getRequestedQuantity(),
                        detail.getUnit(),
                        detail.getEstimatedPrice(),
                        detail.getSupplierId()
                ))
                .toList();
    }

    /** Map danh sách {@link Supplier} sang option cho dropdown/select. */
    private List<Map<String, Object>> toSupplierOptions(List<Supplier> suppliers) {
        return suppliers.stream()
                .map(supplier -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("id", supplier.getId());
                    option.put("name", supplier.getName() != null ? supplier.getName() : "");
                    return option;
                })
                .toList();
    }

    /** Base path theo role: Owner hoặc Accountant. */
    private String resolveBasePath(HttpServletRequest request) {
        return request.getRequestURI().startsWith(ACCOUNTANT_BASE) ? ACCOUNTANT_BASE : OWNER_BASE;
    }
}
