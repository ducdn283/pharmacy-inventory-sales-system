package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class StockReviewDetailItemResponse {

    private Integer productId;

    private String productCode;

    private String productName;

    private Integer batchId;

    private String lotNumber;

    private LocalDate recordedExpirationDate;

    private String recordedExpirationDateDisplay;

    private LocalDate actualExpirationDate;

    private String actualExpirationDateDisplay;

    private Integer systemQty;

    private Integer actualQty;

    private Integer discrepancy;

    private String discrepancyCssClass;

    private String conditionStatus;

    private String conditionStatusDisplay;

    private String conditionCssClass;

    private boolean issue;

    private String note;
}