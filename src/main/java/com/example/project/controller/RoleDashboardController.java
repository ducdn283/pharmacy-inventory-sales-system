package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.service.AccountantDashboardService;
import com.example.project.service.DashboardService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class RoleDashboardController {

    private final DashboardService dashboardService;
    private final AccountantDashboardService accountantDashboardService;
    private final CurrentUserContext currentUserContext;

    public RoleDashboardController(
            DashboardService dashboardService,
            AccountantDashboardService accountantDashboardService,
            CurrentUserContext currentUserContext
    ) {
        this.dashboardService = dashboardService;
        this.accountantDashboardService =
                accountantDashboardService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/owner/dashboard")
    public String ownerDashboard(
            Model model
    ) {
        model.addAttribute(
                "dashboard",
                dashboardService.ownerDashboard(
                        currentUserContext
                                .getCurrentAccountName()
                )
        );

        model.addAttribute(
                "pageTitle",
                "Tổng quan"
        );

        return "dashboard/role-dashboard";
    }

    @GetMapping("/accountant/dashboard")
    public String accountantDashboard(
            @RequestParam(
                    name = "period",
                    defaultValue = "week"
            )
            String period,

            @RequestParam(
                    name = "date",
                    required = false
            )
            String date,

            Model model
    ) {
        model.addAttribute(
                "dashboard",
                accountantDashboardService.getDashboard(
                        currentUserContext
                                .getCurrentAccountName(),
                        period,
                        date
                )
        );

        model.addAttribute(
                "pageTitle",
                "Tổng quan"
        );

        return "dashboard/accountant-dashboard";
    }

    @GetMapping("/pharmacist/dashboard")
    public String pharmacistDashboard(
            Model model
    ) {
        model.addAttribute(
                "dashboard",
                dashboardService.pharmacistDashboard(
                        currentUserContext
                                .getCurrentAccountId(),
                        currentUserContext
                                .getCurrentAccountName()
                )
        );

        model.addAttribute(
                "pageTitle",
                "Tổng quan"
        );

        return "dashboard/role-dashboard";
    }
}