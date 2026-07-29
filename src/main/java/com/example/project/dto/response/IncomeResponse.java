package com.example.project.dto.response;

import com.example.project.entity.Income;
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
public class IncomeResponse {
    private Integer id;
    private String incomeCode;
    private Integer applicantId;
    private String incomeType;
    private Integer invoiceId;
    private Integer returnId;
    private Integer shiftReportId;
    private Instant date;
    private String reason;
    private BigDecimal amount;
    private BigDecimal paidByCash;
    private BigDecimal paidByBanking;
    private BigDecimal paidByCredit;
    private Integer supplierId;
    private Integer customerId;
    private Integer accountId;
    private String status;
    private String note;
    private Integer stockAdjustmentID;
    private Integer shiftReportOfAccountID;

    public static IncomeResponse from(Income income) {
        return new IncomeResponse(
                income.getId(),
                income.getIncomeCode(),
                income.getApplicantID() != null ? income.getApplicantID().getId() : null,
                income.getIncomeType(),
                income.getInvoiceID() != null ? income.getInvoiceID().getId() : null,
                income.getReturnID() != null ? income.getReturnID().getId() : null,
                income.getShiftReportID() != null ? income.getShiftReportID().getId() : null,
                income.getDate(),
                income.getReason(),
                income.getAmount(),
                income.getPaidByCash(),
                income.getPaidByBanking(),
                income.getPaidByCredit(),
                income.getSupplierID() != null ? income.getSupplierID().getId() : null,
                income.getCustomerID() != null ? income.getCustomerID().getId() : null,
                income.getAccountID() != null ? income.getAccountID().getId() : null,
                income.getStatus(),
                income.getNote(),
                income.getStockAdjustmentID() != null ? income.getStockAdjustmentID().getId() : null,
                income.getShiftReportOfAccountID() != null ? income.getShiftReportOfAccountID().getId() : null
        );
    }
}
