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

    /**
     * The VAT actually payable — the one figure that means the same thing in every group.
     *
     * <p>Output and input VAT are deliberately <em>not</em> on this row: they only exist under the
     * deduction method (group 3). A group-2 period owes a flat percentage of revenue and a group-1
     * period owes nothing, so columns for them would be blank or misleading on most rows. The full
     * breakdown lives on the detail screen, where the group is stated alongside it.</p>
     */
    private BigDecimal vatPayable;

    /** Thuế TNCN của kỳ — the other half of what the household actually owes. */
    private BigDecimal incomeTax;

    private String recordedAtDisplay;

    /** Only the newest period may still be edited — the chain below it is frozen. */
    private boolean editable;
}
