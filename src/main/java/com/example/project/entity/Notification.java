package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notificationID", nullable = false)
    private Integer id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "accountID", nullable = false)
    private Account accountID;

    @NotNull
    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "createdAt", nullable = false)
    private Instant createdAt = Instant.now();

    @Size(max = 255)
    @NotNull
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Size(max = 50)
    @NotNull
    @Column(name = "notificationType", nullable = false, length = 50)
    private String notificationType;

    @Size(max = 50)
    @NotNull
    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Size(max = 20)
    @NotNull
    @ColumnDefault("'INFO'")
    @Column(name = "severity", nullable = false, length = 20)
    private String severity = "INFO";

    @Size(max = 20)
    @NotNull
    @ColumnDefault("'UNREAD'")
    @Column(name = "status", nullable = false, length = 20)
    private String status = "UNREAD";

    @Size(max = 50)
    @NotNull
    @Column(name = "targetRole", nullable = false, length = 50)
    private String targetRole;

    @Size(max = 50)
    @Column(name = "referenceType", length = 50)
    private String referenceType;

    @Column(name = "referenceId")
    private Integer referenceId;

    @Size(max = 255)
    @Column(name = "actionUrl", length = 255)
    private String actionUrl;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "isRead", nullable = false)
    private Boolean isRead = false;

    @Column(name = "readAt")
    private LocalDateTime readAt;

    @Column(name = "resolvedAt")
    private LocalDateTime resolvedAt;

    @Column(name = "expiresAt")
    private LocalDateTime expiresAt;

    @Size(max = 255)
    @Column(name = "dedupeKey", length = 255)
    private String dedupeKey;

    @NotNull
    @ColumnDefault("1")
    @Column(name = "isActive", nullable = false)
    private Boolean isActive = true;
}