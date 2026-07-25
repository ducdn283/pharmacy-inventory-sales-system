package com.example.project.controller;

import com.example.project.service.SidebarMenuService;
import com.example.project.view.SidebarMenuItem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves a single generic placeholder page for every role-specific route in the sidebar
 * that does not yet have a full implementation.
 *
 * <p>This exists so the role-based navigation is fully clickable while the real module screens
 * are still being built. The page title / module shown are looked up from {@link SidebarMenuService} by request URI,
 * so labels live in exactly one place (the menu config). Real module pages can later
 * replace these by adding more specific mappings — Spring will prefer the exact path over
 * these placeholder mappings. {@code /owner/permissions} is intentionally excluded (it has
 * its own {@link PermissionController}).</p>
 *
 * <p>Routes are enumerated explicitly (no greedy wildcard) so unknown URLs still 404.</p>
 */
@Controller
public class PlaceholderController {

    private final SidebarMenuService sidebarMenuService;

    public PlaceholderController(SidebarMenuService sidebarMenuService) {
        this.sidebarMenuService = sidebarMenuService;
    }

    @GetMapping({
            "/owner/dashboard",
            "/owner/suppliers",
            "/owner/debts",
            "/owner/vat-invoices",
            "/owner/customers",
            "/owner/notifications",
            "/owner/daily-reports"
    })
    public String owner(HttpServletRequest request, Model model) {
        return render(request, model);
    }

    @GetMapping({
            "/pharmacist/dashboard",
            "/pharmacist/customers",
            "/pharmacist/notifications",
            "/pharmacist/stock-outs/create"
    })
    public String pharmacist(HttpServletRequest request, Model model) {
        return render(request, model);
    }

    // "/accountant/incomes" mới có trong sidebar (2026-07-25) nhưng Income hiện chỉ có route cho
    // Owner + Pharmacist — để placeholder thay vì mở quyền truy cập màn hình thật cho Kế toán, vì đó
    // là quyết định nghiệp vụ chứ không phải việc của một thay đổi sidebar.
    // ("/accountant/returns" đã bỏ khỏi đây 2026-07-25 — ReturnController nay có route xem-thôi thật
    // sự cho Accountant, xem ReturnController.ACCOUNTANT_BASE.)
    @GetMapping({
            "/accountant/dashboard",
            "/accountant/debts",
            "/accountant/daily-reports",
            "/accountant/vat-invoices",
            "/accountant/notifications",
            "/accountant/incomes"
    })
    public String accountant(HttpServletRequest request, Model model) {
        return render(request, model);
    }

    private String render(HttpServletRequest request, Model model) {
        SidebarMenuItem item = sidebarMenuService.findByUri(request.getRequestURI());
        model.addAttribute("pageTitle", item != null ? item.getLabel() : "Trang");
        model.addAttribute("moduleName", item != null ? item.getModule() : "-");
        return "placeholder";
    }
}
