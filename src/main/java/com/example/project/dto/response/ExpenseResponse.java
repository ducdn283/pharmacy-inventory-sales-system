package com.example.project.dto.response;

import com.example.project.entity.Expense;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseResponse {
    private Integer id;
    private String expenseCode;
    private Integer applicantId;
    private String expenseType;
    private Integer returnId;
    private Integer purchaseId;
    private Integer shiftReportId;
    private Instant date;
    private String reason;
    private BigDecimal amount;
    private BigDecimal paid;
    private BigDecimal paidByCash;
    private BigDecimal paidByBanking;
    private BigDecimal paidByCredit;
    private Integer supplierId;
    private Integer customerId;
    private Integer accountId;
    private String status;
    private Instant approvedAt;
    private String note;

    public static ExpenseResponse from(Expense expense) {
        return new ExpenseResponse(
                expense.getId(),
                expense.getExpenseCode(),
                expense.getApplicantID() != null ? expense.getApplicantID().getId() : null,
                expense.getExpenseType(),
                expense.getReturnID() != null ? expense.getReturnID().getId() : null,
                expense.getPurchaseID() != null ? expense.getPurchaseID().getId() : null,
                expense.getShiftReportID() != null ? expense.getShiftReportID().getId() : null,
                expense.getDate(),
                expense.getReason(),
                expense.getAmount(),
                expense.getPaid(),
                expense.getPaidByCash(),
                expense.getPaidByBanking(),
                expense.getPaidByCredit(),
                expense.getSupplierID() != null ? expense.getSupplierID().getId() : null,
                expense.getCustomerID() != null ? expense.getCustomerID().getId() : null,
                expense.getAccountID() != null ? expense.getAccountID().getId() : null,
                expense.getStatus(),
                expense.getApprovedAt(),
                expense.getNote()
        );
    }
}