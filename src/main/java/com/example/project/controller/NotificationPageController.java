package com.example.project.controller;

import com.example.project.constant.NotificationCategory;
import com.example.project.constant.NotificationSeverity;
import com.example.project.constant.NotificationStatus;
import com.example.project.constant.RoleConstants;
import com.example.project.context.CurrentUserContext;
import com.example.project.dto.response.NotificationResponse;
import com.example.project.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;

@Controller
public class NotificationPageController {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int PAGE_WINDOW_SIZE = 5;

    private static final List<String> LIST_STATE_PARAMETERS = List.of(
            "keyword",
            "category",
            "severity",
            "status",
            "unreadOnly",
            "page",
            "size"
    );

    private final NotificationService notificationService;
    private final CurrentUserContext currentUserContext;

    public NotificationPageController(
            NotificationService notificationService,
            CurrentUserContext currentUserContext
    ) {
        this.notificationService = notificationService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping({
            "/owner/notifications",
            "/accountant/notifications",
            "/pharmacist/notifications"
    })
    public String list(
            @RequestParam(name = "keyword", required = false)
            String keyword,

            @RequestParam(name = "category", required = false)
            String category,

            @RequestParam(name = "severity", required = false)
            String severity,

            @RequestParam(name = "status", required = false)
            String status,

            @RequestParam(name = "unreadOnly", defaultValue = "false")
            boolean unreadOnly,

            @RequestParam(name = "selectedId", required = false)
            Integer selectedId,

            @RequestParam(name = "page", defaultValue = "0")
            int page,

            @RequestParam(name = "size", defaultValue = "10")
            int size,

            HttpServletRequest request,
            Model model
    ) {
        Integer accountId = currentUserContext.getCurrentAccountId();

        int safePage = Math.max(page, 0);
        int safeSize = normalizePageSize(size);

        Page<NotificationResponse> notificationPage =
                notificationService.search(
                        accountId,
                        keyword,
                        category,
                        severity,
                        status,
                        unreadOnly,
                        PageRequest.of(safePage, safeSize)
                );

        /*
         * Nếu người dùng truy cập một trang vượt quá tổng số trang,
         * tự động đưa về trang cuối cùng.
         */
        if (notificationPage.getTotalPages() > 0
                && safePage >= notificationPage.getTotalPages()) {

            safePage = notificationPage.getTotalPages() - 1;

            notificationPage = notificationService.search(
                    accountId,
                    keyword,
                    category,
                    severity,
                    status,
                    unreadOnly,
                    PageRequest.of(safePage, safeSize)
            );
        }

        List<NotificationResponse> notifications =
                notificationPage.getContent();

        Optional<NotificationResponse> selected =
                notificationService.findForAccount(accountId, selectedId);

        /*
         * Nếu chưa chọn thông báo cụ thể thì hiển thị
         * thông báo đầu tiên của trang hiện tại.
         */
        if (selected.isEmpty() && !notifications.isEmpty()) {
            selected = Optional.of(notifications.get(0));
        }

        String basePath = resolveBasePath(request);

        model.addAttribute("pageTitle", "Thông báo");
        model.addAttribute("basePath", basePath);

        model.addAttribute("notifications", notifications);
        model.addAttribute(
                "selectedNotification",
                selected.orElse(null)
        );

        model.addAttribute(
                "stats",
                notificationService.getStats(accountId)
        );

        model.addAttribute(
                "categories",
                NotificationCategory.vietnameseLabels()
        );

        model.addAttribute(
                "severities",
                NotificationSeverity.ALL
        );

        model.addAttribute(
                "statuses",
                NotificationStatus.ALL
        );

        model.addAttribute("keyword", keyword);
        model.addAttribute("filterCategory", category);
        model.addAttribute("filterSeverity", severity);
        model.addAttribute("filterStatus", status);
        model.addAttribute("unreadOnly", unreadOnly);

        int totalPages = notificationPage.getTotalPages();

        int startPage = calculateStartPage(
                safePage,
                totalPages
        );

        int endPage = totalPages == 0
                ? 0
                : Math.min(
                totalPages - 1,
                startPage + PAGE_WINDOW_SIZE - 1
        );

        model.addAttribute("currentPage", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageSize", safeSize);
        model.addAttribute(
                "totalItems",
                notificationPage.getTotalElements()
        );

        model.addAttribute("startPage", startPage);
        model.addAttribute("endPage", endPage);

        return "notification/list";
    }

    @PostMapping({
            "/owner/notifications/{notificationId}/read",
            "/accountant/notifications/{notificationId}/read",
            "/pharmacist/notifications/{notificationId}/read"
    })
    public String markAsRead(
            @PathVariable Integer notificationId,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            notificationService.markAsRead(
                    currentUserContext.getCurrentAccountId(),
                    notificationId
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã đánh dấu thông báo là đã đọc"
            );
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        preserveListState(request, redirectAttributes);

        redirectAttributes.addAttribute(
                "selectedId",
                notificationId
        );

        return "redirect:" + resolveBasePath(request);
    }

    @PostMapping({
            "/owner/notifications/read-all",
            "/accountant/notifications/read-all",
            "/pharmacist/notifications/read-all"
    })
    public String markAllAsRead(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        notificationService.markAllAsRead(
                currentUserContext.getCurrentAccountId()
        );

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Đã đánh dấu tất cả thông báo là đã đọc"
        );

        preserveListState(request, redirectAttributes);

        return "redirect:" + resolveBasePath(request);
    }

    @PostMapping({
            "/owner/notifications/{notificationId}/dismiss",
            "/accountant/notifications/{notificationId}/dismiss",
            "/pharmacist/notifications/{notificationId}/dismiss"
    })
    public String dismiss(
            @PathVariable Integer notificationId,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            notificationService.dismiss(
                    currentUserContext.getCurrentAccountId(),
                    notificationId
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã ẩn thông báo"
            );
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        preserveListState(request, redirectAttributes);

        return "redirect:" + resolveBasePath(request);
    }

    private int normalizePageSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }

        return Math.min(size, MAX_PAGE_SIZE);
    }

    private int calculateStartPage(
            int currentPage,
            int totalPages
    ) {
        if (totalPages <= PAGE_WINDOW_SIZE) {
            return 0;
        }

        int centeredStart =
                currentPage - PAGE_WINDOW_SIZE / 2;

        return Math.max(
                0,
                Math.min(
                        centeredStart,
                        totalPages - PAGE_WINDOW_SIZE
                )
        );
    }

    private void preserveListState(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        for (String parameterName : LIST_STATE_PARAMETERS) {
            String value =
                    request.getParameter(parameterName);

            if (value != null && !value.isBlank()) {
                redirectAttributes.addAttribute(
                        parameterName,
                        value
                );
            }
        }
    }

    private String resolveBasePath(
            HttpServletRequest request
    ) {
        String uri = request.getRequestURI();

        if (uri.startsWith("/owner/notifications")) {
            return "/owner/notifications";
        }

        if (uri.startsWith("/accountant/notifications")) {
            return "/accountant/notifications";
        }

        if (uri.startsWith("/pharmacist/notifications")) {
            return "/pharmacist/notifications";
        }

        String role = currentUserContext.getCurrentRole();

        if (RoleConstants.ACCOUNTANT.equals(role)) {
            return "/accountant/notifications";
        }

        if (RoleConstants.PHARMACIST.equals(role)) {
            return "/pharmacist/notifications";
        }

        return "/owner/notifications";
    }
}