package com.example.project.controller;

import com.example.project.service.SidebarMenuService;
import com.example.project.view.SidebarMenuItem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the generic placeholder page for the last sidebar route that has no real screen yet.
 *
 * <p><strong>Down to one route (2026-07-30).</strong> Every other menu item now resolves to a real
 * controller, so the eight remaining stand-ins — {@code /owner/suppliers}, {@code /owner/customers},
 * {@code /pharmacist/customers} (the sidebar points at the role-agnostic {@code /supplier} and
 * {@code /customer} instead), {@code /owner/vat-invoices}, {@code /owner/daily-reports},
 * {@code /accountant/vat-invoices}, {@code /accountant/daily-reports} (dropped from the sidebar in
 * the 2026-07-25 redesign) and {@code /pharmacist/stock-outs/create} (never a menu item) — were
 * deleted. Nothing linked to them.</p>
 *
 * <p>{@code /accountant/dashboard} stays because it is still the Accountant's first menu item and
 * their landing page after login, and {@code DashboardService} implements only the Owner and
 * Pharmacist views. <strong>Delete this class the moment a real Accountant dashboard exists.</strong></p>
 *
 * <p>The page title / module come from {@link SidebarMenuService} by request URI, so labels live in
 * exactly one place (the menu config).</p>
 *
 * <p><strong>Delete a route here the moment the real screen claims it.</strong> Spring throws
 * {@code IllegalStateException: Ambiguous handler methods mapped for ...} — a 500 on every request,
 * not a startup failure — when two {@code @Controller}s map the same path. That is exactly what
 * happened to all three {@code /…/notifications} pages on 2026-07-29: {@code NotificationPageController}
 * shipped while the placeholder entries stayed behind.</p>
 */
@Controller
public class PlaceholderController {

    private final SidebarMenuService sidebarMenuService;

    public PlaceholderController(SidebarMenuService sidebarMenuService) {
        this.sidebarMenuService = sidebarMenuService;
    }

    @GetMapping("/accountant/dashboard")
    public String accountantDashboard(HttpServletRequest request, Model model) {
        return render(request, model);
    }

    private String render(HttpServletRequest request, Model model) {
        SidebarMenuItem item = sidebarMenuService.findByUri(request.getRequestURI());
        model.addAttribute("pageTitle", item != null ? item.getLabel() : "Trang");
        model.addAttribute("moduleName", item != null ? item.getModule() : "-");
        return "placeholder";
    }
}
