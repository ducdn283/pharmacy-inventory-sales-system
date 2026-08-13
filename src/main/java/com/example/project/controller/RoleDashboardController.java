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
        this.accountantDashboardService = accountantDashboardService;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Dashboard của Owner sử dụng chung dữ liệu tài chính và giao diện
     * với dashboard của Accountant.
     *
     * Mặc định hiển thị dữ liệu từng ngày trong tuần hiện tại.
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

            Model model
    ) {
        model.addAttribute(
                "dashboard",
                accountantDashboardService.getDashboard(
                        currentUserContext.getCurrentAccountName(),
                        period,
                        date,
                        "/owner"
                )
        );

        /*
         * Dùng để form bộ lọc gửi lại đúng dashboard của Owner,
         * không chuyển nhầm sang đường dẫn của Accountant.
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
     * Dashboard của Accountant.
     *
     * Dùng chung nội dung tài chính với Owner nhưng các liên kết thao tác
     * vẫn sử dụng đường dẫn /accountant tương ứng.
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

            Model model
    ) {
        model.addAttribute(
                "dashboard",
                accountantDashboardService.getDashboard(
                        currentUserContext.getCurrentAccountName(),
                        period,
                        date,
                        "/accountant"
                )
        );

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
     * Dashboard của Pharmacist vẫn giữ nguyên nghiệp vụ cũ,
     * không sử dụng dashboard tài chính của Owner và Accountant.
     */
    @GetMapping("/pharmacist/dashboard")
    public String pharmacistDashboard(
            Model model
    ) {
        model.addAttribute(
                "dashboard",
                dashboardService.pharmacistDashboard(
                        currentUserContext.getCurrentAccountId(),
                        currentUserContext.getCurrentAccountName()
                )
        );

        model.addAttribute(
                "pageTitle",
                "Tổng quan"
        );

        return "dashboard/role-dashboard";
    }
}