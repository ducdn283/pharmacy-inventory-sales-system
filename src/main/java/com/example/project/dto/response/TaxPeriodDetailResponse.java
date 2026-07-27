package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * A closed tax period, as shown on the detail screen. Carries both the stored figures and the
 * chain context needed to read them: which group the period was declared under, and which period
 * fed its {@code vatCarryforwardIn}.
 */
@Getter
@AllArgsConstructor
public class TaxPeriodDetailResponse {

    private Integer id;
    private String periodLabel;

    private String startDateDisplay;
    private String endDateDisplay;

    private Integer revenueGroup;
    private String revenueGroupDisplay;
    private boolean deductionGroup;
    private boolean taxExempt;

    private BigDecimal vatOutput;
    private BigDecimal vatInput;
    private BigDecimal vatCarryforwardIn;
    private BigDecimal vatCarryforwardOut;
    private BigDecimal vatPayable;

    /** Thuế TNCN của kỳ. Stored, not re-derived — see {@code TaxPeriodUpdateRequest}. */
    private BigDecimal incomeTax;

    private BigDecimal cashBalanceAtPeriodEnd;

    /** Group to apply from the day after {@code endDate}; defaults to this period's own group. */
    private Integer nextPeriodTaxType;
    private String nextPeriodTaxTypeDisplay;

    private String recordedAtDisplay;
    private String note;

    /** Label of the period this one's carry-forward came from; null for the first period. */
    private String previousPeriodLabel;

    private boolean editable;
}
