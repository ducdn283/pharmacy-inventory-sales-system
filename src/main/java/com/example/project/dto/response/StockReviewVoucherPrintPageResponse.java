package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@AllArgsConstructor
public class StockReviewVoucherPrintPageResponse {

    private Integer stockReviewId;

    private String stockReviewCode;

    private String reviewDateDisplay;

    private String type;

    private String typeDisplay;

    private String status;

    private String createdByName;

    private String approvedByName;

    private String approvedAtDisplay;

    private String note;

    private long totalItems;

    private long issueItems;

    private Integer totalSystemQty;

    private Integer totalActualQty;

    private Integer totalExcessQty;

    private Integer totalShortageQty;

    private Integer totalDiscrepancyQty;

    private BigDecimal totalDiscrepancyValue;

    private List<StockReviewVoucherPrintLineResponse> items;
}