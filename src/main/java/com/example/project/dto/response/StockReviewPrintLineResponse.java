package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class StockReviewPrintLineResponse {

    private Integer productId;

    private String productCode;

    private String productName;

    private String lotNumber;

    private String expirationDateDisplay;

    private Integer systemQty;
}