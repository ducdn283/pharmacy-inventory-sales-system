package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class ExpenseDetailResponse {

    private Integer id;
    private String code;
    private String dateDisplay;

    private String expenseType;
    private String expenseTypeDisplay;

    private String applicantName;

    private String reason;

    private BigDecimal amount;
    private BigDecimal paid;
    private BigDecimal paidByCash;
    private BigDecimal paidByBanking;

    private String statusName;
    private String statusCssClass;

    private String approvedByName;
    private String approvedAtDisplay;

    private String note;

    /** Linked customer return, or {@code null} when this slip is not a refund payout. */
    private Integer returnId;
    private String returnCode;

    /** Customer of the linked return's original invoice; {@code null} for a walk-in sale. */
    private String customerName;

    /** Linked purchase invoice, or {@code null} when this slip does not settle one. */
    private Integer purchaseId;
    private String purchaseCode;

    /** Supplier of the linked purchase invoice. */
    private String supplierName;

    /**
     * Shift the payout was stamped against, or {@code null} when no shift was open at the time —
     * always the case for an Accountant, who never has one.
     */
    private Integer shiftReportId;
    private String shiftReportCode;
}
