package com.example.project.service;

import com.example.project.constant.NotificationCategory;
import com.example.project.constant.NotificationReferenceType;
import com.example.project.constant.NotificationSeverity;
import com.example.project.constant.NotificationType;
import com.example.project.constant.RoleConstants;
import com.example.project.constant.TaxRevenueGroup;
import com.example.project.entity.Account;
import com.example.project.entity.Financialsetting;
import com.example.project.repository.AccountpermissionRepository;
import com.example.project.repository.FinancialsettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class TaxRevenueNotificationService {

    private static final BigDecimal WARNING_RATE = new BigDecimal("0.80");
    private static final Locale VIETNAM = Locale.forLanguageTag("vi-VN");

    private final TaxperiodsnapshotService taxperiodsnapshotService;
    private final FinancialsettingRepository financialsettingRepository;
    private final AccountpermissionRepository accountpermissionRepository;
    private final NotificationService notificationService;

    public TaxRevenueNotificationService(TaxperiodsnapshotService taxperiodsnapshotService,
                                         FinancialsettingRepository financialsettingRepository,
                                         AccountpermissionRepository accountpermissionRepository,
                                         NotificationService notificationService) {
        this.taxperiodsnapshotService = taxperiodsnapshotService;
        this.financialsettingRepository = financialsettingRepository;
        this.accountpermissionRepository = accountpermissionRepository;
        this.notificationService = notificationService;
    }

    /**
     * The single entry point the Tax Period screens call: applies the 1 → 2 auto-transition if the
     * threshold has just been crossed (notifying about it), then falls through to the existing
     * warning for a 2 → 3 crossing still pending until the year's last quarter closes.
     */
    @Transactional
    public void checkGroupTransitionAndWarn(TaxperiodsnapshotService.TaxPeriod period,
                                            Integer taxPeriodSnapshotId) {
        TaxperiodsnapshotService.GroupTransitionResult result =
                taxperiodsnapshotService.applyAutomaticGroupTransition();
        if (result.changed()) {
            sendGroupChangedNotification(period, taxPeriodSnapshotId, result.fromGroup(), result.toGroup());
        }
        warnIfRevenueThresholdReached(period, taxPeriodSnapshotId);
    }

    /**
     * Warns about a still-pending 2 → 3 crossing (the 1 → 2 case is no longer "pending" by the time
     * this runs — {@link #checkGroupTransitionAndWarn} already applied it).
     */
    @Transactional
    public void warnIfRevenueThresholdReached(TaxperiodsnapshotService.TaxPeriod period,
                                              Integer taxPeriodSnapshotId) {
        if (period == null || period.startDate() == null) {
            return;
        }

        Integer currentGroup = taxperiodsnapshotService.groupForPeriod(period);

        if (currentGroup == null || currentGroup >= TaxRevenueGroup.DEDUCTION) {
            return;
        }

        Financialsetting setting = financialsettingRepository.findFirstByOrderByIdAsc()
                .orElse(null);

        BigDecimal threshold = nextThreshold(setting, currentGroup);

        if (threshold == null || threshold.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        int year = period.startDate().getYear();
        BigDecimal revenue = taxperiodsnapshotService.revenueForYear(year);

        BigDecimal warningThreshold = threshold.multiply(WARNING_RATE);

        if (revenue.compareTo(threshold) >= 0) {
            sendExceededNotification(period, taxPeriodSnapshotId, currentGroup, revenue, threshold);
            return;
        }

        if (revenue.compareTo(warningThreshold) >= 0) {
            sendWarningNotification(period, taxPeriodSnapshotId, currentGroup, revenue, threshold);
        }
    }

    private void sendGroupChangedNotification(TaxperiodsnapshotService.TaxPeriod period,
                                              Integer taxPeriodSnapshotId,
                                              Integer fromGroup,
                                              Integer toGroup) {
        int year = period.startDate().getYear();

        String title = "Đã tự động chuyển sang " + TaxRevenueGroup.shortLabel(toGroup);

        String message = "Doanh thu lũy kế năm " + year + " đã vượt ngưỡng chuyển nhóm, nên hệ thống "
                + "đã tự động chuyển từ " + TaxRevenueGroup.shortLabel(fromGroup)
                + " sang " + TaxRevenueGroup.shortLabel(toGroup)
                + ", áp dụng ngay từ kỳ hiện tại (" + period.label() + ") theo quy định.";

        String dedupeKey = "TAX_GROUP_CHANGED_" + year + "_G" + fromGroup + "_TO_G" + toGroup;

        sendToOwnerAndAccountant(
                title,
                message,
                NotificationType.TAX_GROUP_CHANGED,
                NotificationSeverity.URGENT,
                period,
                taxPeriodSnapshotId,
                dedupeKey
        );
    }

    private void sendWarningNotification(TaxperiodsnapshotService.TaxPeriod period,
                                         Integer taxPeriodSnapshotId,
                                         Integer currentGroup,
                                         BigDecimal revenue,
                                         BigDecimal threshold) {
        int year = period.startDate().getYear();
        Integer nextGroup = currentGroup + 1;

        String title = "Doanh thu năm " + year + " đã đạt 80% ngưỡng chuyển nhóm thuế";

        String message = "Doanh thu lũy kế năm " + year + " hiện đạt "
                + money(revenue)
                + ", đã tiệm cận ngưỡng " + money(threshold)
                + " để chuyển từ "
                + TaxRevenueGroup.shortLabel(currentGroup)
                + " sang "
                + TaxRevenueGroup.shortLabel(nextGroup)
                + ". "
                + transitionRule(currentGroup);

        String dedupeKey = "TAX_THRESHOLD_" + year
                + "_G" + currentGroup
                + "_TO_G" + nextGroup
                + "_80PCT";

        sendToOwnerAndAccountant(
                title,
                message,
                NotificationType.TAX_REVENUE_WARNING,
                NotificationSeverity.WARNING,
                period,
                taxPeriodSnapshotId,
                dedupeKey
        );
    }

    private void sendExceededNotification(TaxperiodsnapshotService.TaxPeriod period,
                                          Integer taxPeriodSnapshotId,
                                          Integer currentGroup,
                                          BigDecimal revenue,
                                          BigDecimal threshold) {
        int year = period.startDate().getYear();
        Integer nextGroup = currentGroup + 1;

        String title = "Doanh thu năm " + year + " đã vượt ngưỡng chuyển nhóm thuế";

        String message = "Doanh thu lũy kế năm " + year + " hiện đạt "
                + money(revenue)
                + ", đã vượt ngưỡng " + money(threshold)
                + " để chuyển từ "
                + TaxRevenueGroup.shortLabel(currentGroup)
                + " sang "
                + TaxRevenueGroup.shortLabel(nextGroup)
                + ". "
                + transitionRule(currentGroup);

        String dedupeKey = "TAX_THRESHOLD_" + year
                + "_G" + currentGroup
                + "_TO_G" + nextGroup
                + "_EXCEEDED";

        sendToOwnerAndAccountant(
                title,
                message,
                NotificationType.TAX_REVENUE_EXCEEDED,
                NotificationSeverity.URGENT,
                period,
                taxPeriodSnapshotId,
                dedupeKey
        );
    }

    private void sendToOwnerAndAccountant(String title,
                                          String message,
                                          String notificationType,
                                          String severity,
                                          TaxperiodsnapshotService.TaxPeriod period,
                                          Integer taxPeriodSnapshotId,
                                          String dedupeKey) {
        sendToRole(
                RoleConstants.OWNER,
                title,
                message,
                notificationType,
                severity,
                taxPeriodSnapshotId,
                "/owner/tax-periods/preview?year=" + period.startDate().getYear()
                        + "&quarter=" + quarterNumber(period),
                dedupeKey + "_OWNER"
        );

        sendToRole(
                RoleConstants.ACCOUNTANT,
                title,
                message,
                notificationType,
                severity,
                taxPeriodSnapshotId,
                "/accountant/tax-periods/preview?year=" + period.startDate().getYear()
                        + "&quarter=" + quarterNumber(period),
                dedupeKey + "_ACCOUNTANT"
        );
    }

    private void sendToRole(String role,
                            String title,
                            String message,
                            String notificationType,
                            String severity,
                            Integer taxPeriodSnapshotId,
                            String actionUrl,
                            String dedupeKey) {
        List<Account> receivers = accountpermissionRepository.findActiveAccountsByRole(role);

        for (Account receiver : receivers) {
            notificationService.createIfMissing(
                    receiver,
                    role,
                    title,
                    message,
                    notificationType,
                    NotificationCategory.KY_THUE,
                    severity,
                    NotificationReferenceType.TAX_PERIOD,
                    taxPeriodSnapshotId,
                    actionUrl,
                    dedupeKey + "_ACCOUNT_" + receiver.getId()
            );
        }
    }

    private BigDecimal nextThreshold(Financialsetting setting, Integer currentGroup) {
        BigDecimal threshold1 = Optional.ofNullable(setting)
                .map(Financialsetting::getAnnualRevenueThreshold1)
                .orElse(new BigDecimal("1000000000.00"));

        BigDecimal threshold2 = Optional.ofNullable(setting)
                .map(Financialsetting::getAnnualRevenueThreshold2)
                .orElse(new BigDecimal("3000000000.00"));

        if (TaxRevenueGroup.EXEMPT == currentGroup) {
            return threshold1;
        }

        if (TaxRevenueGroup.DIRECT == currentGroup) {
            return threshold2;
        }

        return null;
    }

    private String transitionRule(Integer currentGroup) {
        if (TaxRevenueGroup.EXEMPT == currentGroup) {
            return "Quy tắc chuyển Nhóm 1 → 2: hệ thống sẽ tự động tính thuế ngay từ chính quý phát sinh vượt ngưỡng, không cần thao tác thủ công.";
        }

        if (TaxRevenueGroup.DIRECT == currentGroup) {
            return "Quy tắc chuyển Nhóm 2 → 3: vẫn giữ Nhóm 2 đến hết năm tài chính; hệ thống sẽ tự chuyển sang Nhóm 3 khi chốt kỳ cuối năm.";
        }

        return "";
    }

    private int quarterNumber(TaxperiodsnapshotService.TaxPeriod period) {
        return (period.startDate().getMonthValue() - 1) / 3 + 1;
    }

    private String money(BigDecimal value) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        NumberFormat format = NumberFormat.getCurrencyInstance(VIETNAM);
        format.setMaximumFractionDigits(0);
        return format.format(safe.setScale(0, RoundingMode.HALF_UP));
    }
}