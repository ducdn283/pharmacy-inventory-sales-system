package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class StockReviewVoucherPrintLineResponse {

    private Integer productId;

    private String productCode;

    private String productName;

    private String lotNumber;

    private String expirationDateDisplay;

    private LocalDate recordedExpirationDate;

    private String recordedExpirationDateDisplay;

    private LocalDate actualExpirationDate;

    private String actualExpirationDateDisplay;

    private Integer systemQty;

    private Integer actualQty;

    private Integer discrepancy;

    private BigDecimal discrepancyValue;

    private String conditionStatus;

    private String conditionStatusDisplay;

    private boolean issue;

    private String note;
}