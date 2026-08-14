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

    private final AccountantDashboardService
            accountantDashboardService;

    private final CurrentUserContext
            currentUserContext;

    public RoleDashboardController(
            DashboardService dashboardService,
            AccountantDashboardService accountantDashboardService,
            CurrentUserContext currentUserContext
    ) {
        this.dashboardService = dashboardService;

        this.accountantDashboardService =
                accountantDashboardService;

        this.currentUserContext =
                currentUserContext;
    }

    /**
     * Dashboard tổng dành cho Owner.
     *
     * Mặc định hiển thị dữ liệu theo từng ngày
     * trong tuần hiện tại.
     *
     * productKeyword được sử dụng để tìm:
     *
     * - Nhà cung cấp có giá nhập tốt nhất.
     * - Lịch sử giá nhập của sản phẩm.
     */
    @GetMapping("/owner/dashboard")
    public String ownerDashboard(
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

            @RequestParam(
                    name = "productKeyword",
                    required = false
            )
            String productKeyword,

            Model model
    ) {
        model.addAttribute(
                "dashboard",
                accountantDashboardService
                        .getDashboard(
                                currentUserContext
                                        .getCurrentAccountName(),

                                period,

                                date,

                                "/owner",

                                productKeyword
                        )
        );

        /*
         * Giúp các form bộ lọc gửi lại đúng
         * đường dẫn dashboard của Owner.
         */
        model.addAttribute(
                "dashboardPath",
                "/owner/dashboard"
        );

        model.addAttribute(
                "dashboardRoleLabel",
                "Chủ nhà thuốc"
        );

        model.addAttribute(
                "pageTitle",
                "Tổng quan"
        );

        return "dashboard/accountant-dashboard";
    }

    /**
     * Dashboard dành cho Accountant.
     *
     * Accountant sử dụng chung giao diện và dữ liệu
     * phân tích tài chính với Owner nhưng các đường
     * dẫn thao tác sử dụng prefix /accountant.
     */
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

            @RequestParam(
                    name = "productKeyword",
                    required = false
            )
            String productKeyword,

            Model model
    ) {
        model.addAttribute(
                "dashboard",
                accountantDashboardService
                        .getDashboard(
                                currentUserContext
                                        .getCurrentAccountName(),

                                period,

                                date,

                                "/accountant",

                                productKeyword
                        )
        );

        /*
         * Giúp các form bộ lọc gửi lại đúng
         * dashboard của Accountant.
         */
        model.addAttribute(
                "dashboardPath",
                "/accountant/dashboard"
        );

        model.addAttribute(
                "dashboardRoleLabel",
                "Kế toán"
        );

        model.addAttribute(
                "pageTitle",
                "Tổng quan"
        );

        return "dashboard/accountant-dashboard";
    }

    /**
     * Dashboard bán hàng dành cho Pharmacist.
     *
     * Dashboard này sử dụng DashboardService riêng,
     * không sử dụng các số liệu tổng của Owner.
     */
    @GetMapping("/pharmacist/dashboard")
    public String pharmacistDashboard(
            Model model
    ) {
        model.addAttribute(
                "dashboard",
                dashboardService
                        .pharmacistDashboard(
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