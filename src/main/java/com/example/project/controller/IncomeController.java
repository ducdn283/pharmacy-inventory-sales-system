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

/**
 * Màn quản lý phiếu thu (danh sách / chi tiết / tạo / hủy).
 * Chủ nhà thuốc, Dược sĩ và Kế toán: xem danh sách và chi tiết; tạo/hủy theo quyền nghiệp vụ.
 */
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

    /** Chuyển giá trị form ({@code String}) sang {@link BigDecimal} / {@link Integer} khi binding. */
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(BigDecimal.class, new CustomNumberEditor(BigDecimal.class, true));
        binder.registerCustomEditor(Integer.class, new CustomNumberEditor(Integer.class, true));
    }

    /** Danh sách phiếu thu có phân trang, lọc theo mã, ngày, loại, trạng thái, hình thức thu và người lập. */
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

    /** Trang chi tiết phiếu thu — kèm báo cáo ca nếu liên quan thất thoát quỹ. */
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

    /** Danh sách hóa đơn còn nợ của khách — chọn chứng từ khi thu nợ khách hàng. */
    @GetMapping(value = {OWNER_BASE + "/references/debt-invoices", PHARMACIST_BASE + "/references/debt-invoices",
            ACCOUNTANT_BASE + "/references/debt-invoices"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<IncomeReferenceOptionResponse> debtInvoices(@RequestParam(name = "customerId") Integer customerId) {
        return incomeService.listDebtInvoices(customerId);
    }

    /** Danh sách phiếu trả NCC còn tiền phải hoàn — chọn chứng từ khi thu nợ NCC. */
    @GetMapping(value = {OWNER_BASE + "/references/supplier-returns", PHARMACIST_BASE + "/references/supplier-returns",
            ACCOUNTANT_BASE + "/references/supplier-returns"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<IncomeReferenceOptionResponse> supplierReturns(@RequestParam(name = "supplierId") Integer supplierId) {
        return incomeService.listSupplierReturns(supplierId);
    }

    /** Danh sách phiếu điều chỉnh kho nhân viên phải đền bù — chọn chứng từ thu đền bù. */
    @GetMapping(value = {OWNER_BASE + "/references/stock-adjustments", PHARMACIST_BASE + "/references/stock-adjustments",
            ACCOUNTANT_BASE + "/references/stock-adjustments"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<IncomeReferenceOptionResponse> stockAdjustments(@RequestParam(name = "accountId") Integer accountId) {
        return incomeService.listStockAdjustments(accountId);
    }

    /** Danh sách báo cáo ca còn thiếu tiền mặt — chọn chứng từ thu thất thoát ca. */
    @GetMapping(value = {OWNER_BASE + "/references/shift-reports", PHARMACIST_BASE + "/references/shift-reports",
            ACCOUNTANT_BASE + "/references/shift-reports"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<IncomeReferenceOptionResponse> shiftReportsWithShortage(
            @RequestParam(name = "accountId") Integer accountId) {
        return incomeService.listShiftReportsWithShortage(accountId);
    }

    /** Chi tiết hóa đơn bán liên quan — xem trước trên form tạo phiếu thu. */
    @GetMapping(value = {OWNER_BASE + "/references/invoices/{id}/detail",
            PHARMACIST_BASE + "/references/invoices/{id}/detail",
            ACCOUNTANT_BASE + "/references/invoices/{id}/detail"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public InvoiceDetailPageResponse invoiceReferenceDetail(@PathVariable("id") Integer id) {
        return invoiceService.getDetail(id);
    }

    /** Chi tiết phiếu trả NCC kèm số tiền còn thu được — form tạo phiếu thu. */
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

    /** Payload JSON chi tiết phiếu trả NCC trên form tạo phiếu thu — gộp qua {@link JsonUnwrapped}. */
    private record SupplierReturnReferenceDetailPayload(
            @JsonUnwrapped ReturnPurchaseDetailPageResponse detail,
            /** Số tiền NCC còn phải hoàn sau các phiếu thu trước. */
            BigDecimal remainingCollectibleAmount) {
    }

    /** Chi tiết phiếu điều chỉnh kho liên quan — xem trước trên form tạo phiếu thu. */
    @GetMapping(value = {OWNER_BASE + "/references/stock-adjustments/{id}/detail",
            PHARMACIST_BASE + "/references/stock-adjustments/{id}/detail",
            ACCOUNTANT_BASE + "/references/stock-adjustments/{id}/detail"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public StockAdjustmentDetailPageResponse stockAdjustmentReferenceDetail(@PathVariable("id") Integer id) {
        return stockadjustmentService.getDetail(id);
    }

    /** Chi tiết báo cáo ca liên quan — xem trước trên form tạo phiếu thu. */
    @GetMapping(value = {OWNER_BASE + "/references/shift-reports/{id}/detail",
            PHARMACIST_BASE + "/references/shift-reports/{id}/detail",
            ACCOUNTANT_BASE + "/references/shift-reports/{id}/detail"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ShiftReportDetailPageResponse shiftReportReferenceDetail(@PathVariable("id") Integer id) {
        return shiftreportService.getDetail(id);
    }

    /**
     * Form tạo phiếu thu — giữ {@code form} nếu redirect sau lỗi validate.
     * Tham số query điền sẵn khi mở từ màn khác (thu nợ, thất thoát ca, …).
     */
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

    /**
     * Xử lý submit tạo phiếu thu.
     * {@code action = draft}: lưu nháp; ngược lại hoàn thành ngay (không cần duyệt).
     */
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

    /** Hủy phiếu thu — chỉ người lập, không hủy được phiếu bù trừ công nợ. */
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

    /** Nạp loại phiếu, đối tượng, chứng từ tham chiếu và cấu hình form tạo phiếu thu. */
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

    /** Đường dẫn gốc theo vai trò: Chủ nhà thuốc, Dược sĩ hoặc Kế toán. */
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

    /** Đường dẫn gốc hóa đơn bán — liên kết từ chi tiết phiếu thu. */
    private String resolveInvoiceBasePath(String basePath) {
        if (basePath.startsWith(PHARMACIST_BASE)) {
            return "/pharmacist/invoices";
        }
        if (basePath.startsWith(ACCOUNTANT_BASE)) {
            return "/accountant/invoices";
        }
        return "/owner/invoices";
    }

    /** Đường dẫn gốc phiếu điều chỉnh kho — liên kết từ chi tiết phiếu thu. */
    private String resolveStockAdjustmentBasePath(String basePath) {
        if (basePath.startsWith(PHARMACIST_BASE)) {
            return "/pharmacist/stock-adjustments";
        }
        return "/owner/stock-adjustments";
    }

    /** Đường dẫn gốc báo cáo ca — liên kết từ chi tiết phiếu thu. */
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
