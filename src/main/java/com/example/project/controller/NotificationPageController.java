package com.example.project.controller;

import com.example.project.constant.NotificationCategory;
import com.example.project.constant.NotificationSeverity;
import com.example.project.constant.NotificationStatus;
import com.example.project.constant.RoleConstants;
import com.example.project.context.CurrentUserContext;
import com.example.project.dto.response.NotificationResponse;
import com.example.project.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;

@Controller
public class NotificationPageController {

    private final NotificationService notificationService;
    private final CurrentUserContext currentUserContext;

    public NotificationPageController(NotificationService notificationService,
                                      CurrentUserContext currentUserContext) {
        this.notificationService = notificationService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping({
            "/owner/notifications",
            "/accountant/notifications",
            "/pharmacist/notifications"
    })
    public String list(@RequestParam(name = "keyword", required = false) String keyword,
                       @RequestParam(name = "category", required = false) String category,
                       @RequestParam(name = "severity", required = false) String severity,
                       @RequestParam(name = "status", required = false) String status,
                       @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly,
                       @RequestParam(name = "selectedId", required = false) Integer selectedId,
                       HttpServletRequest request,
                       Model model) {
        Integer accountId = currentUserContext.getCurrentAccountId();

        List<NotificationResponse> notifications = notificationService.search(
                accountId,
                keyword,
                category,
                severity,
                status,
                unreadOnly
        );

        Optional<NotificationResponse> selected = notificationService.findForAccount(accountId, selectedId);

        if (selected.isEmpty() && !notifications.isEmpty()) {
            selected = Optional.of(notifications.get(0));
        }

        String basePath = resolveBasePath(request);

        model.addAttribute("pageTitle", "Thông báo");
        model.addAttribute("basePath", basePath);
        model.addAttribute("notifications", notifications);
        model.addAttribute("selectedNotification", selected.orElse(null));
        model.addAttribute("stats", notificationService.getStats(accountId));
        model.addAttribute("categories", NotificationCategory.vietnameseLabels());
        model.addAttribute("severities", NotificationSeverity.ALL);
        model.addAttribute("statuses", NotificationStatus.ALL);

        model.addAttribute("keyword", keyword);
        model.addAttribute("filterCategory", category);
        model.addAttribute("filterSeverity", severity);
        model.addAttribute("filterStatus", status);
        model.addAttribute("unreadOnly", unreadOnly);

        return "notification/list";
    }

    @PostMapping({
            "/owner/notifications/{notificationId}/read",
            "/accountant/notifications/{notificationId}/read",
            "/pharmacist/notifications/{notificationId}/read"
    })
    public String markAsRead(@PathVariable Integer notificationId,
                             HttpServletRequest request,
                             RedirectAttributes redirectAttributes) {
        try {
            notificationService.markAsRead(currentUserContext.getCurrentAccountId(), notificationId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã đánh dấu thông báo là đã đọc");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:" + resolveBasePath(request) + "?selectedId=" + notificationId;
    }

    @PostMapping({
            "/owner/notifications/read-all",
            "/accountant/notifications/read-all",
            "/pharmacist/notifications/read-all"
    })
    public String markAllAsRead(HttpServletRequest request,
                                RedirectAttributes redirectAttributes) {
        notificationService.markAllAsRead(currentUserContext.getCurrentAccountId());
        redirectAttributes.addFlashAttribute("successMessage", "Đã đánh dấu tất cả thông báo là đã đọc");

        return "redirect:" + resolveBasePath(request);
    }

    @PostMapping({
            "/owner/notifications/{notificationId}/dismiss",
            "/accountant/notifications/{notificationId}/dismiss",
            "/pharmacist/notifications/{notificationId}/dismiss"
    })
    public String dismiss(@PathVariable Integer notificationId,
                          HttpServletRequest request,
                          RedirectAttributes redirectAttributes) {
        try {
            notificationService.dismiss(currentUserContext.getCurrentAccountId(), notificationId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã ẩn thông báo");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:" + resolveBasePath(request);
    }

    private String resolveBasePath(HttpServletRequest request) {
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