package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.constant.ExpenseType;
import com.example.project.dto.request.ExpenseCreateRequest;
import com.example.project.dto.response.ExpenseDetailResponse;
import com.example.project.dto.response.ExpenseListItemResponse;
import com.example.project.service.ExpenseService;
import com.example.project.service.FinancialsettingService;
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

import java.math.BigDecimal;
import java.util.Map;

/**
 * Expense ("Phiếu chi") screens (list / detail / create / submit / approve / reject / confirm
 * payment / cancel). Owner and Accountant retain their full existing list scope. Pharmacists only
 * see their own slips and may create customer-return refund slips. Approve/reject/confirm-payment
 * are Owner-only, same as Stock Adjustment —
 * confirm-payment in particular is Owner-only even for a slip an Accountant raised or that was
 * approved on their behalf (BA 2026-08).
 */
@Controller
public class ExpensePageController {

    private static final String OWNER_BASE = "/owner/expenses";
    private static final String ACCOUNTANT_BASE = "/accountant/expenses";
    private static final String PHARMACIST_BASE = "/pharmacist/expenses";

    private final ExpenseService expenseService;
    private final CurrentUserContext currentUserContext;
    private final FinancialsettingService financialsettingService;

    public ExpensePageController(ExpenseService expenseService, CurrentUserContext currentUserContext,
                                 FinancialsettingService financialsettingService) {
        this.expenseService = expenseService;
        this.currentUserContext = currentUserContext;
        this.financialsettingService = financialsettingService;
    }

    @GetMapping({OWNER_BASE, ACCOUNTANT_BASE, PHARMACIST_BASE})
    public String list(@RequestParam(name = "keyword", required = false) String keyword,
                        @RequestParam(name = "fromDate", required = false) String fromDate,
                        @RequestParam(name = "toDate", required = false) String toDate,
                        @RequestParam(name = "expenseType", required = false) String expenseType,
                        @RequestParam(name = "status", required = false) String status,
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

        Integer applicantAccountId = currentUserContext.isPharmacist()
                ? currentUserContext.getCurrentAccountId()
                : null;
        Page<ExpenseListItemResponse> expensePage = expenseService.search(
                keyword, fromDate, toDate, expenseType, status, PageRequest.of(page, size), applicantAccountId);

        model.addAttribute("expensePage", expensePage);
        model.addAttribute("expenses", expensePage.getContent());
        model.addAttribute("stats", expenseService.getStats(applicantAccountId));

        model.addAttribute("statuses", expenseService.listStatuses());
        model.addAttribute("expenseTypeLabels", expenseService.expenseTypeLabels());

        model.addAttribute("keyword", keyword);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterExpenseType", expenseType);
        model.addAttribute("filterStatus", status);

        model.addAttribute("currentPage", expensePage.getNumber());
        model.addAttribute("totalPages", expensePage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", expensePage.getTotalElements());

        model.addAttribute("basePath", resolveBasePath(request));

        return "expense/list";
    }

    @GetMapping({OWNER_BASE + "/create", ACCOUNTANT_BASE + "/create", PHARMACIST_BASE + "/create"})
    public String createPage(@RequestParam(name = "expenseType", required = false) String expenseType,
                             @RequestParam(name = "returnId", required = false) Integer returnId,
                             @RequestParam(name = "purchaseId", required = false) Integer purchaseId,
                             HttpServletRequest request,
                             Model model) {
        if (!model.containsAttribute("form")) {
            ExpenseCreateRequest form = new ExpenseCreateRequest();
            if (currentUserContext.isPharmacist()) {
                form.setExpenseType(ExpenseType.RETURN_REFUND_PAYOUT);
            } else if (expenseType != null && !expenseType.isBlank()) {
                form.setExpenseType(expenseType.trim());
            }
            form.setReturnId(returnId);
            form.setPurchaseId(purchaseId);
            model.addAttribute("form", form);
        }
        addCreateFormOptions(model, resolveBasePath(request));

        return "expense/create";
    }

    @PostMapping({OWNER_BASE + "/create", ACCOUNTANT_BASE + "/create", PHARMACIST_BASE + "/create"})
    public String create(@ModelAttribute("form") ExpenseCreateRequest form,
                          @RequestParam(name = "action", required = false) String action,
                          HttpServletRequest request,
                          RedirectAttributes redirectAttributes,
                          Model model) {
        String basePath = resolveBasePath(request);
        boolean isOwner = currentUserContext.isOwner();
        boolean asDraft = "draft".equals(action);
        try {
            Integer expenseId = expenseService.createExpense(
                    form,
                    currentUserContext.getCurrentAccountId(),
                    isOwner,
                    asDraft,
                    isOwner || currentUserContext.isPharmacist(),
                    currentUserContext.isPharmacist() ? ExpenseType.RETURN_REFUND_PAYOUT : null);

            String message;
            if (asDraft) {
                message = "Đã lưu nháp phiếu chi";
            } else if (isOwner) {
                message = "Tạo phiếu chi thành công (đã tự động duyệt, đang chờ thanh toán)";
            } else {
                message = "Đã gửi phiếu chi, đang chờ duyệt";
            }
            redirectAttributes.addFlashAttribute("successMessage", message);
            return "redirect:" + basePath + "/" + expenseId;
        } catch (IllegalArgumentException exception) {
            if (currentUserContext.isPharmacist()) {
                form.setExpenseType(ExpenseType.RETURN_REFUND_PAYOUT);
                form.setPurchaseId(null);
            }
            model.addAttribute("errorMessage", exception.getMessage());
            model.addAttribute("form", form);
            addCreateFormOptions(model, basePath);
            return "expense/create";
        }
    }

    /**
     * Everything the create form needs besides the form object itself. Shared by the GET and the
     * validation-failure re-render so the customer-return picker survives a rejected submit.
     */
    private void addCreateFormOptions(Model model, String basePath) {
        model.addAttribute("expenseTypeLabels", currentUserContext.isPharmacist()
                ? Map.of(ExpenseType.RETURN_REFUND_PAYOUT,
                        ExpenseType.vietnameseName(ExpenseType.RETURN_REFUND_PAYOUT))
                : expenseService.expenseTypeLabels());
        model.addAttribute("customerReturns", expenseService.listCustomerReturns());
        model.addAttribute("customerReturnAmounts", expenseService.customerReturnAmounts());
        model.addAttribute("purchaseInvoices", expenseService.listPayablePurchaseInvoices());
        model.addAttribute("purchaseInvoiceAmounts", expenseService.payablePurchaseInvoiceAmounts());
        model.addAttribute("purchaseLinkableTypes", expenseService.purchaseLinkableTypes());
        model.addAttribute("creatorName", currentUserContext.getCurrentAccountName());
        // Only the Owner pays out of the drawer; the Accountant settles by transfer and has no shift
        // to reconcile cash against. Enforced server-side in ExpenseService.resolveSplit — this is
        // just so the form does not offer a field the server will reject.
        model.addAttribute("canPayCash", currentUserContext.isOwner() || currentUserContext.isPharmacist());
        model.addAttribute("basePath", basePath);

        // Số dư quỹ hiện tại — chỉ để cảnh báo phía client TRƯỚC khi tạo phiếu nếu quỹ - số tiền chi
        // sẽ âm (xem create.html). Chưa chắc chắn 100% tại thời điểm tạo vì tiền chỉ thực sự rời quỹ
        // ở confirmPayment(), nhưng vẫn là con số tốt nhất hiện có để cảnh báo sớm cho người lập.
        var settings = financialsettingService.getSettings();
        model.addAttribute("cashSafeBalance", settings.getCashSafeBalance());
        model.addAttribute("bankAccountBalance", settings.getBankAccountBalance());
    }

    @GetMapping({OWNER_BASE + "/{expenseId}", ACCOUNTANT_BASE + "/{expenseId}", PHARMACIST_BASE + "/{expenseId}"})
    public String detail(@PathVariable Integer expenseId, HttpServletRequest request, Model model,
                         RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            ExpenseDetailResponse detail = expenseService.getDetail(
                    expenseId,
                    currentUserContext.isPharmacist() ? currentUserContext.getCurrentAccountId() : null);
            model.addAttribute("detail", detail);
            model.addAttribute("basePath", basePath);
            return "expense/detail";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:" + basePath;
        }
    }

    @PostMapping({OWNER_BASE + "/{expenseId}/submit", ACCOUNTANT_BASE + "/{expenseId}/submit",
            PHARMACIST_BASE + "/{expenseId}/submit"})
    public String submit(@PathVariable Integer expenseId,
                          HttpServletRequest request,
                          RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            expenseService.submit(expenseId, currentUserContext.getCurrentAccountId(),
                    currentUserContext.isOwner(), currentUserContext.isPharmacist());
            redirectAttributes.addFlashAttribute("successMessage",
                    currentUserContext.isOwner() ? "Đã duyệt phiếu chi" : "Đã gửi phiếu chi, đang chờ duyệt");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + basePath + "/" + expenseId;
    }

    // redirectTo: optional override used by the unified Approve List (/owner/approvals) so its
    // Duyệt/Từ chối buttons land back on that screen; absent → original behaviour (expense detail).
    @PostMapping(OWNER_BASE + "/{expenseId}/approve")
    public String approve(@PathVariable Integer expenseId,
                          @RequestParam(name = "redirectTo", required = false) String redirectTo,
                          RedirectAttributes redirectAttributes) {
        try {
            expenseService.approve(expenseId, currentUserContext.getCurrentAccountId());
            redirectAttributes.addFlashAttribute("successMessage", "Đã duyệt phiếu chi, đang chờ thanh toán");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + (redirectTo != null && !redirectTo.isBlank() ? redirectTo : OWNER_BASE + "/" + expenseId);
    }

    @PostMapping(OWNER_BASE + "/{expenseId}/reject")
    public String reject(@PathVariable Integer expenseId,
                         @RequestParam(name = "redirectTo", required = false) String redirectTo,
                         RedirectAttributes redirectAttributes) {
        try {
            expenseService.reject(expenseId, currentUserContext.getCurrentAccountId());
            redirectAttributes.addFlashAttribute("successMessage", "Đã từ chối phiếu chi");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + (redirectTo != null && !redirectTo.isBlank() ? redirectTo : OWNER_BASE + "/" + expenseId);
    }

    /**
     * The Owner confirms an {@link com.example.project.constant.ExpenseStatus#AWAITING_PAYMENT}
     * slip's money actually left — Owner-only (mapped under {@code /owner/**} only, matching
     * approve/reject), even for a slip an Accountant raised or that an Owner approved on their
     * behalf. This is NOT the old, deleted "mark-paid" (that let a slip under-pay itself and be
     * topped up later) — a phiếu chi is still one payment for its whole posted amount; this only
     * confirms that amount really left.
     */
    @PostMapping(OWNER_BASE + "/{expenseId}/confirm-payment")
    public String confirmPayment(@PathVariable Integer expenseId,
                                 RedirectAttributes redirectAttributes) {
        try {
            expenseService.confirmPayment(expenseId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã xác nhận thanh toán, phiếu chi hoàn thành");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + OWNER_BASE + "/" + expenseId;
    }

    @PostMapping({OWNER_BASE + "/{expenseId}/cancel", ACCOUNTANT_BASE + "/{expenseId}/cancel",
            PHARMACIST_BASE + "/{expenseId}/cancel"})
    public String cancel(@PathVariable Integer expenseId,
                          @RequestParam(name = "reason", required = false) String reason,
                          HttpServletRequest request,
                          RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            expenseService.cancel(expenseId, reason,
                    currentUserContext.isPharmacist() ? currentUserContext.getCurrentAccountId() : null);
            redirectAttributes.addFlashAttribute("successMessage", "Đã hủy phiếu chi");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + basePath + "/" + expenseId;
    }

    private String resolveBasePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith(PHARMACIST_BASE)) {
            return PHARMACIST_BASE;
        }
        return uri.startsWith(ACCOUNTANT_BASE) ? ACCOUNTANT_BASE : OWNER_BASE;
    }
}
