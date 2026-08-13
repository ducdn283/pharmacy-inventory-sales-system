package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class StockReviewListItemResponse {

    private Integer stockReviewId;

    private String stockReviewCode;

    private Instant reviewDate;

    private String reviewDateDisplay;

    private String type;

    private String typeDisplay;

    private String createdByName;

    private String approvedByName;

    private String approvedAtDisplay;

    private long totalItems;

    private long issueItems;

    private String status;

    private String statusCssClass;

    private String note;
}