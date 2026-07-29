package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Form backing the "create income" screen. {@code applicantID} is always the current user.
 * Reference documents are required by income type: debt sales invoice (CUSTOMER), approved
 * supplier return (SUPPLIER), approved stock adjustment (EMPLOYEE),
 * closed shift report with cash shortage (SHIFT_SHORTAGE).
 */
@Getter
@Setter
public class IncomeCreateRequest {

    /** One of {@link com.example.project.dto.response.IncomeTypeOptionResponse}'s type codes. */
    private String incomeType;

    private String reason;

    /** Tổng số tiền thu. */
    private BigDecimal amount;

    private BigDecimal paidByCash;

    private BigDecimal paidByBanking;

    /** Set when {@code incomeType} is SUPPLIER. */
    private Integer supplierId;

    /** Set when {@code incomeType} is CUSTOMER. */
    private Integer customerId;

    /** Set when {@code incomeType} is EMPLOYEE. */
    private Integer accountId;

    /** Sales invoice with debt — set when {@code incomeType} is CUSTOMER. */
    private Integer invoiceId;

    /** Approved supplier return — set when {@code incomeType} is SUPPLIER. */
    private Integer returnId;

    /** Approved stock adjustment — set when {@code incomeType} is EMPLOYEE. */
    private Integer stockAdjustmentId;

    /** Closed shift report with cash shortage — set when {@code incomeType} is SHIFT_SHORTAGE. */
    private Integer shiftReportOfAccountId;

    private String note;
}
