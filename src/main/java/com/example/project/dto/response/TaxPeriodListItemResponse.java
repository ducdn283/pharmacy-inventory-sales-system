package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * One row of the closed-tax-period list. Dates are pre-formatted {@code String}s so the template
 * never has to format a temporal in a {@code th:inline} block (see CLAUDE.md).
 */
@Getter
@AllArgsConstructor
public class TaxPeriodListItemResponse {

    private Integer id;
    private String periodLabel;

    private String startDateDisplay;
    private String endDateDisplay;

    /** The group this period was declared under — derived from the chain, not stored on the row. */
    private Integer revenueGroup;
    private String revenueGroupDisplay;

    private BigDecimal vatOutput;
    private BigDecimal vatInput;
    private BigDecimal vatCarryforwardOut;

    /** {@code vatOutput − vatInput − vatCarryforwardIn}, floored at zero: the VAT actually payable. */
    private BigDecimal vatPayable;

    private String recordedAtDisplay;

    /** Only the newest period may still be edited — the chain below it is frozen. */
    private boolean editable;
}
