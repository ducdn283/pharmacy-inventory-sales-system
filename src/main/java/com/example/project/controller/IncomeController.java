package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.IncomeCreateRequest;
import com.example.project.dto.response.IncomeListItemResponse;
import com.example.project.dto.response.IncomeReferenceOptionResponse;
import com.example.project.dto.response.InvoiceDetailPageResponse;
import com.example.project.dto.response.ReturnPurchaseDetailPageResponse;
import com.example.project.dto.response.StockAdjustmentDetailPageResponse;
import com.example.project.service.IncomeService;
import com.example.project.service.InvoiceService;
import com.example.project.service.ReturnPurchaseService;
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

    private final IncomeService incomeService;
    private final InvoiceService invoiceService;
    private final ReturnPurchaseService returnPurchaseService;
    private final StockadjustmentService stockadjustmentService;
    private final CurrentUserContext currentUserContext;

    public IncomeController(IncomeService incomeService,
                            InvoiceService invoiceService,
                            ReturnPurchaseService returnPurchaseService,
                            StockadjustmentService stockadjustmentService,
                            CurrentUserContext currentUserContext) {
        this.incomeService = incomeService;
        this.invoiceService = invoiceService;
        this.returnPurchaseService = returnPurchaseService;
        this.stockadjustmentService = stockadjustmentService;
        this.currentUserContext = currentUserContext;
    }

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(BigDecimal.class, new CustomNumberEditor(BigDecimal.class, true));
        binder.registerCustomEditor(Integer.class, new CustomNumberEditor(Integer.class, true));
    }

    @GetMapping({OWNER_BASE, PHARMACIST_BASE})
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
        model.addAttribute("pendingIncomes", incomeService.countPending());
        model.addAttribute("pendingAmount", incomeService.sumPendingAmount());
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
        return "income-list";
    }

    @GetMapping(value = {OWNER_BASE + "/references/debt-invoices", PHARMACIST_BASE + "/references/debt-invoices"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<IncomeReferenceOptionResponse> debtInvoices(@RequestParam(name = "customerId") Integer customerId) {
        return incomeService.listDebtInvoices(customerId);
    }

    @GetMapping(value = {OWNER_BASE + "/references/supplier-returns", PHARMACIST_BASE + "/references/supplier-returns"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<IncomeReferenceOptionResponse> supplierReturns(@RequestParam(name = "supplierId") Integer supplierId) {
        return incomeService.listSupplierReturns(supplierId);
    }

    @GetMapping(value = {OWNER_BASE + "/references/stock-adjustments", PHARMACIST_BASE + "/references/stock-adjustments"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<IncomeReferenceOptionResponse> stockAdjustments(@RequestParam(name = "accountId") Integer accountId) {
        return incomeService.listStockAdjustments(accountId);
    }

    @GetMapping(value = {OWNER_BASE + "/references/invoices/{id}/detail",
            PHARMACIST_BASE + "/references/invoices/{id}/detail"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public InvoiceDetailPageResponse invoiceReferenceDetail(@PathVariable("id") Integer id) {
        return invoiceService.getDetail(id);
    }

    @GetMapping(value = {OWNER_BASE + "/references/supplier-returns/{id}/detail",
            PHARMACIST_BASE + "/references/supplier-returns/{id}/detail"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ReturnPurchaseDetailPageResponse supplierReturnReferenceDetail(@PathVariable("id") Integer id) {
        return returnPurchaseService.getDetail(id);
    }

    @GetMapping(value = {OWNER_BASE + "/references/stock-adjustments/{id}/detail",
            PHARMACIST_BASE + "/references/stock-adjustments/{id}/detail"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public StockAdjustmentDetailPageResponse stockAdjustmentReferenceDetail(@PathVariable("id") Integer id) {
        return stockadjustmentService.getDetail(id);
    }

    @GetMapping({OWNER_BASE + "/create", PHARMACIST_BASE + "/create"})
    public String createPage(HttpServletRequest request, Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new IncomeCreateRequest());
        }
        addCreatePageData(request, model);
        return "create-income";
    }

    @PostMapping({OWNER_BASE + "/create", PHARMACIST_BASE + "/create"})
    public String create(@ModelAttribute("form") IncomeCreateRequest form,
                         @RequestParam(name = "action", required = false) String action,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes,
                         Model model) {
        String basePath = resolveBasePath(request);
        boolean asDraft = "draft".equals(action);
        try {
            incomeService.createIncome(
                    form,
                    currentUserContext.getCurrentAccountId(),
                    currentUserContext.isOwner(),
                    asDraft);

            String message;
            if (asDraft) {
                message = "Đã lưu nháp phiếu thu";
            } else if (currentUserContext.isOwner()) {
                message = "Tạo phiếu thu thành công";
            } else {
                message = "Đã gửi phiếu thu, đang chờ duyệt";
            }
            redirectAttributes.addFlashAttribute("success", message);
            return "redirect:" + basePath;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            model.addAttribute("form", form);
            addCreatePageData(request, model);
            return "create-income";
        }
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
                ? incomeService.listStockAdjustments(form.getAccountId()) : List.of());
        model.addAttribute("creatorName", currentUserContext.getCurrentAccountName());
        model.addAttribute("basePath", resolveBasePath(request));
        model.addAttribute("pageTitle", "Tạo phiếu thu");
    }

    private String resolveBasePath(HttpServletRequest request) {
        return request.getRequestURI().startsWith(PHARMACIST_BASE)
                ? PHARMACIST_BASE
                : OWNER_BASE;
    }
}
