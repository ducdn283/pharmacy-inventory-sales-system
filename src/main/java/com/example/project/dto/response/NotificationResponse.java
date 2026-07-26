package com.example.project.dto.response;

import com.example.project.entity.Notification;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {
    private Integer id;
    private Integer accountId;
    private String message;
    private Instant createdAt;
    private String title;
    private String notificationType;
    private String category;
    private String severity;
    private String status;
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

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getAccountID() != null ? notification.getAccountID().getId() : null,
                notification.getMessage(),
                notification.getCreatedAt(),
                notification.getTitle(),
                notification.getNotificationType(),
                notification.getCategory(),
                notification.getSeverity(),
                notification.getStatus(),
                notification.getTargetRole(),
                notification.getReferenceType(),
                notification.getReferenceId(),
                notification.getActionUrl(),
                notification.getIsRead(),
                notification.getReadAt(),
                notification.getResolvedAt(),
                notification.getExpiresAt(),
                notification.getDedupeKey(),
                notification.getIsActive()
        );
    }
}