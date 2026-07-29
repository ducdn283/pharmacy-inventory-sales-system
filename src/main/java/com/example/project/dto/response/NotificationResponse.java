package com.example.project.dto.response;

import com.example.project.constant.NotificationCategory;
import com.example.project.constant.NotificationSeverity;
import com.example.project.constant.NotificationStatus;
import com.example.project.entity.Notification;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Getter
@AllArgsConstructor
public class NotificationResponse {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_TIME_DISPLAY =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private Integer id;
    private Integer accountId;

    private String message;
    private Instant createdAt;
    private String createdAtDisplay;

    private String title;
    private String notificationType;

    private String category;
    private String categoryDisplay;

    private String severity;
    private String severityDisplay;

    private String status;
    private String statusDisplay;

    private String targetRole;
    private String referenceType;
    private Integer referenceId;
    private String actionUrl;

    private Boolean isRead;
    private LocalDateTime readAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime expiresAt;

    private String dedupeKey;
    private Boolean isActive;

    private String severityCssClass;
    private String categoryCssClass;

    public static NotificationResponse from(Notification notification) {
        String category = notification.getCategory();
        String severity = notification.getSeverity();
        String status = notification.getStatus();

        return new NotificationResponse(
                notification.getId(),
                notification.getAccountID() != null ? notification.getAccountID().getId() : null,

                notification.getMessage(),
                notification.getCreatedAt(),
                formatInstant(notification.getCreatedAt()),

                notification.getTitle(),
                notification.getNotificationType(),

                category,
                NotificationCategory.vietnameseName(category),

                severity,
                NotificationSeverity.vietnameseName(severity),

                status,
                NotificationStatus.vietnameseName(status),

                notification.getTargetRole(),
                notification.getReferenceType(),
                notification.getReferenceId(),
                notification.getActionUrl(),

                notification.getIsRead(),
                notification.getReadAt(),
                notification.getResolvedAt(),
                notification.getExpiresAt(),

                notification.getDedupeKey(),
                notification.getIsActive(),

                severityCssClass(severity),
                categoryCssClass(category)
        );
    }

    private static String formatInstant(Instant value) {
        if (value == null) {
            return "-";
        }

        return DATE_TIME_DISPLAY.format(value.atZone(VN_ZONE));
    }

    private static String severityCssClass(String severity) {
        if (NotificationSeverity.URGENT.equals(severity)) {
            return "danger";
        }

        if (NotificationSeverity.WARNING.equals(severity)) {
            return "warning";
        }

        return "success";
    }

    private static String categoryCssClass(String category) {
        if (NotificationCategory.KHO.equals(category)
                || NotificationCategory.HANG_HOA.equals(category)) {
            return "success";
        }

        if (NotificationCategory.PHE_DUYET.equals(category)) {
            return "warning";
        }

        if (NotificationCategory.TAI_CHINH.equals(category)
                || NotificationCategory.CONG_NO.equals(category)
                || NotificationCategory.KY_THUE.equals(category)) {
            return "orange";
        }

        if (NotificationCategory.HOA_DON.equals(category)
                || NotificationCategory.PHIEU_NHAP.equals(category)) {
            return "info";
        }

        return "secondary";
    }
}