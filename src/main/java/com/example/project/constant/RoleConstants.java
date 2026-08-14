package com.example.project.constant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central definition of the valid role values used across the application.
 *
 * <p>The conceptual source of truth for a user's role is
 * {@code Accountpermission.role} (a {@code varchar(50)} in the database). There is
 * intentionally no Role / Permission / RolePermission table — roles are a fixed,
 * code-defined set. These constants exist so role names are written in exactly one
 * place and never drift between the sidebar, the permission matrix and (later) the
 * login module.</p>
 */
public final class RoleConstants {

    public static final String OWNER = "OWNER";
    public static final String PHARMACIST = "PHARMACIST";
    public static final String ACCOUNTANT = "ACCOUNTANT";

    /** All valid roles, in display order. */
    public static final List<String> ALL = List.of(
            OWNER, PHARMACIST, ACCOUNTANT);

    public static final List<String> NON_OWNER_ROLES = List.of(
            PHARMACIST, ACCOUNTANT
    );

    /**
     * Các vai trò có thể gán qua Bảng phân quyền ({@code /owner/permissions}), theo thứ tự hiển thị.
     * {@code OWNER} bị loại trừ có chủ đích — hệ thống chỉ có đúng một Owner duy nhất, Bảng phân
     * quyền chỉ được chuyển tài khoản giữa {@code PHARMACIST}/{@code ACCOUNTANT}/Không quyền,
     * không bao giờ được thăng cấp ai lên Owner.
     */
    public static final List<String> PERMISSION_TABLE_ROLES = List.of(PHARMACIST, ACCOUNTANT);

    /**
     * Safe fallback used only when an <em>invalid</em> role string is supplied to a lookup
     * (e.g. an unknown {@code ?role=} value on the Permission Table, or a menu/matrix lookup
     * for an unrecognised role). It is NOT used to resolve the signed-in user's role — that
     * always comes from the authenticated {@code AccountPrincipal}; an unauthenticated request
     * gets no role and no sidebar.
     */
    public static final String DEFAULT_ROLE = OWNER;

    private RoleConstants() {
    }

    /** True nếu chuỗi truyền vào là một vai trò hợp lệ (có trong {@link #ALL}). */
    public static boolean isValid(String role) {
        return role != null && ALL.contains(role);
    }

    /** Human-friendly label, e.g. {@code ACCOUNTANT -> "Accountant"}. */
    public static String displayName(String role) {
        if (role == null || role.isBlank()) {
            return "";
        }
        String[] parts = role.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)))
              .append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    /** Vietnamese display label for a role, e.g. {@code ACCOUNTANT -> "Kế toán"}. */
    public static String vietnameseName(String role) {
        if (role == null) {
            return "";
        }
        return switch (role) {
            case OWNER -> "Chủ nhà thuốc";
            case PHARMACIST -> "Dược sĩ";
            case ACCOUNTANT -> "Kế toán";
            default -> role;
        };
    }

    /** Ordered map of role code -> Vietnamese label, for building role dropdowns. */
    public static Map<String, String> vietnameseLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String role : ALL) {
            labels.put(role, vietnameseName(role));
        }
        return labels;
    }

    /** Map mã vai trò -> nhãn tiếng Việt, chỉ gồm các vai trò không phải Owner. */
    public static Map<String, String> nonOwnerVietnameseLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String role : NON_OWNER_ROLES) {
            labels.put(role, vietnameseName(role));
        }
        return labels;
    }

    /** {@code ""} ("Không quyền") cùng các vai trò có thể gán qua Bảng phân quyền, theo thứ tự dropdown. */
    public static Map<String, String> permissionTableRoleLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("", "Không quyền");
        for (String role : PERMISSION_TABLE_ROLES) {
            labels.put(role, vietnameseName(role));
        }
        return labels;
    }

    /** True nếu vai trò này có thể gán qua Bảng phân quyền (trường hợp rỗng/Không quyền xử lý riêng). */
    public static boolean isPermissionTableRole(String role) {
        return role != null && PERMISSION_TABLE_ROLES.contains(role);
    }

    /** URL slug for a role, e.g. {@code ACCOUNTANT -> "accountant"}. */
    public static String urlPrefix(String role) {
        if (role == null) {
            return "";
        }
        return role.toLowerCase().replace('_', '-');
    }

    /** Landing page for a role, e.g. {@code OWNER -> "/owner/dashboard"}. */
    public static String dashboardPath(String role) {
        String safe = isValid(role) ? role : DEFAULT_ROLE;
        return "/" + urlPrefix(safe) + "/dashboard";
    }
}
