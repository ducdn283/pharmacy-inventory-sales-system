package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accountID")
    private Account accountID;

    @NotNull
    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @NotNull
    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

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
    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    @Size(max = 20)
    @NotNull
    @Column(name = "status", nullable = false, length = 20)
    private String status;

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
    @Column(name = "isRead", nullable = false)
    private Boolean isRead;

    @Column(name = "readAt")
    private LocalDateTime readAt;

    @Column(name = "resolvedAt")
    private LocalDateTime resolvedAt;

    @Column(name = "expiresAt")
    private LocalDateTime expiresAt;

    @Size(max = 255)
    @Column(name = "dedupeKey", length = 255)
    private String dedupeKey;

    @Column(name = "isActive")
    private Boolean isActive;
}
