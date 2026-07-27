package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** KPI totals for the debt list page. */
@Getter
@AllArgsConstructor
public class DebtSummaryResponse {

    private long entityCount;
    private BigDecimal totalReceivable;
    private BigDecimal customerReceivable;
    private BigDecimal supplierReceivable;
    private BigDecimal totalPayable;
    private BigDecimal customerPayable;
    private BigDecimal supplierPayable;
}
