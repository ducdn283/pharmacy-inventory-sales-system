package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class ProcurementPlanPrintLineResponse {

    private String productCode;
    private String productName;
    private Integer currentStock;
    private String stockUnit;
    private String unitConversionHint;
    private Integer requestedQuantity;
    private String unit;
    private BigDecimal unitPrice;
    private BigDecimal estimatedPrice;
    private String supplierName;
}
