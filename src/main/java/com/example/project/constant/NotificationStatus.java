package com.example.project.constant;

import java.util.List;

public final class NotificationStatus {

    public static final String UNREAD = "UNREAD";
    public static final String READ = "READ";
    public static final String RESOLVED = "RESOLVED";
    public static final String DISMISSED = "DISMISSED";

    public static final List<String> ALL = List.of(
            UNREAD,
            READ,
            RESOLVED,
            DISMISSED
    );

    private NotificationStatus() {
    }

    public static String vietnameseName(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }

        return switch (status) {
            case UNREAD -> "Chưa đọc";
            case READ -> "Đã đọc";
            case RESOLVED -> "Đã xử lý";
            case DISMISSED -> "Đã bỏ qua";
            default -> status;
        };
    }
}