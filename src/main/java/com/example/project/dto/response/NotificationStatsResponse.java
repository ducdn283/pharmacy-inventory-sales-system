package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class NotificationStatsResponse {

    private long totalCount;
    private long unreadCount;
    private long actionRequiredCount;
    private long urgentCount;
}