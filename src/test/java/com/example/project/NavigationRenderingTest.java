package com.example.project;

import com.example.project.config.SecurityConfig;
import com.example.project.config.WebConfig;
import com.example.project.context.CurrentUserContext;
import com.example.project.controller.DashboardController;
import com.example.project.controller.PermissionController;
import com.example.project.dto.response.DashboardView;
import com.example.project.security.AccountAuthenticationProvider;
import com.example.project.security.AccountPrincipal;
import com.example.project.service.CustomAccountDetailsService;
import com.example.project.service.OwnerPermissionService;
import com.example.project.service.SidebarMenuService;
import com.example.project.view.PermissionAccountRow;
import com.example.project.view.PermissionPageView;
import com.example.project.dto.response.AccountantDashboardResponse;
import com.example.project.service.AccountantDashboardService;
import java.math.BigDecimal;
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

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Web-slice test (no database) for the real, security-backed navigation. It exercises the
 * SecurityConfig role rules, the SidebarInterceptor, and the Thymeleaf fragments/pages with an
 * authenticated {@link AccountPrincipal} injected per request — verifying both rendering and
 * URL-level role authorization. The DB-backed {@link OwnerPermissionService} is mocked; by
 * default it returns an empty {@link PermissionPageView} so the page renders without a database.
 *
 * <p>Single-store: there is no {@code Branch} concept anywhere in this test. Detailed Permission
 * Table behavior (dropdown options, last-owner lock, save/redirect flow) is covered by the
 * dedicated {@code PermissionControllerTest}; this file only proves the screen renders correctly
 * through the real security + sidebar chrome.</p>
 */
@WebMvcTest(controllers = {PermissionController.class, DashboardController.class})
@Import({
        SecurityConfig.class,
        WebConfig.class,
        SidebarMenuService.class,
        CurrentUserContext.class
})
class NavigationRenderingTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CustomAccountDetailsService customAccountDetailsService;

    @MockitoBean
    AccountAuthenticationProvider accountAuthenticationProvider;

    // PermissionController depends on this DB-backed service; mock returns an empty page by default.
    @MockitoBean
    OwnerPermissionService ownerPermissionService;

    @MockitoBean
    AccountantDashboardService accountantDashboardService;

    @BeforeEach
    void setUp() {
        // Default: a valid empty page so the Thymeleaf template renders without NPEs.
        when(ownerPermissionService.getPermissionPage(any(), anyInt(), anyInt()))
                .thenReturn(emptyPage());
        when(accountantDashboardService.getDashboard(any()))
                .thenReturn(emptyAccountantDashboard());
    }

    private static PermissionPageView emptyPage() {
        return new PermissionPageView(List.of(), 0, 10, 0L, 0, null);
    }

    private static RequestPostProcessor as(String role, String displayName) {
        AccountPrincipal principal = new AccountPrincipal(
                1, displayName, role.toLowerCase(), displayName + "@example.com", "pw", true,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)), role);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, "pw", principal.getAuthorities()));
    }

    private static AccountantDashboardResponse
    emptyAccountantDashboard() {

        return new AccountantDashboardResponse(
                "An Accountant",
                "Đối chiếu tài chính hôm nay",
                List.of(),
                List.of(),
                new DashboardView.DashboardChart(
                        "Tổng quan thu - chi - công nợ trong tuần",
                        "line",
                        List.of("Thứ 2"),
                        List.of(
                                new DashboardView.ChartSeries(
                                        "Thu",
                                        List.of(BigDecimal.ZERO)
                                )
                        )
                ),
                List.of(),
                List.of()
        );
    }

    @Test
    void ownerSeesPermissionsPageWithRealChrome() throws Exception {
        mvc.perform(get("/owner/permissions").with(as("OWNER", "Olivia Owner")))
                .andExpect(status().isOk())
                .andExpect(view().name("owner/permissions"))
                .andExpect(content().string(containsString("Bảng phân quyền")))           // title + sidebar link
                .andExpect(content().string(containsString("Không tìm thấy tài khoản nào."))) // empty state
                .andExpect(content().string(containsString("Olivia Owner")))             // topbar shows real user
                .andExpect(content().string(not(containsString("Thêm phân quyền"))))      // add form removed
                .andExpect(content().string(not(containsString("Demo: act as role"))));   // demo switcher gone
    }

    @Test
    void ownerPageRendersAccountRowsWithRealChrome() throws Exception {
        PermissionAccountRow row = new PermissionAccountRow(
                1, "Nguyễn Văn A", "pharmacist01", "PHARMACIST", "Dược sĩ", false);
        PermissionPageView pageView = new PermissionPageView(List.of(row), 0, 10, 1L, 1, null);

        when(ownerPermissionService.getPermissionPage(any(), anyInt(), anyInt()))
                .thenReturn(pageView);

        mvc.perform(get("/owner/permissions").with(as("OWNER", "Olivia Owner")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nguyễn Văn A")))    // account row
                .andExpect(content().string(containsString("pharmacist01")))    // username/email column
                .andExpect(content().string(containsString("Dược sĩ")));        // role dropdown option
    }

    @Test
    void lastOwnerRowIsReadOnlyWithRealChrome() throws Exception {
        PermissionAccountRow lastOwner = new PermissionAccountRow(
                1, "Olivia Owner", "owner01", "OWNER", "Chủ nhà thuốc", true); // lastOwner = true
        PermissionPageView pageView = new PermissionPageView(List.of(lastOwner), 0, 10, 1L, 1, null);

        when(ownerPermissionService.getPermissionPage(any(), anyInt(), anyInt()))
                .thenReturn(pageView);

        mvc.perform(get("/owner/permissions").with(as("OWNER", "Olivia Owner")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chủ nhà thuốc (duy nhất)"))) // locked badge
                // A locked row must NOT render an editable role dropdown/save form.
                .andExpect(content().string(not(containsString("name=\"role\""))));
    }

    @Test
    void nonOwnerIsForbiddenFromOwnerArea() throws Exception {
        mvc.perform(get("/owner/permissions").with(as("PHARMACIST", "Phong Pharmacist")))
                .andExpect(status().isForbidden());
    }

    /**
     * Covers the placeholder view + the "title comes from the menu config" lookup.
     *
     * <p>Was {@code /pharmacist/customers} until 2026-07-30; that route was deleted along with the
     * other seven dead placeholders once every menu item got a real screen. Retargeted rather than
     * dropped, because {@code /accountant/dashboard} is now the only route the placeholder serves —
     * so this is the last test standing between it and an unnoticed regression.</p>
     */
    @Test
    void accountantSeesRealDashboardWithAccountingSections()
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
                .andExpect(status().isOk())
                .andExpect(
                        view().name(
                                "dashboard/accountant-dashboard"
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Tổng quan thu - chi - công nợ trong tuần"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Cảnh báo kế toán"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                containsString(
                                        "Hoạt động gần đây"
                                )
                        )
                )
                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                "Màn hình chức năng này chưa được triển khai"
                                        )
                                )
                        )
                );
    }

    @Test
    void permissionPageEchoesSearch() throws Exception {
        mvc.perform(get("/owner/permissions").param("search", "pharmacist01").with(as("OWNER", "Olivia Owner")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("search", "pharmacist01"));
    }

    @Test
    void unauthenticatedUserIsSentToSignin() throws Exception {
        mvc.perform(get("/owner/permissions"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/signin"));
    }

    @Test
    void dashboardBridgeRedirectsToRoleDashboard() throws Exception {
        mvc.perform(get("/dashboard").with(as("PHARMACIST", "Phong Pharmacist")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/pharmacist/dashboard"));
    }
}
