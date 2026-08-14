package com.example.project;

import com.example.project.config.SecurityConfig;
import com.example.project.config.WebConfig;
import com.example.project.context.CurrentUserContext;
import com.example.project.controller.DashboardController;
import com.example.project.controller.PermissionController;
import com.example.project.controller.RoleDashboardController;
import com.example.project.dto.response.AccountantDashboardResponse;
import com.example.project.dto.response.DashboardView;
import com.example.project.security.AccountAuthenticationProvider;
import com.example.project.security.AccountPrincipal;
import com.example.project.service.AccountantDashboardService;
import com.example.project.service.CustomAccountDetailsService;
import com.example.project.service.DashboardService;
import com.example.project.service.FinancialsettingService;
import com.example.project.service.OwnerPermissionService;
import com.example.project.service.SidebarMenuService;
import com.example.project.view.PermissionAccountRow;
import com.example.project.view.PermissionPageView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Kiểm tra navigation, phân quyền và khả năng render
 * các trang sử dụng Thymeleaf mà không kết nối database.
 */
@WebMvcTest(
        controllers = {
                PermissionController.class,
                DashboardController.class,
                RoleDashboardController.class
        }
)
@Import({
        SecurityConfig.class,
        WebConfig.class,
        SidebarMenuService.class,
        CurrentUserContext.class
})
class NavigationRenderingTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CustomAccountDetailsService
            customAccountDetailsService;

    @MockitoBean
    private AccountAuthenticationProvider
            accountAuthenticationProvider;

    @MockitoBean
    private OwnerPermissionService
            ownerPermissionService;

    @MockitoBean
    private AccountantDashboardService
            accountantDashboardService;

    /*
     * RoleDashboardController vẫn cần DashboardService
     * cho dashboard của Pharmacist.
     */
    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private FinancialsettingService
            financialsettingService;

    @BeforeEach
    void setUp() {
        when(
                ownerPermissionService
                        .getPermissionPage(
                                any(),
                                anyInt(),
                                anyInt()
                        )
        ).thenReturn(
                emptyPage()
        );

        /*
         * Giữ stub cho overload cũ vì những controller
         * hoặc test cũ vẫn có thể sử dụng phương thức này.
         */
        when(
                accountantDashboardService
                        .getDashboard(anyString())
        ).thenReturn(
                emptyAccountantDashboard()
        );

        when(
                accountantDashboardService
                        .getDashboard(
                                anyString(),
                                anyString(),
                                nullable(String.class)
                        )
        ).thenReturn(
                emptyAccountantDashboard()
        );

        /*
         * Overload mới dùng chung cho Owner và Accountant.
         */
        when(
                accountantDashboardService
                        .getDashboard(
                                anyString(),
                                anyString(),
                                nullable(String.class),
                                anyString()
                        )
        ).thenReturn(
                emptyAccountantDashboard()
        );

        when(
                financialsettingService
                        .isSetupConfirmed()
        ).thenReturn(true);
    }

    private static PermissionPageView emptyPage() {
        return new PermissionPageView(
                List.of(),
                0,
                10,
                0L,
                0,
                null
        );
    }

    private static RequestPostProcessor as(
            String role,
            String displayName
    ) {
        AccountPrincipal principal =
                new AccountPrincipal(
                        1,
                        displayName,
                        role.toLowerCase(),
                        displayName
                                + "@example.com",
                        "pw",
                        true,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_" + role
                                )
                        ),
                        role
                );

        return authentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        "pw",
                        principal.getAuthorities()
                )
        );
    }

    private static AccountantDashboardResponse
    emptyAccountantDashboard() {
        return new AccountantDashboardResponse(
                "Người dùng kiểm thử",

                "Tổng quan kinh doanh và tài chính hôm nay",

                List.of(),

                List.of(
                        new AccountantDashboardResponse.MetricCard(
                                "Doanh thu",
                                "0đ",
                                "Tuần hiện tại",
                                "ti ti-chart-line",
                                "success",
                                "/accountant/invoices"
                        ),

                        new AccountantDashboardResponse.MetricCard(
                                "Giá vốn hàng bán",
                                "0đ",
                                "Giá nhập thực tế",
                                "ti ti-packages",
                                "info",
                                "/accountant/invoices"
                        ),

                        new AccountantDashboardResponse.MetricCard(
                                "Lợi nhuận gộp",
                                "0đ",
                                "Doanh thu trừ giá vốn",
                                "ti ti-coins",
                                "success",
                                "/accountant/invoices"
                        )
                ),

                new DashboardView.DashboardChart(
                        "Tổng quan kinh doanh và tài chính",
                        "line",

                        List.of(
                                "Thứ 2"
                        ),

                        List.of(
                                new DashboardView.ChartSeries(
                                        "Doanh thu",
                                        List.of(
                                                BigDecimal.ZERO
                                        )
                                ),

                                new DashboardView.ChartSeries(
                                        "Giá vốn",
                                        List.of(
                                                BigDecimal.ZERO
                                        )
                                ),

                                new DashboardView.ChartSeries(
                                        "Lợi nhuận gộp",
                                        List.of(
                                                BigDecimal.ZERO
                                        )
                                )
                        )
                ),

                List.of(),

                List.of()
        );
    }

    @Test
    void ownerSeesPermissionsPageWithRealChrome()
            throws Exception {

        mvc.perform(
                        get("/owner/permissions")
                                .with(
                                        as(
                                                "OWNER",
                                                "Olivia Owner"
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        view().name(
                                "owner/permissions"
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Bảng phân quyền"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Không tìm thấy tài khoản nào."
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Olivia Owner"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                "Thêm phân quyền"
                                        )
                                )
                        )
                )
                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                "Demo: act as role"
                                        )
                                )
                        )
                );
    }

    @Test
    void ownerPageRendersAccountRowsWithRealChrome()
            throws Exception {

        PermissionAccountRow row =
                new PermissionAccountRow(
                        1,
                        "Nguyễn Văn A",
                        "pharmacist01",
                        "PHARMACIST",
                        "Dược sĩ",
                        false
                );

        PermissionPageView pageView =
                new PermissionPageView(
                        List.of(row),
                        0,
                        10,
                        1L,
                        1,
                        null
                );

        when(
                ownerPermissionService
                        .getPermissionPage(
                                any(),
                                anyInt(),
                                anyInt()
                        )
        ).thenReturn(pageView);

        mvc.perform(
                        get("/owner/permissions")
                                .with(
                                        as(
                                                "OWNER",
                                                "Olivia Owner"
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Nguyễn Văn A"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "pharmacist01"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Dược sĩ"
                                )
                        )
                );
    }

    @Test
    void lastOwnerRowIsReadOnlyWithRealChrome()
            throws Exception {

        PermissionAccountRow lastOwner =
                new PermissionAccountRow(
                        1,
                        "Olivia Owner",
                        "owner01",
                        "OWNER",
                        "Chủ nhà thuốc",
                        true
                );

        PermissionPageView pageView =
                new PermissionPageView(
                        List.of(lastOwner),
                        0,
                        10,
                        1L,
                        1,
                        null
                );

        when(
                ownerPermissionService
                        .getPermissionPage(
                                any(),
                                anyInt(),
                                anyInt()
                        )
        ).thenReturn(pageView);

        mvc.perform(
                        get("/owner/permissions")
                                .with(
                                        as(
                                                "OWNER",
                                                "Olivia Owner"
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Chủ nhà thuốc (duy nhất)"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                "name=\"role\""
                                        )
                                )
                        )
                );
    }

    @Test
    void nonOwnerIsForbiddenFromOwnerArea()
            throws Exception {

        mvc.perform(
                        get("/owner/permissions")
                                .with(
                                        as(
                                                "PHARMACIST",
                                                "Phong Pharmacist"
                                        )
                                )
                )
                .andExpect(
                        status().isForbidden()
                );
    }

    @Test
    void accountantSeesSharedFinancialDashboard()
            throws Exception {

        mvc.perform(
                        get("/accountant/dashboard")
                                .with(
                                        as(
                                                "ACCOUNTANT",
                                                "An Accountant"
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        view().name(
                                "dashboard/accountant-dashboard"
                        )
                )
                .andExpect(
                        model().attribute(
                                "dashboardPath",
                                "/accountant/dashboard"
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Tổng quan kinh doanh và tài chính"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Doanh thu"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Giá vốn hàng bán"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Lợi nhuận gộp"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Cảnh báo cần xử lý"
                                )
                        )
                );
    }

    @Test
    void ownerUsesSameFinancialDashboardAsAccountant()
            throws Exception {

        mvc.perform(
                        get("/owner/dashboard")
                                .with(
                                        as(
                                                "OWNER",
                                                "Olivia Owner"
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        view().name(
                                "dashboard/accountant-dashboard"
                        )
                )
                .andExpect(
                        model().attribute(
                                "dashboardPath",
                                "/owner/dashboard"
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Tổng quan kinh doanh và tài chính"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Doanh thu"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Giá vốn hàng bán"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Lợi nhuận gộp"
                                )
                        )
                );
    }

    @Test
    void dashboardFilterUsesVietnameseLabels()
            throws Exception {

        mvc.perform(
                        get("/accountant/dashboard")
                                .param(
                                        "period",
                                        "quarter"
                                )
                                .param(
                                        "date",
                                        "2026-08-14"
                                )
                                .with(
                                        as(
                                                "ACCOUNTANT",
                                                "An Accountant"
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        content().string(
                                containsString("Ngày")
                        )
                )
                .andExpect(
                        content().string(
                                containsString("Tuần")
                        )
                )
                .andExpect(
                        content().string(
                                containsString("Tháng")
                        )
                )
                .andExpect(
                        content().string(
                                containsString("Quý")
                        )
                )
                .andExpect(
                        content().string(
                                containsString("Năm")
                        )
                );
    }

    @Test
    void permissionPageEchoesSearch()
            throws Exception {

        mvc.perform(
                        get("/owner/permissions")
                                .param(
                                        "search",
                                        "pharmacist01"
                                )
                                .with(
                                        as(
                                                "OWNER",
                                                "Olivia Owner"
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        model().attribute(
                                "search",
                                "pharmacist01"
                        )
                );
    }

    @Test
    void unauthenticatedUserIsSentToSignin()
            throws Exception {

        mvc.perform(
                        get("/owner/permissions")
                )
                .andExpect(
                        status()
                                .is3xxRedirection()
                )
                .andExpect(
                        redirectedUrl(
                                "/signin"
                        )
                );
    }

    @Test
    void dashboardBridgeRedirectsToRoleDashboard()
            throws Exception {

        mvc.perform(
                        get("/dashboard")
                                .with(
                                        as(
                                                "PHARMACIST",
                                                "Phong Pharmacist"
                                        )
                                )
                )
                .andExpect(
                        status()
                                .is3xxRedirection()
                )
                .andExpect(
                        redirectedUrl(
                                "/pharmacist/dashboard"
                        )
                );
    }
}