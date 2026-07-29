package com.example.project.controller;

import com.example.project.constant.RoleConstants;
import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.ShiftReportCloseRequest;
import com.example.project.dto.response.ShiftReportDetailPageResponse;
import com.example.project.dto.response.ShiftReportListItemResponse;
import com.example.project.entity.Shiftreport;
import com.example.project.service.ShiftreportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

@Controller
public class ShiftreportController {

    private static final String OWNER_BASE = "/owner/shift-reports";
    private static final String PHARMACIST_BASE = "/pharmacist/shift-reports";

    private final ShiftreportService shiftreportService;
    private final CurrentUserContext currentUserContext;

    public ShiftreportController(ShiftreportService shiftreportService,
                                 CurrentUserContext currentUserContext) {
        this.shiftreportService = shiftreportService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping({
            OWNER_BASE,
            PHARMACIST_BASE
    })
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

        Page<ShiftReportListItemResponse> shiftPage = shiftreportService.search(
                keyword, fromDate, toDate, status, PageRequest.of(page, size));

        model.addAttribute("shiftPage", shiftPage);
        model.addAttribute("shifts", shiftPage.getContent());
        model.addAttribute("stats", shiftreportService.getStats());
        model.addAttribute("statuses", shiftreportService.listStatuses());

        model.addAttribute("keyword", keyword);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterStatus", status);

        model.addAttribute("currentPage", shiftPage.getNumber());
        model.addAttribute("totalPages", shiftPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", shiftPage.getTotalElements());

        model.addAttribute("basePath", resolveBasePath(request));

        return "shift-report/list";
    }

    @GetMapping({
            OWNER_BASE + "/{shiftReportId}",
            PHARMACIST_BASE + "/{shiftReportId}"
    })
    public String detail(@PathVariable Integer shiftReportId,
                         HttpServletRequest request,
                         Model model) {
        ShiftReportDetailPageResponse detail = shiftreportService.getDetail(shiftReportId);
        boolean isOwnShift = detail.getCashierId() != null
                && detail.getCashierId().equals(currentUserContext.getCurrentAccountId());

        // Ca dở dang từ ngày trước: PendingShiftInterceptor đã đá người dùng về đây và chặn mọi thao
        // tác khác, nên trang này phải nói rõ lý do thay vì để họ tưởng bị lỗi điều hướng.
        boolean mustCloseNow = isOwnShift
                && shiftreportService.findStaleDraftShift(currentUserContext.getCurrentAccountId())
                        .map(stale -> stale.getId().equals(shiftReportId))
                        .orElse(false);

        model.addAttribute("detail", detail);
        model.addAttribute("basePath", resolveBasePath(request));
        model.addAttribute("isOwner", currentUserContext.isOwner());
        model.addAttribute("isOwnShift", isOwnShift);
        model.addAttribute("mustCloseNow", mustCloseNow);

        return "shift-report/detail";
    }

    @PostMapping({
            OWNER_BASE + "/{shiftReportId}/close",
            PHARMACIST_BASE + "/{shiftReportId}/close"
    })
    public String close(@PathVariable Integer shiftReportId,
                        @ModelAttribute("form") ShiftReportCloseRequest form,
                        HttpServletRequest request,
                        RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);

        try {
            shiftreportService.closeShift(
                    shiftReportId,
                    form.getOpeningCash(),
                    form.getActualClosingCash(),
                    form.getNoteDiscrepancy(),
                    form.getNote(),
                    currentUserContext.getCurrentAccountId(),
                    currentUserContext.isOwner()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    currentUserContext.isOwner()
                            ? "Đã chốt ca và tự động duyệt"
                            : "Đã nộp báo cáo ca cho chủ nhà thuốc duyệt"
            );
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:" + basePath + "/" + shiftReportId;
    }

    @PostMapping(OWNER_BASE + "/{shiftReportId}/approve")
    public String approve(@PathVariable Integer shiftReportId,
                          @RequestParam(name = "redirectTo", required = false) String redirectTo,
                          RedirectAttributes redirectAttributes) {
        try {
            shiftreportService.approve(shiftReportId, currentUserContext.getCurrentAccountId());
            redirectAttributes.addFlashAttribute("successMessage", "Đã duyệt báo cáo ca");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:" + (redirectTo != null && !redirectTo.isBlank()
                ? redirectTo
                : OWNER_BASE + "/" + shiftReportId);
    }

    @PostMapping(OWNER_BASE + "/{shiftReportId}/reject")
    public String reject(@PathVariable Integer shiftReportId,
                         @RequestParam(name = "redirectTo", required = false) String redirectTo,
                         RedirectAttributes redirectAttributes) {
        try {
            shiftreportService.reject(shiftReportId, currentUserContext.getCurrentAccountId());
            redirectAttributes.addFlashAttribute("successMessage", "Đã từ chối báo cáo ca");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:" + (redirectTo != null && !redirectTo.isBlank()
                ? redirectTo
                : OWNER_BASE + "/" + shiftReportId);
    }

    /**
     * Gate in front of the real {@code /logout}: Spring Security's LogoutHandler chain cannot be
     * cancelled mid-flight, so we must stop the request here, before it ever reaches /logout, if the
     * account still has an unsubmitted (Nháp) shift.
     */
    @PostMapping("/logout-guard")
    public String logoutGuard(HttpServletRequest request,
                              HttpServletResponse response,
                              RedirectAttributes redirectAttributes) {
        Integer accountId = currentUserContext.getCurrentAccountId();
        String role = currentUserContext.getCurrentRole();

        Optional<Shiftreport> draft = shiftreportService.findDraftShift(accountId);
        if (draft.isPresent()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Bạn cần nộp báo cáo ca hiện tại trước khi đăng xuất");
            return "redirect:/" + RoleConstants.urlPrefix(role) + "/shift-reports/" + draft.get().getId();
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);

        return "redirect:/signin?logout";
    }

    private String resolveBasePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith(OWNER_BASE)) {
            return OWNER_BASE;
        }
        return PHARMACIST_BASE;
    }
}
