package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.constant.ExpenseStatus;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Màn Phiếu chi (danh sách / chi tiết / tạo / gửi duyệt / duyệt / từ chối / xác nhận thanh toán /
 * hủy). Owner và Accountant xem toàn bộ danh sách; Dược sĩ chỉ thấy phiếu của mình và chỉ tạo được
 * phiếu hoàn tiền trả hàng dưới {@link ExpenseType#PHARMACIST_REFUND_LIMIT} — phiếu này luôn dưới
 * {@link ExpenseType#PHARMACIST_AUTO_APPROVE_LIMIT} nên không bao giờ rơi vào {@code PENDING}.
 * Duyệt/từ chối chỉ Chủ nhà thuốc. Xác nhận thanh toán mặc định cũng chỉ Chủ nhà thuốc (kể cả với
 * phiếu do Kế toán lập/được duyệt hộ) — riêng Dược sĩ được tự xác nhận thanh toán cho phiếu của
 * chính mình nếu vẫn dưới ngưỡng tự động duyệt (route mở dưới {@code /pharmacist/**} nữa,
 * {@code ExpenseService} tự kiểm lại cả quyền sở hữu lẫn số tiền).
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

    // Danh sách phiếu chi có lọc/phân trang; Dược sĩ chỉ thấy phiếu do chính mình lập.
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

    // Hiển thị form tạo phiếu chi mới, tự điền loại phiếu mặc định theo vai trò/nguồn gốc điều hướng.
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

    // Xử lý submit form tạo phiếu chi (lưu nháp hoặc gửi/tự duyệt), báo thông báo phù hợp theo kết quả.
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
                    currentUserContext.isPharmacist(),
                    currentUserContext.isPharmacist() ? ExpenseType.RETURN_REFUND_PAYOUT : null);

            String message;
            if (asDraft) {
                message = "Đã lưu nháp phiếu chi";
            } else if (ExpenseStatus.AWAITING_PAYMENT.equals(expenseService.getDetail(expenseId).getStatusName())) {
                // Owner tự duyệt, Dược sĩ dưới ngưỡng PHARMACIST_AUTO_APPROVE_LIMIT cũng vậy — cả
                // hai đều nhảy thẳng qua Chờ duyệt.
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
     * Mọi dữ liệu form tạo phiếu cần ngoài chính đối tượng form — dùng chung cho GET và cho lần
     * render lại khi validate lỗi, để danh sách phiếu trả hàng không bị mất khi submit thất bại.
     */
    private void addCreateFormOptions(Model model, String basePath) {
        boolean pharmacist = currentUserContext.isPharmacist();
        model.addAttribute("expenseTypeLabels", currentUserContext.isPharmacist()
                ? Map.of(ExpenseType.RETURN_REFUND_PAYOUT,
                        ExpenseType.vietnameseName(ExpenseType.RETURN_REFUND_PAYOUT))
                : expenseService.expenseTypeLabels());
        var customerReturns = expenseService.listCustomerReturns();
        if (pharmacist) {
            customerReturns = customerReturns.stream()
                    .filter(item -> item.getAmount() != null
                            && item.getAmount().compareTo(ExpenseType.PHARMACIST_REFUND_LIMIT) < 0)
                    .toList();
        }
        Map<Integer, BigDecimal> customerReturnAmounts = new LinkedHashMap<>();
        customerReturns.forEach(item -> customerReturnAmounts.put(item.getId(), item.getAmount()));
        model.addAttribute("customerReturns", customerReturns);
        model.addAttribute("customerReturnAmounts", customerReturnAmounts);
        model.addAttribute("pharmacistRefundLimit",
                pharmacist ? ExpenseType.PHARMACIST_REFUND_LIMIT : null);
        model.addAttribute("purchaseInvoices", expenseService.listPayablePurchaseInvoices());
        model.addAttribute("purchaseInvoiceAmounts", expenseService.payablePurchaseInvoiceAmounts());
        model.addAttribute("purchaseLinkableTypes", expenseService.purchaseLinkableTypes());
        model.addAttribute("creatorName", currentUserContext.getCurrentAccountName());
        // Tiền mặt: Owner/Dược sĩ. Chuyển khoản: Owner/Kế toán — Dược sĩ ca chỉ mở bằng quỹ tiền
        // mặt, Kế toán không giữ quỹ tiền mặt. Server kiểm lại ở ExpenseService.resolveSplit, đây
        // chỉ để form không hiện ô mà server sẽ từ chối.
        model.addAttribute("canPayCash", currentUserContext.isOwner() || currentUserContext.isPharmacist());
        model.addAttribute("canPayBanking", !currentUserContext.isPharmacist());
        model.addAttribute("basePath", basePath);

        // Số dư quỹ hiện tại — chỉ để cảnh báo phía client TRƯỚC khi tạo phiếu nếu quỹ - số tiền chi
        // sẽ âm (xem create.html). Chưa chắc chắn 100% tại thời điểm tạo vì tiền chỉ thực sự rời quỹ
        // ở confirmPayment(), nhưng vẫn là con số tốt nhất hiện có để cảnh báo sớm cho người lập.
        var settings = financialsettingService.getSettings();
        model.addAttribute("cashSafeBalance", settings.getCashSafeBalance());
        model.addAttribute("bankAccountBalance", settings.getBankAccountBalance());
    }

    // Trang chi tiết một phiếu chi, kèm cờ cho biết người xem hiện tại có được hủy phiếu hay không.
    @GetMapping({OWNER_BASE + "/{expenseId}", ACCOUNTANT_BASE + "/{expenseId}", PHARMACIST_BASE + "/{expenseId}"})
    public String detail(@PathVariable Integer expenseId, HttpServletRequest request, Model model,
                         RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            ExpenseDetailResponse detail = expenseService.getDetail(
                    expenseId,
                    currentUserContext.isPharmacist() ? currentUserContext.getCurrentAccountId() : null);
            model.addAttribute("detail", detail);
            model.addAttribute("canCancel",
                    expenseService.canCancel(expenseId, currentUserContext.getCurrentAccountId()));
            model.addAttribute("basePath", basePath);
            return "expense/detail";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:" + basePath;
        }
    }

    @PostMapping({OWNER_BASE + "/{expenseId}/submit", ACCOUNTANT_BASE + "/{expenseId}/submit",
            PHARMACIST_BASE + "/{expenseId}/submit"})
    // Gửi phiếu nháp đi duyệt (hoặc tự duyệt luôn nếu người gửi là Chủ nhà thuốc).
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

    // redirectTo: cho phép màn Duyệt tổng hợp (/owner/approvals) điều hướng quay lại chính nó sau
    // khi Duyệt/Từ chối; bỏ trống thì quay về trang chi tiết phiếu chi như mặc định.
    @PostMapping(OWNER_BASE + "/{expenseId}/approve")
    // Chủ nhà thuốc duyệt phiếu đang Chờ duyệt -> chuyển sang Chờ thanh toán.
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
    // Chủ nhà thuốc từ chối phiếu đang Chờ duyệt -> quay về Nháp.
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
     * Xác nhận tiền của một phiếu {@link com.example.project.constant.ExpenseStatus#AWAITING_PAYMENT}
     * đã thực sự rời quỹ. Mặc định chỉ Chủ nhà thuốc (route dưới {@code /owner/**}, giống duyệt/từ
     * chối), kể cả với phiếu Kế toán lập hoặc được duyệt hộ — trừ trường hợp Dược sĩ tự xác nhận
     * phiếu của chính mình khi còn dưới {@code ExpenseType.PHARMACIST_AUTO_APPROVE_LIMIT} (route
     * mở thêm dưới {@code /pharmacist/**}; {@code ExpenseService} vẫn tự kiểm lại quyền sở hữu và
     * số tiền, route mapping không phải chốt chặn duy nhất). Không phải cơ chế "chi thiếu rồi trả
     * góp" — một phiếu chi vẫn là một lần chi trọn số tiền đã ghi, hàm này chỉ xác nhận số đó đã
     * thực sự rời quỹ.
     */
    @PostMapping({OWNER_BASE + "/{expenseId}/confirm-payment", PHARMACIST_BASE + "/{expenseId}/confirm-payment"})
    public String confirmPayment(@PathVariable Integer expenseId,
                                 HttpServletRequest request,
                                 RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            if (currentUserContext.isPharmacist()) {
                expenseService.confirmPayment(expenseId, currentUserContext.getCurrentAccountId());
            } else {
                expenseService.confirmPayment(expenseId);
            }
            redirectAttributes.addFlashAttribute("successMessage", "Đã xác nhận thanh toán, phiếu chi hoàn thành");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + basePath + "/" + expenseId;
    }

    @PostMapping({OWNER_BASE + "/{expenseId}/cancel", ACCOUNTANT_BASE + "/{expenseId}/cancel",
            PHARMACIST_BASE + "/{expenseId}/cancel"})
    // Hủy phiếu chi (chỉ người tạo, và chỉ khi phiếu chưa hoàn thành thật).
    public String cancel(@PathVariable Integer expenseId,
                          @RequestParam(name = "reason", required = false) String reason,
                          HttpServletRequest request,
                          RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            expenseService.cancel(expenseId, reason, currentUserContext.getCurrentAccountId());
            redirectAttributes.addFlashAttribute("successMessage", "Đã hủy phiếu chi");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + basePath + "/" + expenseId;
    }

    // Suy ra tiền tố URL theo vai trò (/owner, /accountant, /pharmacist) từ request hiện tại.
    private String resolveBasePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith(PHARMACIST_BASE)) {
            return PHARMACIST_BASE;
        }
        return uri.startsWith(ACCOUNTANT_BASE) ? ACCOUNTANT_BASE : OWNER_BASE;
    }
}
