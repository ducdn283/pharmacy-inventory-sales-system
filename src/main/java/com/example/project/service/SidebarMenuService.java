package com.example.project.service;

import com.example.project.constant.RoleConstants;
import com.example.project.view.SidebarMenuGroup;
import com.example.project.view.SidebarMenuItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the sidebar menu for a given role from a fixed, code-defined configuration.
 *
 * <p>This is the single source of truth for role-based navigation. The menu is purely
 * presentational config (no database access) and its labels are Vietnamese; only the role
 * <em>codes</em> (OWNER, PHARMACIST, ...) stay in English. Each role maps to an ordered list of
 * {@link SidebarMenuGroup}s; the Thymeleaf {@code fragments/sidebar} renders whatever this
 * service returns for the current role.</p>
 *
 * <p>Detail/edit pages (e.g. {@code /owner/products/123}) are intentionally NOT listed as menu
 * items; they are highlighted under their parent list item via
 * {@link #resolveActiveUrl(List, String)} (longest-prefix match).</p>
 */
@Service
public class SidebarMenuService {

    /** Single-item "home" group; rendered as a plain link, so this label is not shown. */
    private static final String GROUP_MAIN = "Tổng quan";

    // Top-level icons shared across roles, so the same concept never picks up a different glyph
    // in one role's menu than in another's (2026-07-25 sidebar redesign).
    private static final String ICON_DASHBOARD = "ti ti-trending-up";
    private static final String ICON_TRANSACTIONS = "ti ti-file-text";
    private static final String ICON_PRODUCTS = "ti ti-package";
    private static final String ICON_SUPPLY = "ti ti-truck";
    private static final String ICON_WAREHOUSE = "ti ti-inbox";
    private static final String ICON_FINANCE = "ti ti-cash";
    private static final String ICON_CUSTOMER = "ti ti-user";
    private static final String ICON_SHIFT_REPORT = "ti ti-clipboard-data";

    private final Map<String, List<SidebarMenuGroup>> menusByRole = new LinkedHashMap<>();

    public SidebarMenuService() {
        menusByRole.put(RoleConstants.OWNER, ownerMenu());
        menusByRole.put(RoleConstants.PHARMACIST, pharmacistMenu());
        menusByRole.put(RoleConstants.ACCOUNTANT, accountantMenu());
    }

    /** Menu groups for the given role (falls back to the default role if unknown). */
    public List<SidebarMenuGroup> getMenu(String role) {
        String key = RoleConstants.isValid(role) ? role : RoleConstants.DEFAULT_ROLE;
        return menusByRole.getOrDefault(key, menusByRole.get(RoleConstants.DEFAULT_ROLE));
    }

    /**
     * Finds the menu item whose URL exactly matches the given request URI, across all roles.
     * Used by placeholder pages to label themselves from the same config.
     */
    public SidebarMenuItem findByUri(String uri) {
        if (uri == null) {
            return null;
        }
        for (List<SidebarMenuGroup> groups : menusByRole.values()) {
            for (SidebarMenuGroup group : groups) {
                for (SidebarMenuItem item : group.getItems()) {
                    if (item.getUrl().equals(uri)) {
                        return item;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns the URL of the menu item that should be highlighted for the current URI, or
     * {@code null} if none. An item matches when the URI equals its URL or is a sub-path of it
     * (segment boundary); the longest such URL wins, so detail pages highlight their parent.
     */
    public String resolveActiveUrl(List<SidebarMenuGroup> menu, String uri) {
        if (menu == null || uri == null) {
            return null;
        }
        if (uri.startsWith("/owner/producers/create-producer")
                || uri.startsWith("/owner/producers/update-producer")) {
            uri = "/owner/producers";
        }
        if (uri.startsWith("/owner/types/create-type")
                || uri.startsWith("/owner/types/update-type")) {
            uri = "/owner/types";
        }
        if (uri.startsWith("/owner/positions/create-position")
                || uri.startsWith("/owner/positions/update-position")) {
            uri = "/owner/positions";
        }
        if (uri.startsWith("/owner/procurements/create-procurementplan")
                || uri.startsWith("/owner/procurements/update-procurementplan")
                || uri.startsWith("/owner/procurements/view-detail")) {
            uri = "/owner/procurements";
        }
        String best = null;
        for (SidebarMenuGroup group : menu) {
            for (SidebarMenuItem item : group.getItems()) {
                String url = item.getUrl();
                boolean matches = uri.equals(url) || uri.startsWith(url + "/");
                if (matches && (best == null || url.length() > best.length())) {
                    best = url;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Per-role menu configuration (Vietnamese labels)
    // ------------------------------------------------------------------

    private List<SidebarMenuGroup> ownerMenu() {
        return List.of(
                linkGroup(GROUP_MAIN, ICON_DASHBOARD,
                        i("Tổng quan", "/owner/dashboard", ICON_DASHBOARD)),

                menuGroup("Giao dịch", ICON_TRANSACTIONS,
                        i("Bán hàng", "/owner/selling", "ti ti-shopping-cart"),
                        i("Danh sách hóa đơn", "/owner/invoices", ICON_TRANSACTIONS),
                        i("Danh sách trả hàng", "/owner/returns", "ti ti-rotate")),

                menuGroup("Quản trị", "ti ti-settings",
                        i("Danh sách người dùng", "/owner/users", "ti ti-users"),
                        i("Bảng phân quyền", "/owner/permissions", "ti ti-shield-lock")),

                menuGroup("Hàng hóa", ICON_PRODUCTS,
                        i("Danh sách hàng hóa", "/owner/products", ICON_PRODUCTS),
                        i("Danh sách loại hàng", "/owner/types", "ti ti-category"),
                        i("Danh sách nhà sản xuất", "/owner/producers", "ti ti-building-factory-2"),
                        i("Danh sách vị trí", "/owner/positions", "ti ti-map-pin")),

                menuGroup("Cung ứng", ICON_SUPPLY,
                        i("Danh sách nhà cung cấp", "/supplier", ICON_SUPPLY),
                        i("Danh sách phiếu nhập", "/owner/purchase-invoices", "ti ti-receipt"),
                        i("Danh sách dự trù", "/owner/procurements", "ti ti-clipboard-list"),
                        i("Danh sách trả hàng NCC", "/owner/return-purchases", "ti ti-rotate-2")),

                menuGroup("Kho", ICON_WAREHOUSE,
                        i("Danh sách điều chỉnh tồn", "/owner/stock-adjustments", ICON_WAREHOUSE),
                        i("Danh sách kiểm kho", "/owner/stock-counts", "ti ti-clipboard-list")),

                menuGroup("Tài chính", ICON_FINANCE,
                        i("Danh sách công nợ", "/owner/debts", "ti ti-credit-card"),
                        i("Danh sách khoản thu", "/owner/incomes", ICON_FINANCE),
                        i("Danh sách khoản chi", "/owner/expenses", "ti ti-cash"),
                        i("Kỳ thuế", "/owner/tax-periods", "ti ti-receipt-tax"),
                        i("Thiết lập giá", "/owner/price-settings", "ti ti-tag"),
                        i("Thiết lập tài chính", "/owner/financial-setting", "ti ti-settings")),

                linkGroup("Khách hàng", ICON_CUSTOMER,
                        i("Khách hàng", "/customer", ICON_CUSTOMER)),

                linkGroup("Báo cáo ca", ICON_SHIFT_REPORT,
                        i("Báo cáo ca", "/owner/shift-reports", ICON_SHIFT_REPORT)),

                linkGroup("Phê duyệt", "ti ti-clipboard-check",
                        i("Phê duyệt", "/owner/approvals", "ti ti-clipboard-check"))
        );
    }

    private List<SidebarMenuGroup> pharmacistMenu() {
        return List.of(
                linkGroup(GROUP_MAIN, ICON_DASHBOARD,
                        i("Tổng quan", "/pharmacist/dashboard", ICON_DASHBOARD)),

                linkGroup("Bán hàng", "ti ti-shopping-cart",
                        i("Bán hàng", "/pharmacist/selling", "ti ti-shopping-cart")),

                menuGroup("Giao dịch", ICON_TRANSACTIONS,
                        i("Danh sách hóa đơn", "/pharmacist/invoices", ICON_TRANSACTIONS),
                        i("Danh sách trả hàng", "/pharmacist/returns", "ti ti-rotate")),

                linkGroup("Hàng hóa", ICON_PRODUCTS,
                        i("Hàng hóa", "/pharmacist/products", ICON_PRODUCTS)),

                menuGroup("Kho", ICON_WAREHOUSE,
                        i("Danh sách kiểm kho", "/pharmacist/stock-counts", "ti ti-clipboard-list")),

                menuGroup("Tài chính", ICON_FINANCE,
                        i("Danh sách khoản thu", "/pharmacist/incomes", ICON_FINANCE)),

                linkGroup("Khách hàng", ICON_CUSTOMER,
                        i("Khách hàng", "/customer", ICON_CUSTOMER)),

                linkGroup("Báo cáo ca", ICON_SHIFT_REPORT,
                        i("Báo cáo ca", "/pharmacist/shift-reports", ICON_SHIFT_REPORT))
        );
    }

    private List<SidebarMenuGroup> accountantMenu() {
        return List.of(
                linkGroup(GROUP_MAIN, ICON_DASHBOARD,
                        i("Tổng quan", "/accountant/dashboard", ICON_DASHBOARD)),

                menuGroup("Tài chính", ICON_FINANCE,
                        i("Danh sách công nợ", "/accountant/debts", "ti ti-credit-card"),
                        i("Danh sách khoản thu", "/accountant/incomes", ICON_FINANCE),
                        i("Danh sách khoản chi", "/accountant/expenses", "ti ti-cash"),
                        i("Kỳ thuế", "/accountant/tax-periods", "ti ti-receipt-tax")),

                menuGroup("Cung ứng", ICON_SUPPLY,
                        i("Danh sách phiếu nhập", "/accountant/purchase-invoices", "ti ti-receipt")),

                linkGroup("Khách hàng", ICON_CUSTOMER,
                        i("Khách hàng", "/customer", ICON_CUSTOMER)),

                menuGroup("Giao dịch", ICON_TRANSACTIONS,
                        i("Danh sách hóa đơn", "/accountant/invoices", ICON_TRANSACTIONS),
                        i("Danh sách trả hàng", "/accountant/returns", "ti ti-rotate"))
        );
    }

    // ------------------------------------------------------------------
    // Small builders: an item's "module" is auto-set to its group's label.
    // ------------------------------------------------------------------

    private SidebarMenuGroup linkGroup(String label, String icon, ItemSpec... specs) {
        return buildGroup(label, icon, false, specs);
    }

    private SidebarMenuGroup menuGroup(String label, String icon, ItemSpec... specs) {
        return buildGroup(label, icon, true, specs);
    }

    private SidebarMenuGroup buildGroup(String label, String icon, boolean collapsible, ItemSpec... specs) {
        List<SidebarMenuItem> items = new ArrayList<>(specs.length);
        for (ItemSpec spec : specs) {
            items.add(new SidebarMenuItem(spec.label(), spec.url(), spec.icon(), label));
        }
        return new SidebarMenuGroup(label, icon, List.copyOf(items), collapsible);
    }

    private static ItemSpec i(String label, String url, String icon) {
        return new ItemSpec(label, url, icon);
    }

    private record ItemSpec(String label, String url, String icon) {
    }
}
