package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.service.DashboardService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RoleDashboardController {

    private final DashboardService dashboardService;
    private final CurrentUserContext currentUserContext;

    public RoleDashboardController(DashboardService dashboardService,
                                   CurrentUserContext currentUserContext) {
        this.dashboardService = dashboardService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/owner/dashboard")
    public String ownerDashboard(Model model) {
        model.addAttribute(
                "dashboard",
                dashboardService.ownerDashboard(currentUserContext.getCurrentAccountName())
        );
        model.addAttribute("pageTitle", "Tổng quan");
        return "dashboard/role-dashboard";
    }

    @GetMapping("/pharmacist/dashboard")
    public String pharmacistDashboard(Model model) {
        model.addAttribute(
                "dashboard",
                dashboardService.pharmacistDashboard(
                        currentUserContext.getCurrentAccountId(),
                        currentUserContext.getCurrentAccountName()
                )
        );
        model.addAttribute("pageTitle", "Tổng quan");
        return "dashboard/role-dashboard";
    }
}