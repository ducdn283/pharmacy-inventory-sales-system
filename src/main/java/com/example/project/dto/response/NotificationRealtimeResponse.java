package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class NotificationRealtimeResponse {

    private long unreadCount;
    private List<NotificationResponse> latestNotifications;
}