package com.example.project.service;

import com.example.project.constant.NotificationCategory;
import com.example.project.constant.NotificationReferenceType;
import com.example.project.constant.NotificationSeverity;
import com.example.project.constant.NotificationType;
import com.example.project.constant.RoleConstants;
import com.example.project.entity.Account;
import com.example.project.entity.Expense;
import com.example.project.entity.Income;
import com.example.project.entity.Return;
import com.example.project.entity.Shiftreport;
import com.example.project.entity.Stockreview;
import com.example.project.repository.AccountpermissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class WorkflowNotificationService {

    private static final Locale VIETNAM =
            Locale.forLanguageTag("vi-VN");

    private final AccountpermissionRepository
            accountpermissionRepository;

    private final NotificationService notificationService;

    public WorkflowNotificationService(
            AccountpermissionRepository accountpermissionRepository,
            NotificationService notificationService
    ) {
        this.accountpermissionRepository =
                accountpermissionRepository;

        this.notificationService = notificationService;
    }

    // =========================================================
    // RETURN
    // =========================================================

    @Transactional
    public void returnPending(Return value) {
        if (value == null || value.getId() == null) {
            return;
        }

        String code = code(
                value.getReturnCode(),
                value.getId(),
                "TH"
        );

        pendingToOwners(
                "Phiếu trả hàng " + code + " cần duyệt",
                accountName(value.getReturnedBy())
                        + " đã gửi phiếu trả hàng "
                        + code
                        + " với số tiền hoàn "
                        + money(value.getTotalRefund())
                        + ".",
                NotificationType.RETURN_PENDING,
                NotificationReferenceType.RETURN,
                value.getId(),
                "/owner/returns/" + value.getId(),
                "RETURN_PENDING_" + value.getId()
        );
    }

    @Transactional
    public void returnApproved(Return value) {
        if (value == null || value.getId() == null) {
            return;
        }

        resolveAndSendResult(
                value.getReturnedBy(),
                "Phiếu trả hàng "
                        + code(
                        value.getReturnCode(),
                        value.getId(),
                        "TH"
                )
                        + " đã được duyệt",
                "Phiếu trả hàng của bạn đã được duyệt "
                        + "và chuyển sang công nợ hoàn khách.",
                NotificationType.RETURN_APPROVED,
                NotificationReferenceType.RETURN,
                value.getId(),
                "returns",
                NotificationSeverity.INFO,
                "RETURN_APPROVED_" + value.getId()
        );
    }

    @Transactional
    public void returnRejected(Return value) {
        if (value == null || value.getId() == null) {
            return;
        }

        resolveAndSendResult(
                value.getReturnedBy(),
                "Phiếu trả hàng "
                        + code(
                        value.getReturnCode(),
                        value.getId(),
                        "TH"
                )
                        + " bị từ chối",
                "Phiếu trả hàng của bạn đã bị từ chối. "
                        + "Vui lòng mở phiếu để kiểm tra lại.",
                NotificationType.RETURN_REJECTED,
                NotificationReferenceType.RETURN,
                value.getId(),
                "returns",
                NotificationSeverity.WARNING,
                "RETURN_REJECTED_" + value.getId()
        );
    }

    // =========================================================
    // EXPENSE
    // =========================================================

    @Transactional
    public void expensePending(Expense value) {
        if (value == null || value.getId() == null) {
            return;
        }

        String code = code(
                value.getExpenseCode(),
                value.getId(),
                "PC"
        );

        pendingToOwners(
                "Phiếu chi " + code + " cần duyệt",
                accountName(value.getApplicantID())
                        + " đã gửi phiếu chi "
                        + code
                        + " với số tiền "
                        + money(value.getAmount())
                        + ".",
                NotificationType.EXPENSE_PENDING,
                NotificationReferenceType.EXPENSE,
                value.getId(),
                "/owner/expenses/" + value.getId(),
                "EXPENSE_PENDING_" + value.getId()
        );
    }

    @Transactional
    public void expenseApproved(Expense value) {
        if (value == null || value.getId() == null) {
            return;
        }

        resolveAndSendResult(
                value.getApplicantID(),
                "Phiếu chi "
                        + code(
                        value.getExpenseCode(),
                        value.getId(),
                        "PC"
                )
                        + " đã được duyệt",
                "Phiếu chi "
                        + money(value.getAmount())
                        + " của bạn đã được duyệt.",
                NotificationType.EXPENSE_APPROVED,
                NotificationReferenceType.EXPENSE,
                value.getId(),
                "expenses",
                NotificationSeverity.INFO,
                "EXPENSE_APPROVED_" + value.getId()
        );
    }

    @Transactional
    public void expenseRejected(Expense value) {
        if (value == null || value.getId() == null) {
            return;
        }

        resolveAndSendResult(
                value.getApplicantID(),
                "Phiếu chi "
                        + code(
                        value.getExpenseCode(),
                        value.getId(),
                        "PC"
                )
                        + " bị từ chối",
                "Phiếu chi của bạn đã bị từ chối. "
                        + "Vui lòng mở phiếu để kiểm tra lại.",
                NotificationType.EXPENSE_REJECTED,
                NotificationReferenceType.EXPENSE,
                value.getId(),
                "expenses",
                NotificationSeverity.WARNING,
                "EXPENSE_REJECTED_" + value.getId()
        );
    }

    // =========================================================
    // STOCK REVIEW
    // =========================================================

    @Transactional
    public void stockReviewPending(Stockreview value) {
        if (value == null || value.getId() == null) {
            return;
        }

        String code = code(
                value.getStockCountCode(),
                value.getId(),
                "SR"
        );

        pendingToOwners(
                "Phiếu rà soát kho "
                        + code
                        + " cần duyệt",
                accountName(value.getCreatedBy())
                        + " đã gửi phiếu rà soát kho "
                        + code
                        + ".",
                NotificationType.STOCK_REVIEW_PENDING,
                NotificationReferenceType.STOCK_REVIEW,
                value.getId(),
                "/owner/stock-reviews/" + value.getId(),
                "STOCK_REVIEW_PENDING_" + value.getId()
        );
    }

    @Transactional
    public void stockReviewApproved(Stockreview value) {
        if (value == null || value.getId() == null) {
            return;
        }

        resolveAndSendResult(
                value.getCreatedBy(),
                "Phiếu rà soát kho "
                        + code(
                        value.getStockCountCode(),
                        value.getId(),
                        "SR"
                )
                        + " đã được duyệt",
                "Phiếu rà soát kho của bạn đã được duyệt.",
                NotificationType.STOCK_REVIEW_APPROVED,
                NotificationReferenceType.STOCK_REVIEW,
                value.getId(),
                "stock-reviews",
                NotificationSeverity.INFO,
                "STOCK_REVIEW_APPROVED_" + value.getId()
        );
    }

    @Transactional
    public void stockReviewRejected(Stockreview value) {
        if (value == null || value.getId() == null) {
            return;
        }

        resolveAndSendResult(
                value.getCreatedBy(),
                "Phiếu rà soát kho "
                        + code(
                        value.getStockCountCode(),
                        value.getId(),
                        "SR"
                )
                        + " bị từ chối",
                "Phiếu rà soát kho của bạn đã bị từ chối. "
                        + "Vui lòng mở phiếu để kiểm tra lại.",
                NotificationType.STOCK_REVIEW_REJECTED,
                NotificationReferenceType.STOCK_REVIEW,
                value.getId(),
                "stock-reviews",
                NotificationSeverity.WARNING,
                "STOCK_REVIEW_REJECTED_" + value.getId()
        );
    }

    // =========================================================
    // SHIFT REPORT
    // =========================================================

    @Transactional
    public void shiftReportPending(Shiftreport value) {
        if (value == null || value.getId() == null) {
            return;
        }

        /*
         * Báo cáo ca bị từ chối có thể được sửa rồi gửi lại.
         * Đóng thông báo kết quả cũ trước khi tạo thông báo mới.
         */
        notificationService.resolveByReference(
                NotificationReferenceType.SHIFT_REPORT,
                value.getId()
        );

        BigDecimal discrepancy =
                safe(value.getCashDiscrepancy());

        boolean urgent =
                discrepancy.compareTo(BigDecimal.ZERO) != 0;

        String code = code(
                value.getShiftReportCode(),
                value.getId(),
                "CA"
        );

        String message =
                accountName(value.getCashierID())
                        + " đã nộp báo cáo ca "
                        + code
                        + ".";

        if (urgent) {
            message += " Chênh lệch quỹ: "
                    + money(discrepancy)
                    + ".";
        }

        sendToRole(
                RoleConstants.OWNER,
                "Báo cáo ca " + code + " cần duyệt",
                message,
                NotificationType.SHIFT_REPORT_PENDING,
                NotificationCategory.PHE_DUYET,
                urgent
                        ? NotificationSeverity.URGENT
                        : NotificationSeverity.WARNING,
                NotificationReferenceType.SHIFT_REPORT,
                value.getId(),
                "/owner/shift-reports/" + value.getId(),
                "SHIFT_REPORT_PENDING_" + value.getId(),
                null
        );
    }

    @Transactional
    public void shiftReportApproved(Shiftreport value) {
        if (value == null || value.getId() == null) {
            return;
        }

        resolveAndSendResult(
                value.getCashierID(),
                "Báo cáo ca "
                        + code(
                        value.getShiftReportCode(),
                        value.getId(),
                        "CA"
                )
                        + " đã được duyệt",
                "Báo cáo ca của bạn đã được duyệt.",
                NotificationType.SHIFT_REPORT_APPROVED,
                NotificationReferenceType.SHIFT_REPORT,
                value.getId(),
                "shift-reports",
                NotificationSeverity.INFO,
                "SHIFT_REPORT_APPROVED_" + value.getId()
        );
    }

    @Transactional
    public void shiftReportRejected(Shiftreport value) {
        if (value == null || value.getId() == null) {
            return;
        }

        resolveAndSendResult(
                value.getCashierID(),
                "Báo cáo ca "
                        + code(
                        value.getShiftReportCode(),
                        value.getId(),
                        "CA"
                )
                        + " bị từ chối",
                "Báo cáo ca của bạn đã bị từ chối. "
                        + "Vui lòng kiểm tra và nộp lại.",
                NotificationType.SHIFT_REPORT_REJECTED,
                NotificationReferenceType.SHIFT_REPORT,
                value.getId(),
                "shift-reports",
                NotificationSeverity.WARNING,
                "SHIFT_REPORT_REJECTED_" + value.getId()
        );
    }

    // =========================================================
    // INCOME
    // =========================================================

    @Transactional
    public void incomeCreated(Income value) {
        if (value == null || value.getId() == null) {
            return;
        }

        String code = code(
                value.getIncomeCode(),
                value.getId(),
                "PT"
        );

        String status = text(
                value.getStatus(),
                "Không rõ"
        );

        String severity =
                "Chờ duyệt".equalsIgnoreCase(status)
                        ? NotificationSeverity.WARNING
                        : NotificationSeverity.INFO;

        String message =
                accountName(value.getApplicantID())
                        + " đã tạo phiếu thu "
                        + code
                        + " với số tiền "
                        + money(value.getAmount())
                        + ". Trạng thái: "
                        + status
                        + ".";

        /*
         * Gửi Owner, nhưng không gửi lại cho chính người vừa tạo.
         */
        sendToRole(
                RoleConstants.OWNER,
                "Có phiếu thu mới " + code,
                message,
                NotificationType.INCOME_CREATED,
                NotificationCategory.TAI_CHINH,
                severity,
                NotificationReferenceType.INCOME,
                value.getId(),
                "/owner/incomes/" + value.getId(),
                "INCOME_CREATED_"
                        + value.getId()
                        + "_OWNER",
                value.getApplicantID()
        );

        /*
         * Gửi Accountant, nhưng không gửi lại cho chính người vừa tạo.
         */
        sendToRole(
                RoleConstants.ACCOUNTANT,
                "Có phiếu thu mới " + code,
                message,
                NotificationType.INCOME_CREATED,
                NotificationCategory.TAI_CHINH,
                severity,
                NotificationReferenceType.INCOME,
                value.getId(),
                "/accountant/incomes/" + value.getId(),
                "INCOME_CREATED_"
                        + value.getId()
                        + "_ACCOUNTANT",
                value.getApplicantID()
        );
    }

    // =========================================================
    // COMMON METHODS
    // =========================================================

    private void pendingToOwners(
            String title,
            String message,
            String type,
            String referenceType,
            Integer referenceId,
            String actionUrl,
            String dedupeKey
    ) {
        sendToRole(
                RoleConstants.OWNER,
                title,
                message,
                type,
                NotificationCategory.PHE_DUYET,
                NotificationSeverity.WARNING,
                referenceType,
                referenceId,
                actionUrl,
                dedupeKey,
                null
        );
    }

    private void resolveAndSendResult(
            Account receiver,
            String title,
            String message,
            String type,
            String referenceType,
            Integer referenceId,
            String modulePath,
            String severity,
            String dedupeKey
    ) {
        /*
         * Đóng thông báo "cần duyệt" cũ.
         */
        notificationService.resolveByReference(
                referenceType,
                referenceId
        );

        sendToAccount(
                receiver,
                title,
                message,
                type,
                NotificationCategory.PHE_DUYET,
                severity,
                referenceType,
                referenceId,
                modulePath,
                dedupeKey
        );
    }

    private void sendToAccount(
            Account receiver,
            String title,
            String message,
            String type,
            String category,
            String severity,
            String referenceType,
            Integer referenceId,
            String modulePath,
            String dedupeKey
    ) {
        if (!active(receiver)) {
            return;
        }

        Optional<String> role = roleOf(receiver);

        if (role.isEmpty()) {
            return;
        }

        String actionUrl =
                "/"
                        + RoleConstants.urlPrefix(role.get())
                        + "/"
                        + modulePath
                        + "/"
                        + referenceId;

        notificationService.createIfMissing(
                receiver,
                role.get(),
                title,
                message,
                type,
                category,
                severity,
                referenceType,
                referenceId,
                actionUrl,
                dedupeKey
                        + "_ACCOUNT_"
                        + receiver.getId()
        );
    }

    private void sendToRole(
            String role,
            String title,
            String message,
            String type,
            String category,
            String severity,
            String referenceType,
            Integer referenceId,
            String actionUrl,
            String dedupeKey,
            Account excludedAccount
    ) {
        List<Account> receivers =
                accountpermissionRepository
                        .findActiveAccountsByRole(role);

        for (Account receiver : receivers) {
            if (excludedAccount != null
                    && receiver.getId().equals(
                    excludedAccount.getId()
            )) {
                continue;
            }

            notificationService.createIfMissing(
                    receiver,
                    role,
                    title,
                    message,
                    type,
                    category,
                    severity,
                    referenceType,
                    referenceId,
                    actionUrl,
                    dedupeKey
                            + "_ACCOUNT_"
                            + receiver.getId()
            );
        }
    }

    private Optional<String> roleOf(Account account) {
        return accountpermissionRepository
                .findByAccountId(account.getId())
                .stream()
                .map(permission ->
                        permission.getRole() == null
                                ? null
                                : permission.getRole()
                                .trim()
                                .toUpperCase(Locale.ROOT)
                )
                .filter(RoleConstants::isValid)
                .findFirst();
    }

    private boolean active(Account account) {
        return account != null
                && account.getId() != null
                && Boolean.TRUE.equals(
                account.getStatus()
        );
    }

    private String accountName(Account account) {
        return account == null
                ? "Người dùng"
                : text(
                account.getName(),
                "Người dùng"
        );
    }

    private String code(
            String value,
            Integer id,
            String prefix
    ) {
        return value == null || value.isBlank()
                ? prefix
                + "-"
                + String.format(
                "%06d",
                id == null ? 0 : id
        )
                : value.trim();
    }

    private String money(BigDecimal value) {
        NumberFormat formatter =
                NumberFormat.getCurrencyInstance(VIETNAM);

        formatter.setMaximumFractionDigits(0);

        return formatter.format(safe(value));
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO
                : value;
    }

    private String text(
            String value,
            String fallback
    ) {
        return value == null || value.isBlank()
                ? fallback
                : value.trim();
    }
}