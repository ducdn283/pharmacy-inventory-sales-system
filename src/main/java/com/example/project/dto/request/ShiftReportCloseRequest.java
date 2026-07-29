package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ShiftReportCloseRequest {

    /** Override of the inherited openingCash, if the cashier corrects it before submitting. */
    private BigDecimal openingCash;

    private BigDecimal actualClosingCash;

    private String noteDiscrepancy;

    /** General shift note (shiftreport.note) — separate from the cash-discrepancy explanation. */
    private String note;
}
