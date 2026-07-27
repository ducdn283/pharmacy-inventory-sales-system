package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * A tax period computed live from the transactions in it — nothing is stored. This is what the
 * "xem trước kỳ hiện tại" screen renders, and what pre-fills the figures when the period is
 * eventually closed. The docx allows either ("chốt tay hoặc tính runtime rồi lưu lại"), so the
 * numbers are a starting point, not a verdict.
 *
 * <p>Each VAT total is exposed together with the two components it was built from, because an
 * accountant checking a declaration needs to see <em>why</em> a figure moved, not just the net.</p>
 */
@Getter
@AllArgsConstructor
public class TaxPeriodComputationResponse {

    private String periodLabel;
    private String startDateDisplay;
    private String endDateDisplay;

    private Integer revenueGroup;
    private String revenueGroupDisplay;
    private boolean deductionGroup;
    private boolean taxExempt;

    // --- output VAT: sales in the period, less VAT on goods customers returned in the period
    private BigDecimal vatOutputFromSales;
    private BigDecimal vatOutputReturnDeduction;
    private BigDecimal vatOutput;

    // --- input VAT: deductible purchases in the period, less VAT reversed by supplier returns.
    //     Both are zero outside the deduction group — there is nothing to deduct.
    private BigDecimal vatInputFromPurchases;
    private BigDecimal vatInputReturnReversal;
    private BigDecimal vatInput;

    private BigDecimal vatCarryforwardIn;
    private BigDecimal vatCarryforwardOut;
    private BigDecimal vatPayable;

    /** How many source documents fell in the period — a sanity check for an empty-looking result. */
    private int invoiceCount;
    private int customerReturnCount;
    private int purchaseInvoiceCount;
    private int supplierReturnCount;

    /** Purchase invoices inside the period that were skipped as not deductible. */
    private int nonDeductiblePurchaseCount;

    /** Label of the period supplying {@code vatCarryforwardIn}; null when this is the first one. */
    private String previousPeriodLabel;

    /** True when a snapshot with this {@code periodLabel} has already been closed. */
    private boolean alreadyClosed;
}
