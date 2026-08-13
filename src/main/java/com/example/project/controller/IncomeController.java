package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.IncomeCreateRequest;
import com.example.project.dto.response.IncomeDetailResponse;
import com.example.project.dto.response.IncomeListItemResponse;
import com.example.project.dto.response.IncomeReferenceOptionResponse;
import com.example.project.dto.response.IncomeTypeOptionResponse;
import com.example.project.dto.response.InvoiceDetailPageResponse;
import com.example.project.dto.response.ReturnPurchaseDetailPageResponse;
import com.example.project.dto.response.ShiftReportDetailPageResponse;
import com.example.project.dto.response.StockAdjustmentDetailPageResponse;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.example.project.service.IncomeService;
import com.example.project.service.InvoiceService;
import com.example.project.service.ReturnPurchaseService;
import com.example.project.service.ShiftreportService;
import com.example.project.service.StockadjustmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.propertyeditors.CustomNumberEditor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
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
import java.util.List;

@Controller
public class IncomeController {

    private static final String OWNER_BASE = "/owner/incomes";
    private static final String PHARMACIST_BASE = "/pharmacist/incomes";
    private static final String ACCOUNTANT_BASE = "/accountant/incomes";

    private final IncomeService incomeService;
    private final InvoiceService invoiceService;
    private final ReturnPurchaseService returnPurchaseService;
    private final StockadjustmentService stockadjustmentService;
    private final ShiftreportService shiftreportService;
    private final CurrentUserContext currentUserContext;

    public IncomeController(IncomeService incomeService,
                            InvoiceService invoiceService,
                            ReturnPurchaseService returnPurchaseService,
                            StockadjustmentService stockadjustmentService,
                            ShiftreportService shiftreportService,
                            CurrentUserContext currentUserContext) {
        this.incomeService = incomeService;
        this.invoiceService = invoiceService;
        this.returnPurchaseService = returnPurchaseService;
        this.stockadjustmentService = stockadjustmentService;
        this.shiftreportService = shiftreportService;
        this.currentUserContext = currentUserContext;
    }

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(BigDecimal.class, new CustomNumberEditor(BigDecimal.class, true));
        binder.registerCustomEditor(Integer.class, new CustomNumberEditor(Integer.class, true));
    }

    @GetMapping({OWNER_BASE, PHARMACIST_BASE, ACCOUNTANT_BASE})
    public String incomeList(@RequestParam(name = "search", required = false) String search,
                             @RequestParam(name = "fromDate", required = false) String fromDate,
                             @RequestParam(name = "toDate", required = false) String toDate,
                             @RequestParam(name = "incomeType", required = false) String incomeType,
                             @RequestParam(name = "status", required = false) String status,
                             @RequestParam(name = "paymentType", required = false) String paymentType,
                             @RequestParam(name = "applicantId", required = false) Integer applicantId,
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

        Page<IncomeListItemResponse> incomePage = incomeService.list(
                search, fromDate, toDate, incomeType, status, paymentType, applicantId, PageRequest.of(page, size));
        String basePath = resolveBasePath(request);

        model.addAttribute("incomes", incomePage.getContent());
        model.addAttribute("totalIncomes", incomeService.countAll());
        model.addAttribute("todayIncomes", incomeService.countToday());
        model.addAttribute("todayAmount", incomeService.sumTodayAmount());
        model.addAttribute("approvedIncomes", incomeService.countApproved());
        model.addAttribute("approvedAmount", incomeService.sumApprovedAmount());
        model.addAttribute("statuses", incomeService.listStatuses());
        model.addAttribute("applicants", incomeService.listApplicants());
        model.addAttribute("incomeTypes", incomeService.listIncomeTypes());
        model.addAttribute("paymentTypeLabels", incomeService.paymentTypeLabels());
        model.addAttribute("search", search);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterIncomeType", incomeType);
        model.addAttribute("filterStatus", status);
        model.addAttribute("filterPaymentType", paymentType);
        model.addAttribute("filterApplicantId", applicantId);
        model.addAttribute("currentPage", incomePage.getNumber());
        model.addAttribute("totalPages", incomePage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", incomePage.getTotalElements());
        model.addAttribute("pageTitle", "Danh sách khoản thu");
        model.addAttribute("basePath", basePath);
        return "income/income-list";
    }

    @GetMapping({OWNER_BASE + "/{incomeId}", PHARMACIST_BASE + "/{incomeId}", ACCOUNTANT_BASE + "/{incomeId}"})
    public String detail(@PathVariable Integer incomeId, HttpServletRequest request, Model model) {
        IncomeDetailResponse detail = incomeService.getDetail(
                incomeId, currentUserContext.getCurrentAccountId());
        String basePath = resolveBasePath(request);

        model.addAttribute("detail", detail);
        model.addAttribute("basePath", basePath);
        if (detail.getShiftReportOfAccountId() != null) {
            model.addAttribute("shiftReportDetail",
                    shiftreportService.getDetail(detail.getShiftReportOfAccountId()));
        }
        model.addAttribute("invoiceBasePath", resolveInvoiceBasePath(basePath));
        model.addAttribute("returnBasePath", "/owner/return-purchases");
        model.addAttribute("stockAdjustmentBasePath", resolveStockAdjustmentBasePath(basePath));
        model.addAttribute("shiftReportBasePath", resolveShiftReportBasePath(basePath));
        model.addAttribute("pageTitle", "Chi tiết phiếu thu");
        return "income/income-detail";
    }

    @GetMapping(value = {OWNER_BASE + "/references/debt-invoices", PHARMACIST_BASE + "/references/debt-invoices",
            ACCOUNTANT_BASE + "/references/debt-invoices"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<IncomeReferenceOptionResponse> debtInvoices(@RequestParam(name = "customerId") Integer customerId) {
        return incomeService.listDebtInvoices(customerId);
    }

    @GetMapping(value = {OWNER_BASE + "/references/supplier-returns", PHARMACIST_BASE + "/references/supplier-returns",
            ACCOUNTANT_BASE + "/references/supplier-returns"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<IncomeReferenceOptionResponse> supplierReturns(@RequestParam(name = "supplierId") Integer supplierId) {
        return incomeService.listSupplierReturns(supplierId);
    }

    @GetMapping(value = {OWNER_BASE + "/references/stock-adjustments", PHARMACIST_BASE + "/references/stock-adjustments",
            ACCOUNTANT_BASE + "/references/stock-adjustments"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<IncomeReferenceOptionResponse> stockAdjustments(@RequestParam(name = "accountId") Integer accountId) {
        return incomeService.listStockAdjustments(accountId);
    }

    @GetMapping(value = {OWNER_BASE + "/references/shift-reports", PHARMACIST_BASE + "/references/shift-reports",
            ACCOUNTANT_BASE + "/references/shift-reports"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<IncomeReferenceOptionResponse> shiftReportsWithShortage(
            @RequestParam(name = "accountId") Integer accountId) {
        return incomeService.listShiftReportsWithShortage(accountId);
    }

    @GetMapping(value = {OWNER_BASE + "/references/invoices/{id}/detail",
            PHARMACIST_BASE + "/references/invoices/{id}/detail",
            ACCOUNTANT_BASE + "/references/invoices/{id}/detail"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public InvoiceDetailPageResponse invoiceReferenceDetail(@PathVariable("id") Integer id) {
        return invoiceService.getDetail(id);
    }

    @GetMapping(value = {OWNER_BASE + "/references/supplier-returns/{id}/detail",
            PHARMACIST_BASE + "/references/supplier-returns/{id}/detail",
            ACCOUNTANT_BASE + "/references/supplier-returns/{id}/detail"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public SupplierReturnReferenceDetailPayload supplierReturnReferenceDetail(@PathVariable("id") Integer id) {
        return new SupplierReturnReferenceDetailPayload(
                returnPurchaseService.getDetail(id),
                incomeService.remainingCollectibleForSupplierReturn(id));
    }

    /** JSON payload for supplier-return detail on the income create screen (flattened via {@link JsonUnwrapped}). */
    private record SupplierReturnReferenceDetailPayload(
            @JsonUnwrapped ReturnPurchaseDetailPageResponse detail,
            BigDecimal remainingCollectibleAmount) {
    }

    @GetMapping(value = {OWNER_BASE + "/references/stock-adjustments/{id}/detail",
            PHARMACIST_BASE + "/references/stock-adjustments/{id}/detail",
            ACCOUNTANT_BASE + "/references/stock-adjustments/{id}/detail"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public StockAdjustmentDetailPageResponse stockAdjustmentReferenceDetail(@PathVariable("id") Integer id) {
        return stockadjustmentService.getDetail(id);
    }

    @GetMapping(value = {OWNER_BASE + "/references/shift-reports/{id}/detail",
            PHARMACIST_BASE + "/references/shift-reports/{id}/detail",
            ACCOUNTANT_BASE + "/references/shift-reports/{id}/detail"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ShiftReportDetailPageResponse shiftReportReferenceDetail(@PathVariable("id") Integer id) {
        return shiftreportService.getDetail(id);
    }

    @GetMapping({OWNER_BASE + "/create", PHARMACIST_BASE + "/create", ACCOUNTANT_BASE + "/create"})
    public String createPage(@RequestParam(name = "incomeType", required = false) String incomeType,
                             @RequestParam(name = "customerId", required = false) Integer customerId,
                             @RequestParam(name = "invoiceId", required = false) Integer invoiceId,
                             @RequestParam(name = "supplierId", required = false) Integer supplierId,
                             @RequestParam(name = "returnId", required = false) Integer returnId,
                             @RequestParam(name = "accountId", required = false) Integer accountId,
                             @RequestParam(name = "shiftReportOfAccountId", required = false)
                             Integer shiftReportOfAccountId,
                             HttpServletRequest request,
                             Model model) {
        if (!model.containsAttribute("form")) {
            IncomeCreateRequest form = new IncomeCreateRequest();
            if (incomeType != null && !incomeType.isBlank()) {
                form.setIncomeType(IncomeTypeOptionResponse.codeOf(incomeType));
            }
            form.setCustomerId(customerId);
            form.setInvoiceId(invoiceId);
            form.setSupplierId(supplierId);
            form.setReturnId(returnId);
            // Điền sẵn khi mở từ màn báo cáo ca bị thâm hụt quỹ (loại SHIFT_SHORTAGE): người chịu
            // trách nhiệm = người trực ca, chứng từ liên quan = chính ca đó.
            form.setAccountId(accountId);
            form.setShiftReportOfAccountId(shiftReportOfAccountId);
            model.addAttribute("form", form);
        }
        addCreatePageData(request, model);
        return "income/create-income";
    }

    @PostMapping({OWNER_BASE + "/create", PHARMACIST_BASE + "/create", ACCOUNTANT_BASE + "/create"})
    public String create(@ModelAttribute("form") IncomeCreateRequest form,
                         @RequestParam(name = "action", required = false) String action,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes,
                         Model model) {
        String basePath = resolveBasePath(request);
        boolean asDraft = "draft".equals(action);
        try {
            Integer incomeId = incomeService.createIncome(
                    form,
                    currentUserContext.getCurrentAccountId(),
                    asDraft);

            String message = asDraft ? "Đã lưu nháp phiếu thu" : "Tạo phiếu thu thành công";
            redirectAttributes.addFlashAttribute("successMessage", message);
            return "redirect:" + basePath + "/" + incomeId;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            model.addAttribute("form", form);
            addCreatePageData(request, model);
            return "income/create-income";
        }
    }

    @PostMapping({OWNER_BASE + "/{incomeId}/cancel", PHARMACIST_BASE + "/{incomeId}/cancel",
            ACCOUNTANT_BASE + "/{incomeId}/cancel"})
    public String cancel(@PathVariable Integer incomeId,
                         @RequestParam(name = "reason", required = false) String reason,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            incomeService.cancel(incomeId, reason, currentUserContext.getCurrentAccountId());
            redirectAttributes.addFlashAttribute("successMessage", "Đã hủy phiếu thu");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + basePath + "/" + incomeId;
    }

    private void addCreatePageData(HttpServletRequest request, Model model) {
        IncomeCreateRequest form = (IncomeCreateRequest) model.getAttribute("form");
        model.addAttribute("incomeTypes", incomeService.listIncomeTypes());
        model.addAttribute("suppliers", incomeService.listSuppliers());
        model.addAttribute("customers", incomeService.listCustomers());
        model.addAttribute("employees", incomeService.listEmployees());
        model.addAttribute("debtInvoices", form != null && form.getCustomerId() != null
                ? incomeService.listDebtInvoices(form.getCustomerId()) : List.of());
        model.addAttribute("supplierReturns", form != null && form.getSupplierId() != null
                ? incomeService.listSupplierReturns(form.getSupplierId()) : List.of());
        model.addAttribute("stockAdjustments", form != null && form.getAccountId() != null
                && IncomeTypeOptionResponse.EMPLOYEE.equals(
                        form.getIncomeType() != null ? IncomeTypeOptionResponse.codeOf(form.getIncomeType()) : "")
                ? incomeService.listStockAdjustments(form.getAccountId()) : List.of());
        model.addAttribute("shiftReportsWithShortage", form != null && form.getAccountId() != null
                && IncomeTypeOptionResponse.SHIFT_SHORTAGE.equals(
                        form.getIncomeType() != null ? IncomeTypeOptionResponse.codeOf(form.getIncomeType()) : "")
                ? incomeService.listShiftReportsWithShortage(form.getAccountId()) : List.of());
        model.addAttribute("creatorName", currentUserContext.getCurrentAccountName());
        model.addAttribute("basePath", resolveBasePath(request));
        model.addAttribute("pageTitle", "Tạo phiếu thu");
    }

    private String resolveBasePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith(PHARMACIST_BASE)) {
            return PHARMACIST_BASE;
        }
        if (uri.startsWith(ACCOUNTANT_BASE)) {
            return ACCOUNTANT_BASE;
        }
        return OWNER_BASE;
    }

    private String resolveInvoiceBasePath(String basePath) {
        if (basePath.startsWith(PHARMACIST_BASE)) {
            return "/pharmacist/invoices";
        }
        if (basePath.startsWith(ACCOUNTANT_BASE)) {
            return "/accountant/invoices";
        }
        return "/owner/invoices";
    }

    private String resolveStockAdjustmentBasePath(String basePath) {
        if (basePath.startsWith(PHARMACIST_BASE)) {
            return "/pharmacist/stock-adjustments";
        }
        return "/owner/stock-adjustments";
    }

    private String resolveShiftReportBasePath(String basePath) {
        if (basePath.startsWith(PHARMACIST_BASE)) {
            return "/pharmacist/shift-reports";
        }
        if (basePath.startsWith(ACCOUNTANT_BASE)) {
            return "/accountant/shift-reports";
        }
        return "/owner/shift-reports";
    }
}
