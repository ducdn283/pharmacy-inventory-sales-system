package com.example.project.service;

import com.example.project.constant.RoleConstants;
import com.example.project.entity.Account;
import com.example.project.entity.Accountpermission;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.AccountpermissionRepository;
import com.example.project.view.PermissionAccountRow;
import com.example.project.view.PermissionPageView;
import com.example.project.security.AccountPrincipal;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Xử lý gán vai trò (tài khoản → vai trò) cho màn hình "Bảng phân quyền"
 * ({@code /owner/permissions}). Đọc/ghi trực tiếp bảng {@code AccountPermission}.
 *
 * <p>Quy tắc nghiệp vụ:</p>
 * <ul>
 *   <li>Mỗi tài khoản chỉ giữ tối đa một vai trò.</li>
 *   <li>Chỉ được gán {@code PHARMACIST} hoặc {@code ACCOUNTANT} (hoặc để trống = "Không quyền").
 *       <b>Không được gán {@code OWNER} qua màn hình này</b> — hệ thống chỉ có đúng một Owner
 *       duy nhất, việc này ngăn lỗ hổng leo thang quyền (tạo thêm Owner thứ hai).</li>
 *   <li>Dòng của Owner duy nhất luôn readonly, không thể sửa/xóa qua bảng này.</li>
 * </ul>
 * Thông báo lỗi tiếng Việt được ném dưới dạng {@link IllegalArgumentException} để controller
 * hiển thị thành flash message.
 */
@Service
public class OwnerPermissionService {

    private static final int DEFAULT_SIZE = 10;

    private static final String MSG_MISSING_ACCOUNT = "Thiếu thông tin tài khoản";
    private static final String MSG_INVALID_ROLE = "Vai trò không hợp lệ";
    private static final String MSG_ACCOUNT_NOT_FOUND = "Không tìm thấy tài khoản đã chọn";
    private static final String MSG_LAST_OWNER =
            "Không thể thay đổi vì đây là Chủ nhà thuốc duy nhất trong hệ thống";
    private static final String MSG_CANNOT_ASSIGN_OWNER =
            "Không thể gán vai trò Chủ nhà thuốc — hệ thống chỉ có một Chủ nhà thuốc duy nhất";

    private final AccountpermissionRepository accountpermissionRepository;
    private final AccountRepository accountRepository;
    private final SessionRegistry sessionRegistry;

    public OwnerPermissionService(AccountpermissionRepository accountpermissionRepository,
                                  AccountRepository accountRepository,
                                  SessionRegistry sessionRegistry) {
        this.accountpermissionRepository = accountpermissionRepository;
        this.accountRepository = accountRepository;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Dựng dữ liệu cho một lần render màn hình: danh sách tài khoản kèm vai trò, có phân trang.
     * {@code search} tìm theo tên/tên đăng nhập/email; {@code page} 0-based, {@code size} mặc định 10.
     */
    @Transactional(readOnly = true)
    public PermissionPageView getPermissionPage(String search, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? DEFAULT_SIZE : size;
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);

        // accountId -> vai trò duy nhất của tài khoản đó
        Map<Integer, Accountpermission> assignmentByAccount = new HashMap<>();
        for (Accountpermission ap : accountpermissionRepository.findAllWithAccount()) {
            Account account = ap.getAccountID();
            if (account == null || account.getId() == null) {
                continue;
            }
            assignmentByAccount.putIfAbsent(account.getId(), ap);
        }

        int ownerLikeCount = (int) assignmentByAccount.values().stream()
                .filter(ap -> isOwnerLikeRole(ap.getRole()))
                .count();

        List<PermissionAccountRow> allRows = new ArrayList<>();
        for (Account account : accountRepository.findAll()) {
            if (account.getId() == null || !matchesAccount(account, needle)) {
                continue;
            }

            Accountpermission ap = assignmentByAccount.get(account.getId());
            String role = ap == null ? null : canonicalRole(ap.getRole());
            boolean lastOwner = isOwnerLikeRole(role) && ownerLikeCount <= 1;
            String roleDisplay = (role == null || role.isBlank())
                    ? "Không quyền"
                    : RoleConstants.vietnameseName(role);

            allRows.add(new PermissionAccountRow(
                    account.getId(), account.getName(), usernameOrEmail(account),
                    role, roleDisplay, lastOwner));
        }

        // Sắp xếp: Owner lên đầu, đã phân quyền trước chưa phân quyền, rồi theo tên, rồi theo id
        allRows.sort(Comparator
                .comparing((PermissionAccountRow row) -> RoleConstants.OWNER.equals(row.getRole()) ? 0 : 1)
                .thenComparing(Comparator.comparing(PermissionAccountRow::isAssigned).reversed())
                .thenComparing(row -> normalizedName(row.getAccountName()))
                .thenComparing(row -> row.getAccountId() == null ? Integer.MAX_VALUE : row.getAccountId()));

        // Phân trang trong bộ nhớ vì số lượng tài khoản nhỏ
        long totalElements = allRows.size();
        int totalPages = (int) Math.ceil((double) totalElements / safeSize);
        int boundedPage = totalPages == 0 ? 0 : Math.min(safePage, totalPages - 1);
        int fromIndex = boundedPage * safeSize;
        List<PermissionAccountRow> pageRows = fromIndex >= allRows.size()
                ? List.of()
                : List.copyOf(allRows.subList(fromIndex, Math.min(fromIndex + safeSize, allRows.size())));

        return new PermissionPageView(pageRows, boundedPage, safeSize, totalElements, totalPages, search);
    }

    /**
     * Lưu vai trò hệ thống của một tài khoản (đổi dropdown trên Bảng phân quyền).
     * {@code role} rỗng → xóa gán quyền hiện tại (= "Không quyền"). {@code OWNER} luôn bị từ chối
     * — không được gán qua màn hình này (xem javadoc của class). Chỉ {@code PHARMACIST}/
     * {@code ACCOUNTANT} được phép gán, giá trị khác bị từ chối. Nếu tài khoản đang là Owner thì
     * không cho đổi, vì đó là Owner duy nhất của hệ thống.
     */
    @Transactional
    public void saveRole(Integer accountId, String role) {
        if (accountId == null) {
            throw new IllegalArgumentException(MSG_MISSING_ACCOUNT);
        }

        Account account = requireAccount(accountId);

        String normalized = canonicalRole(role);
        boolean clearing = normalized == null || normalized.isBlank();
        if (!clearing && RoleConstants.OWNER.equals(normalized)) {
            throw new IllegalArgumentException(MSG_CANNOT_ASSIGN_OWNER);
        }
        if (!clearing && !RoleConstants.isPermissionTableRole(normalized)) {
            throw new IllegalArgumentException(MSG_INVALID_ROLE);
        }

        Accountpermission existing = findAssignment(accountId);
        boolean wasOwnerLike = existing != null && isOwnerLikeRole(existing.getRole());

        // Owner luôn là duy nhất nên dòng của Owner không bao giờ được đổi qua đây
        if (wasOwnerLike) {
            throw new IllegalArgumentException(MSG_LAST_OWNER);
        }

        if (clearing) {
            if (existing != null) {
                accountpermissionRepository.delete(existing);
                invalidateSessions(accountId);
            }
            return;
        }

        if (existing == null) {
            Accountpermission permission = new Accountpermission();
            // Gán id thủ công theo quy ước findMaxId()+1 của bảng này
            permission.setId(accountpermissionRepository.findMaxId() + 1);
            permission.setAccountID(account);
            permission.setRole(normalized);
            accountpermissionRepository.save(permission);
        } else {
            existing.setRole(normalized);
            accountpermissionRepository.save(existing);
        }
        invalidateSessions(accountId);
    }

    // Buộc đăng xuất mọi phiên đang đăng nhập của tài khoản này (áp dụng vai trò mới ngay lập tức).
    private void invalidateSessions(Integer accountId) {
        sessionRegistry.getAllPrincipals().stream()
                .filter(p -> p instanceof AccountPrincipal ap && ap.getAccountId().equals(accountId))
                .flatMap(p -> sessionRegistry.getAllSessions(p, false).stream())
                .forEach(SessionInformation::expireNow);
    }

    // ------------------------------------------------------------------

    /** Vai trò hiện tại của tài khoản, hoặc {@code null} nếu chưa có. */
    private Accountpermission findAssignment(Integer accountId) {
        List<Accountpermission> existing = accountpermissionRepository.findByAccountId(accountId);
        return existing.isEmpty() ? null : existing.get(0);
    }

    // Lấy tài khoản theo id, ném lỗi nếu không tồn tại.
    private Account requireAccount(Integer accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException(MSG_ACCOUNT_NOT_FOUND));
    }

    /** True nếu là vai trò {@code OWNER}. */
    private boolean isOwnerLikeRole(String role) {
        if (role == null) {
            return false;
        }
        return RoleConstants.OWNER.equals(canonicalRole(role));
    }

    // Ưu tiên hiển thị tên đăng nhập, nếu trống thì dùng email.
    private String usernameOrEmail(Account account) {
        if (account.getUsername() != null && !account.getUsername().isBlank()) {
            return account.getUsername();
        }
        return account.getEmail() == null ? "" : account.getEmail();
    }

    // True nếu tài khoản khớp từ khóa tìm kiếm theo tên/tên đăng nhập/email.
    private boolean matchesAccount(Account account, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        return contains(account.getName(), needle)
                || contains(account.getUsername(), needle)
                || contains(account.getEmail(), needle);
    }

    // So khớp chuỗi không phân biệt hoa/thường, an toàn với giá trị null.
    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    // Chuẩn hóa mã vai trò: bỏ khoảng trắng, viết hoa, bỏ tiền tố "ROLE_" nếu có.
    private String canonicalRole(String role) {
        if (role == null) {
            return null;
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }

    // Chuẩn hóa tên để so sánh/sắp xếp (trim + viết thường), an toàn với giá trị null.
    private String normalizedName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
