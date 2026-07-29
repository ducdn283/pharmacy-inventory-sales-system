package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class StockAdjustmentStatsResponse {

    private long monthlyCount;
    private long draftCount;
    private long completedCount;
    private long cancelledCount;
}