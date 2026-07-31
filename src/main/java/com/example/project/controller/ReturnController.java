package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.ReturnCreateRequest;
import com.example.project.dto.response.ReturnInvoiceLineResponse;
import com.example.project.dto.response.ReturnListItemResponse;
import com.example.project.service.ReturnService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Customer-return screens (list / detail / create / approve / reject).
 *
 * <p>Reachable by the Owner (approver), the Pharmacist (creator), and — read-only — the Accountant.
 * All three share the same templates; the active base path (<code>/owner/returns</code>,
 * <code>/pharmacist/returns</code> or <code>/accountant/returns</code>) is resolved per request so
 * links stay within the caller's role prefix. Create/submit/approve/reject are never mapped under
 * {@code ACCOUNTANT_BASE} — the create/detail templates additionally hide those actions behind
 * {@code currentRole != 'ACCOUNTANT'} so the Accountant only ever sees a view link. Approve/reject
 * remain Owner-only (enforced by SecurityConfig on <code>/owner/**</code>).</p>
 */
@Controller
public class ReturnController {

    private static final String OWNER_BASE = "/owner/returns";
    private static final String PHARMACIST_BASE = "/pharmacist/returns";
    private static final String ACCOUNTANT_BASE = "/accountant/returns";

    private final ReturnService returnService;
    private final CurrentUserContext currentUserContext;

    public ReturnController(ReturnService returnService, CurrentUserContext currentUserContext) {
        this.returnService = returnService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping({OWNER_BASE, PHARMACIST_BASE, ACCOUNTANT_BASE})
    public String list(@RequestParam(name = "keyword", required = false) String keyword,
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

        Page<ReturnListItemResponse> returnPage =
                returnService.search(keyword, fromDate, toDate, status, PageRequest.of(page, size));

        model.addAttribute("returnPage", returnPage);
        model.addAttribute("returns", returnPage.getContent());
        model.addAttribute("stats", returnService.getStats());

        model.addAttribute("statuses", returnService.listStatuses());

        model.addAttribute("keyword", keyword);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterStatus", status);

        model.addAttribute("currentPage", returnPage.getNumber());
        model.addAttribute("totalPages", returnPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", returnPage.getTotalElements());

        // Tắt bù trừ thì 2 cột cấn trừ luôn bằng 0 ⇒ ẩn hẳn cột thay vì để một cột toàn số 0.
        model.addAttribute("autoOffsetDebt", returnService.isAutoOffsetDebt());
        model.addAttribute("basePath", resolveBasePath(request));
        return "return/list";
    }

    @GetMapping({OWNER_BASE + "/create", PHARMACIST_BASE + "/create"})
    public String createPage(HttpServletRequest request, Model model) {
        ReturnCreateRequest form = new ReturnCreateRequest();
        // Điền sẵn tỷ lệ hoàn mặc định của nhà thuốc; người lập vẫn chỉnh được cho từng phiếu.
        form.setRefundRate(returnService.getDefaultRefundRate());
        model.addAttribute("form", form);
        addCreateFormOptions(model, request);
        return "return/create";
    }

    /** JSON: the still-returnable lines of a chosen invoice, for the create screen. */
    @GetMapping(value = {OWNER_BASE + "/invoices/{invoiceId}/lines",
            PHARMACIST_BASE + "/invoices/{invoiceId}/lines"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<ReturnInvoiceLineResponse> invoiceLines(@PathVariable Integer invoiceId) {
        return returnService.loadInvoiceLines(invoiceId);
    }

    @PostMapping({OWNER_BASE + "/create", PHARMACIST_BASE + "/create"})
    public String create(@ModelAttribute("form") ReturnCreateRequest form,
                         @RequestParam(name = "action", required = false) String action,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes,
                         Model model) {
        String basePath = resolveBasePath(request);
        boolean isOwner = currentUserContext.isOwner();
        boolean asDraft = "draft".equals(action);
        try {
            Integer returnId = returnService.createReturn(
                    form, currentUserContext.getCurrentAccountId(), isOwner, asDraft);

            String message;
            if (asDraft) {
                message = "Đã lưu nháp phiếu trả hàng";
            } else if (isOwner) {
                message = "Tạo phiếu trả hàng thành công (đã duyệt, tồn kho đã cập nhật)";
            } else {
                message = "Đã gửi phiếu trả hàng, đang chờ duyệt";
            }
            redirectAttributes.addFlashAttribute("successMessage", message);
            return "redirect:" + basePath + "/" + returnId;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            model.addAttribute("form", form);
            addCreateFormOptions(model, request);
            return "return/create";
        }
    }

    /** Dữ liệu dùng chung cho màn tạo (lần đầu và khi render lại sau lỗi validate). */
    private void addCreateFormOptions(Model model, HttpServletRequest request) {
        model.addAttribute("returnableInvoices", returnService.listReturnableInvoices(null));
        model.addAttribute("creatorName", currentUserContext.getCurrentAccountName());
        model.addAttribute("returnWindowDays", returnService.getReturnWindowDays());
        model.addAttribute("returnWindowLabel", returnService.getReturnWindowLabel());
        model.addAttribute("defaultRefundRate", returnService.getDefaultRefundRate());
        model.addAttribute("autoOffsetDebt", returnService.isAutoOffsetDebt());
        model.addAttribute("basePath", resolveBasePath(request));
    }

    @PostMapping({OWNER_BASE + "/{returnId}/submit", PHARMACIST_BASE + "/{returnId}/submit"})
    public String submit(@PathVariable Integer returnId,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            returnService.submit(returnId, currentUserContext.isOwner());
            redirectAttributes.addFlashAttribute("successMessage",
                    currentUserContext.isOwner()
                            ? "Đã duyệt phiếu trả hàng (tồn kho đã cập nhật)"
                            : "Đã gửi phiếu trả hàng, đang chờ duyệt");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + basePath + "/" + returnId;
    }

    @GetMapping({OWNER_BASE + "/{returnId}", PHARMACIST_BASE + "/{returnId}", ACCOUNTANT_BASE + "/{returnId}"})
    public String detail(@PathVariable Integer returnId, HttpServletRequest request, Model model) {
        model.addAttribute("detail", returnService.getDetail(returnId));
        model.addAttribute("basePath", resolveBasePath(request));
        // Cho phép bấm thẳng từ "Hóa đơn gốc" sang màn chi tiết hóa đơn — cả 3 role đều xem được hóa đơn.
        model.addAttribute("invoiceBasePath", resolveInvoiceBasePath(request));
        return "return/detail";
    }

    @PostMapping(OWNER_BASE + "/{returnId}/approve")
    public String approve(@PathVariable Integer returnId,
                          @RequestParam(name = "redirectTo", required = false) String redirectTo,
                          RedirectAttributes redirectAttributes) {
        try {
            returnService.approve(returnId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã duyệt phiếu trả hàng (tồn kho đã cập nhật)");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + (redirectTo != null && !redirectTo.isBlank() ? redirectTo : OWNER_BASE + "/" + returnId);
    }

    @PostMapping(OWNER_BASE + "/{returnId}/reject")
    public String reject(@PathVariable Integer returnId,
                         @RequestParam(name = "redirectTo", required = false) String redirectTo,
                         RedirectAttributes redirectAttributes) {
        try {
            returnService.reject(returnId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã từ chối phiếu trả hàng");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + (redirectTo != null && !redirectTo.isBlank() ? redirectTo : OWNER_BASE + "/" + returnId);
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

    /** Invoice-detail base for the current role (InvoiceController maps all three). */
    private String resolveInvoiceBasePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith(PHARMACIST_BASE)) {
            return "/pharmacist/invoices";
        }
        if (uri.startsWith(ACCOUNTANT_BASE)) {
            return "/accountant/invoices";
        }
        return "/owner/invoices";
    }
}
