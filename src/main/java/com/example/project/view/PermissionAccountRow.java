package com.example.project.view;

import lombok.Getter;

/**
 * Một dòng trong Bảng phân quyền: tài khoản kèm vai trò hệ thống duy nhất của nó. Được dựng bên
 * trong transaction để view Thymeleaf không đụng tới lazy association.
 *
 * <p>{@link #role} null/rỗng nghĩa là "Không quyền". {@link #lastOwner} = {@code true} khi tài
 * khoản này là Owner duy nhất còn lại trong hệ thống — dòng này sẽ hiển thị readonly để không
 * thể hạ quyền xuống 0 Owner.</p>
 */
@Getter
public class PermissionAccountRow {

    private final Integer accountId;
    private final String accountName;
    private final String usernameOrEmail;
    private final String role;          // mã vai trò, vd "PHARMACIST"/"OWNER"; null nếu chưa gán
    private final String roleDisplay;   // nhãn tiếng Việt, hoặc "Không quyền" nếu chưa gán
    private final boolean lastOwner;    // Owner duy nhất còn lại — không cho hạ quyền

    public PermissionAccountRow(Integer accountId, String accountName, String usernameOrEmail,
                                String role, String roleDisplay, boolean lastOwner) {
        this.accountId = accountId;
        this.accountName = accountName;
        this.usernameOrEmail = usernameOrEmail;
        this.role = role;
        this.roleDisplay = roleDisplay;
        this.lastOwner = lastOwner;
    }

    /** True khi tài khoản đang giữ một vai trò. */
    public boolean isAssigned() {
        return role != null && !role.isBlank();
    }
}
