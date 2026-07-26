package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * One selectable reference document on the create-expense screen — currently only a customer
 * return awaiting its refund payout. Mirrors {@link IncomeReferenceOptionResponse}, which does the
 * same job for the opposite direction (a supplier return the pharmacy collects money for).
 *
 * <p>All fields are scalars and {@code dateDisplay} is pre-formatted, so the list is safe to embed
 * in a {@code th:inline} block.</p>
 */
@Getter
@AllArgsConstructor
public class ExpenseReferenceOptionResponse {

    private Integer id;
    private String code;
    private String dateDisplay;

    /** The part of the refund that is real money out — {@code refundCash + refundBanking}. */
    private BigDecimal amount;

    private String detail;
}
