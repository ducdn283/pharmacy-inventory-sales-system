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
 * Owner "account &rarr; role" permission logic over the existing {@code AccountPermission} table,
 * for the single-store {@code /owner/permissions} screen: which account holds which role,
 * system-wide. There are no branches any more — every operation reads/writes
 * {@code AccountPermission} directly, with no new tables.
 *
 * <p>Rules, validated here rather than by a DB constraint:</p>
 * <ul>
 *   <li>An account holds at most one role (one {@code AccountPermission} row).</li>
 *   <li>Assignable roles are exactly {@code PHARMACIST} and {@code ACCOUNTANT}, plus blank/"Không
 *       quyền" to clear the assignment. {@code OWNER} can never be assigned from this screen — the
 *       system has exactly one Owner, always (mirrors {@code OwnerUserService}'s refusal to create a
 *       second Owner account).</li>
 *   <li>The sole Owner's own row is read-only here (never editable/removable) — enforced by the
 *       existing "last Owner" guard below, which now always applies since a second Owner can never
 *       be created in the first place.</li>
 * </ul>
 * Friendly Vietnamese messages are thrown as {@link IllegalArgumentException} for the controller
 * to surface as flash messages.
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
     * Builds the whole screen state for one render: one page of account rows, each with its
     * system-wide role.
     *
     * <ul>
     *   <li>{@code search} matches account name / username / email.</li>
     *   <li>Pagination is by account row ({@code page} is 0-based, {@code size} defaults to 10).</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public PermissionPageView getPermissionPage(String search, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? DEFAULT_SIZE : size;
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);

        // accountId -> its single assignment (one-role-per-account).
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

        // Owner(s) always first, then assigned before unassigned, then by account name, then by id.
        allRows.sort(Comparator
                .comparing((PermissionAccountRow row) -> RoleConstants.OWNER.equals(row.getRole()) ? 0 : 1)
                .thenComparing(Comparator.comparing(PermissionAccountRow::isAssigned).reversed())
                .thenComparing(row -> normalizedName(row.getAccountName()))
                .thenComparing(row -> row.getAccountId() == null ? Integer.MAX_VALUE : row.getAccountId()));

        // In-memory pagination by account row (the account set is small).
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
     * Saves one account's system-wide role (a dropdown change on the Permission Table).
     *
     * <ul>
     *   <li>A blank/empty {@code role} clears the assignment: the existing
     *       {@code AccountPermission} row (if any) is deleted, meaning "Không quyền".</li>
     *   <li>{@code OWNER} is rejected outright — never assignable from here, see class javadoc.</li>
     *   <li>Only {@code PHARMACIST}/{@code ACCOUNTANT} can otherwise be assigned; anything else is
     *       rejected.</li>
     *   <li>If the account currently holds the Owner role, no change to it is ever allowed (it's
     *       the system's sole Owner, by construction) — surfaced via the pre-existing "last Owner"
     *       message so the wording stays familiar.</li>
     *   <li>Otherwise the role is created (with a manual id — the PK is not guaranteed
     *       auto-increment on every environment) or updated in place.</li>
     * </ul>
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

        // The sole Owner's row can never be changed from here — OWNER is never a valid target
        // above, so any existing Owner assignment is, by definition, always "the last one".
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
            // Manual id assignment, matching the existing findMaxId()+1 convention for this table.
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

    private void invalidateSessions(Integer accountId) {
        sessionRegistry.getAllPrincipals().stream()
                .filter(p -> p instanceof AccountPrincipal ap && ap.getAccountId().equals(accountId))
                .flatMap(p -> sessionRegistry.getAllSessions(p, false).stream())
                .forEach(SessionInformation::expireNow);
    }

    // ------------------------------------------------------------------

    /** Current assignment for an account, or {@code null} (one-role-per-account rule). */
    private Accountpermission findAssignment(Integer accountId) {
        List<Accountpermission> existing = accountpermissionRepository.findByAccountId(accountId);
        return existing.isEmpty() ? null : existing.get(0);
    }

    private Account requireAccount(Integer accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException(MSG_ACCOUNT_NOT_FOUND));
    }

    /** True for {@code OWNER}. */
    private boolean isOwnerLikeRole(String role) {
        if (role == null) {
            return false;
        }
        return RoleConstants.OWNER.equals(canonicalRole(role));
    }

    private String usernameOrEmail(Account account) {
        if (account.getUsername() != null && !account.getUsername().isBlank()) {
            return account.getUsername();
        }
        return account.getEmail() == null ? "" : account.getEmail();
    }

    private boolean matchesAccount(Account account, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        return contains(account.getName(), needle)
                || contains(account.getUsername(), needle)
                || contains(account.getEmail(), needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private String canonicalRole(String role) {
        if (role == null) {
            return null;
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }

    private String normalizedName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
