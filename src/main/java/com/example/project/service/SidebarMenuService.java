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
 * Xây dựng sidebar theo từng vai trò.
 */
@Service
public class SidebarMenuService {

    private static final String GROUP_MAIN =
            "Tổng quan";

    private static final String ICON_DASHBOARD =
            "ti ti-trending-up";

    private static final String ICON_TRANSACTIONS =
            "ti ti-file-text";

    private static final String ICON_PRODUCTS =
            "ti ti-package";

    private static final String ICON_SUPPLY =
            "ti ti-truck";

    private static final String ICON_WAREHOUSE =
            "ti ti-inbox";

    private static final String ICON_FINANCE =
            "ti ti-cash";

    private static final String ICON_CUSTOMER =
            "ti ti-user";

    private static final String ICON_SHIFT_REPORT =
            "ti ti-clipboard-data";

    private static final String ICON_NOTIFICATION =
            "ti ti-bell";

    private final Map<String, List<SidebarMenuGroup>>
            menusByRole = new LinkedHashMap<>();

    public SidebarMenuService() {
        menusByRole.put(
                RoleConstants.OWNER,
                ownerMenu()
        );

        menusByRole.put(
                RoleConstants.PHARMACIST,
                pharmacistMenu()
        );

        menusByRole.put(
                RoleConstants.ACCOUNTANT,
                accountantMenu()
        );
    }

    public List<SidebarMenuGroup> getMenu(
            String role
    ) {
        String key = RoleConstants.isValid(role)
                ? role
                : RoleConstants.DEFAULT_ROLE;

        return menusByRole.getOrDefault(
                key,
                menusByRole.get(
                        RoleConstants.DEFAULT_ROLE
                )
        );
    }

    public SidebarMenuItem findByUri(
            String uri
    ) {
        if (uri == null) {
            return null;
        }

        for (List<SidebarMenuGroup> groups
                : menusByRole.values()) {

            for (SidebarMenuGroup group : groups) {
                for (SidebarMenuItem item
                        : group.getItems()) {

                    if (item.getUrl().equals(uri)) {
                        return item;
                    }
                }
            }
        }

        return null;
    }

    public String resolveActiveUrl(
            List<SidebarMenuGroup> menu,
            String uri
    ) {
        if (menu == null || uri == null) {
            return null;
        }

        if (uri.startsWith(
                "/owner/producers/create-producer"
        )
                || uri.startsWith(
                "/owner/producers/update-producer"
        )) {
            uri = "/owner/producers";
        }

        if (uri.startsWith(
                "/owner/types/create-type"
        )
                || uri.startsWith(
                "/owner/types/update-type"
        )) {
            uri = "/owner/types";
        }

        if (uri.startsWith(
                "/owner/positions/create-position"
        )
                || uri.startsWith(
                "/owner/positions/update-position"
        )) {
            uri = "/owner/positions";
        }

        if (uri.startsWith(
                "/owner/procurements/create-procurementplan"
        )
                || uri.startsWith(
                "/owner/procurements/update-procurementplan"
        )
                || uri.startsWith(
                "/owner/procurements/view-detail"
        )) {
            uri = "/owner/procurements";
        }

        String best = null;

        for (SidebarMenuGroup group : menu) {
            for (SidebarMenuItem item
                    : group.getItems()) {

                String url = item.getUrl();

                boolean matches =
                        uri.equals(url)
                                || uri.startsWith(
                                url + "/"
                        );

                if (matches
                        && (
                        best == null
                                || url.length()
                                > best.length()
                )) {
                    best = url;
                }
            }
        }

        return best;
    }

    private List<SidebarMenuGroup> ownerMenu() {
        return List.of(
                linkGroup(
                        GROUP_MAIN,
                        ICON_DASHBOARD,
                        i(
                                "Tổng quan",
                                "/owner/dashboard",
                                ICON_DASHBOARD
                        )
                ),

                linkGroup(
                        "Thông báo",
                        ICON_NOTIFICATION,
                        i(
                                "Thông báo",
                                "/owner/notifications",
                                ICON_NOTIFICATION
                        )
                ),

                menuGroup(
                        "Giao dịch",
                        ICON_TRANSACTIONS,
                        i(
                                "Bán hàng",
                                "/owner/selling",
                                "ti ti-shopping-cart"
                        ),
                        i(
                                "Danh sách hóa đơn",
                                "/owner/invoices",
                                ICON_TRANSACTIONS
                        ),
                        i(
                                "Danh sách trả hàng",
                                "/owner/returns",
                                "ti ti-rotate"
                        )
                ),

                menuGroup(
                        "Quản trị",
                        "ti ti-settings",
                        i(
                                "Danh sách người dùng",
                                "/owner/users",
                                "ti ti-users"
                        ),
                        i(
                                "Bảng phân quyền",
                                "/owner/permissions",
                                "ti ti-shield-lock"
                        )
                ),

                menuGroup(
                        "Hàng hóa",
                        ICON_PRODUCTS,
                        i(
                                "Danh sách hàng hóa",
                                "/owner/products",
                                ICON_PRODUCTS
                        ),
                        i(
                                "Danh sách loại hàng",
                                "/owner/types",
                                "ti ti-category"
                        ),
                        i(
                                "Danh sách nhà sản xuất",
                                "/owner/producers",
                                "ti ti-building-factory-2"
                        ),
                        i(
                                "Danh sách vị trí",
                                "/owner/positions",
                                "ti ti-map-pin"
                        )
                ),

                menuGroup(
                        "Cung ứng",
                        ICON_SUPPLY,
                        i(
                                "Danh sách nhà cung cấp",
                                "/supplier",
                                ICON_SUPPLY
                        ),
                        i(
                                "Danh sách phiếu nhập",
                                "/owner/purchase-invoices",
                                "ti ti-receipt"
                        ),
                        i(
                                "Danh sách dự trù",
                                "/owner/procurements",
                                "ti ti-clipboard-list"
                        ),
                        i(
                                "Danh sách trả hàng NCC",
                                "/owner/return-purchases",
                                "ti ti-rotate-2"
                        )
                ),

                menuGroup(
                        "Kho",
                        ICON_WAREHOUSE,
                        i(
                                "Danh sách điều chỉnh kho",
                                "/owner/stock-adjustments",
                                ICON_WAREHOUSE
                        ),
                        i(
                                "Rà soát kho",
                                "/owner/stock-reviews",
                                "ti ti-clipboard-list"
                        )
                ),

                menuGroup(
                        "Tài chính",
                        ICON_FINANCE,
                        i(
                                "Danh sách công nợ",
                                "/owner/debts",
                                "ti ti-credit-card"
                        ),
                        i(
                                "Danh sách khoản thu",
                                "/owner/incomes",
                                ICON_FINANCE
                        ),
                        i(
                                "Danh sách khoản chi",
                                "/owner/expenses",
                                "ti ti-cash"
                        ),
                        i(
                                "Kỳ thuế",
                                "/owner/tax-periods",
                                "ti ti-receipt-tax"
                        ),
                        i(
                                "Thiết lập giá",
                                "/owner/price-settings",
                                "ti ti-tag"
                        ),
                        i(
                                "Thiết lập tài chính",
                                "/owner/financial-setting",
                                "ti ti-settings"
                        )
                ),

                linkGroup(
                        "Khách hàng",
                        ICON_CUSTOMER,
                        i(
                                "Khách hàng",
                                "/customer",
                                ICON_CUSTOMER
                        )
                ),

                linkGroup(
                        "Báo cáo ca",
                        ICON_SHIFT_REPORT,
                        i(
                                "Báo cáo ca",
                                "/owner/shift-reports",
                                ICON_SHIFT_REPORT
                        )
                ),

                linkGroup(
                        "Phê duyệt",
                        "ti ti-clipboard-check",
                        i(
                                "Phê duyệt",
                                "/owner/approvals",
                                "ti ti-clipboard-check"
                        )
                )
        );
    }

    private List<SidebarMenuGroup> pharmacistMenu() {
        return List.of(
                linkGroup(
                        GROUP_MAIN,
                        ICON_DASHBOARD,
                        i(
                                "Tổng quan",
                                "/pharmacist/dashboard",
                                ICON_DASHBOARD
                        )
                ),

                linkGroup(
                        "Thông báo",
                        ICON_NOTIFICATION,
                        i(
                                "Thông báo",
                                "/pharmacist/notifications",
                                ICON_NOTIFICATION
                        )
                ),

                linkGroup(
                        "Bán hàng",
                        "ti ti-shopping-cart",
                        i(
                                "Bán hàng",
                                "/pharmacist/selling",
                                "ti ti-shopping-cart"
                        )
                ),

                menuGroup(
                        "Giao dịch",
                        ICON_TRANSACTIONS,
                        i(
                                "Danh sách hóa đơn",
                                "/pharmacist/invoices",
                                ICON_TRANSACTIONS
                        ),
                        i(
                                "Danh sách trả hàng",
                                "/pharmacist/returns",
                                "ti ti-rotate"
                        )
                ),

                menuGroup(
                        "Hàng hóa",
                        ICON_PRODUCTS,
                        i(
                                "Hàng hóa",
                                "/pharmacist/products",
                                ICON_PRODUCTS
                        ),
                        i(
                                "Danh sách vị trí",
                                "/pharmacist/positions",
                                "ti ti-map-pin"
                        )
                ),

                menuGroup(
                        "Kho",
                        ICON_WAREHOUSE,
                        i(
                                "Rà soát kho",
                                "/pharmacist/stock-reviews",
                                "ti ti-clipboard-list"
                        )
                ),

                menuGroup(
                        "Tài chính",
                        ICON_FINANCE,
                        i(
                                "Danh sách khoản thu",
                                "/pharmacist/incomes",
                                ICON_FINANCE
                        ),
                        i(
                                "Danh sách khoản chi",
                                "/pharmacist/expenses",
                                "ti ti-cash"
                        )
                ),

                linkGroup(
                        "Khách hàng",
                        ICON_CUSTOMER,
                        i(
                                "Khách hàng",
                                "/customer",
                                ICON_CUSTOMER
                        )
                ),

                linkGroup(
                        "Báo cáo ca",
                        ICON_SHIFT_REPORT,
                        i(
                                "Báo cáo ca",
                                "/pharmacist/shift-reports",
                                ICON_SHIFT_REPORT
                        )
                )
        );
    }

    private List<SidebarMenuGroup> accountantMenu() {
        return List.of(
                linkGroup(
                        GROUP_MAIN,
                        ICON_DASHBOARD,
                        i(
                                "Tổng quan",
                                "/accountant/dashboard",
                                ICON_DASHBOARD
                        )
                ),

                linkGroup(
                        "Thông báo",
                        ICON_NOTIFICATION,
                        i(
                                "Thông báo",
                                "/accountant/notifications",
                                ICON_NOTIFICATION
                        )
                ),

                menuGroup(
                        "Tài chính",
                        ICON_FINANCE,
                        i(
                                "Danh sách công nợ",
                                "/accountant/debts",
                                "ti ti-credit-card"
                        ),
                        i(
                                "Danh sách khoản thu",
                                "/accountant/incomes",
                                ICON_FINANCE
                        ),
                        i(
                                "Danh sách khoản chi",
                                "/accountant/expenses",
                                "ti ti-cash"
                        ),
                        i(
                                "Kỳ thuế",
                                "/accountant/tax-periods",
                                "ti ti-receipt-tax"
                        )
                ),

                linkGroup("Hàng hóa", ICON_PRODUCTS,
                        i("Hàng hóa", "/accountant/products", ICON_PRODUCTS)),

                menuGroup(
                        "Cung ứng",
                        ICON_SUPPLY,
                        i(
                                "Danh sách nhà cung cấp",
                                "/supplier",
                                ICON_SUPPLY
                        ),
                        i(
                                "Danh sách phiếu nhập",
                                "/accountant/purchase-invoices",
                                "ti ti-receipt"
                        ),
                        i(
                                "Danh sách dự trù",
                                "/accountant/procurements",
                                "ti ti-clipboard-list"
                        )
                ),

                linkGroup(
                        "Khách hàng",
                        ICON_CUSTOMER,
                        i(
                                "Khách hàng",
                                "/customer",
                                ICON_CUSTOMER
                        )
                ),

                menuGroup(
                        "Giao dịch",
                        ICON_TRANSACTIONS,
                        i(
                                "Danh sách hóa đơn",
                                "/accountant/invoices",
                                ICON_TRANSACTIONS
                        ),
                        i(
                                "Danh sách trả hàng",
                                "/accountant/returns",
                                "ti ti-rotate"
                        )
                ),

                linkGroup(
                        "Báo cáo ca",
                        ICON_SHIFT_REPORT,
                        i(
                                "Báo cáo ca",
                                "/accountant/shift-reports",
                                ICON_SHIFT_REPORT
                        )
                )
        );
    }

    private SidebarMenuGroup linkGroup(
            String label,
            String icon,
            ItemSpec... specs
    ) {
        return buildGroup(
                label,
                icon,
                false,
                specs
        );
    }

    private SidebarMenuGroup menuGroup(
            String label,
            String icon,
            ItemSpec... specs
    ) {
        return buildGroup(
                label,
                icon,
                true,
                specs
        );
    }

    private SidebarMenuGroup buildGroup(
            String label,
            String icon,
            boolean collapsible,
            ItemSpec... specs
    ) {
        List<SidebarMenuItem> items =
                new ArrayList<>(specs.length);

        for (ItemSpec spec : specs) {
            items.add(
                    new SidebarMenuItem(
                            spec.label(),
                            spec.url(),
                            spec.icon(),
                            label
                    )
            );
        }

        return new SidebarMenuGroup(
                label,
                icon,
                List.copyOf(items),
                collapsible
        );
    }

    private static ItemSpec i(
            String label,
            String url,
            String icon
    ) {
        return new ItemSpec(
                label,
                url,
                icon
        );
    }

    private record ItemSpec(
            String label,
            String url,
            String icon
    ) {
    }
}
