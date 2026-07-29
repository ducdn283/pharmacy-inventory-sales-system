package com.example.project.constant;

import java.util.List;

public final class NotificationSeverity {

    public static final String INFO = "INFO";
    public static final String WARNING = "WARNING";
    public static final String URGENT = "URGENT";

    public static final List<String> ALL = List.of(
            INFO,
            WARNING,
            URGENT
    );

    private NotificationSeverity() {
    }

    public static String vietnameseName(String severity) {
        if (severity == null || severity.isBlank()) {
            return "";
        }

        return switch (severity) {
            case INFO -> "Thông tin";
            case WARNING -> "Cảnh báo";
            case URGENT -> "Khẩn cấp";
            default -> severity;
        };
    }
}