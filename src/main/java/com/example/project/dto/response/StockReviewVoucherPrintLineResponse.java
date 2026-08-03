package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class StockReviewVoucherPrintLineResponse {

    private Integer productId;

    private String productCode;

    private String productName;

    private String lotNumber;

    private String expirationDateDisplay;

    private Integer systemQty;

    private Integer actualQty;

    private Integer discrepancy;

    private BigDecimal discrepancyValue;
}