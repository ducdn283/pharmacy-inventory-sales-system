package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
@AllArgsConstructor
public class StockReviewDetailPageResponse {

    private Integer stockReviewId;

    private String stockReviewCode;

    private Instant reviewDate;

    private String reviewDateDisplay;

    private String type;

    private String typeDisplay;

    private String createdByName;

    private String approvedByName;

    private String approvedAtDisplay;

    private String status;

    private String statusCssClass;

    private String note;

    private long totalItems;

    private long matchedItems;

    private long issueItems;

    private int totalSystemQty;

    private int totalActualQty;

    private int totalDiscrepancy;

    private List<StockReviewDetailItemResponse> items;
}