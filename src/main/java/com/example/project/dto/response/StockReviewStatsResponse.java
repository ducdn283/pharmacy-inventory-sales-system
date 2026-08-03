package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class StockReviewStatsResponse {

    private long totalCount;

    private long draftCount;

    private long pendingCount;

    private long approvedCount;

    private long adjustedCount;
}