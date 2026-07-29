package com.example.project.dto.response;

import com.example.project.entity.Return;
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
public class ReturnResponse {
    private Integer id;
    private String returnCode;
    private Integer invoiceId;
    private Integer purchaseId;
    private Integer returnedById;
    private Instant returnDate;
    private String returnType;
    private BigDecimal totalRefund;
    private BigDecimal offsetDebtAmount;
    private Integer shiftReportId;
    private String reason;
    private String status;
    private Instant approvedAt;
    private String note;
    private BigDecimal totalVATRefund;
    private BigDecimal appliedRefundRate;

    public static ReturnResponse from(Return returnEntity) {
        return new ReturnResponse(
                returnEntity.getId(),
                returnEntity.getReturnCode(), 
                returnEntity.getInvoiceID() != null ? returnEntity.getInvoiceID().getId() : null,
                returnEntity.getPurchaseID() != null ? returnEntity.getPurchaseID().getId() : null,
                returnEntity.getReturnedBy() != null ? returnEntity.getReturnedBy().getId() : null,
                returnEntity.getReturnDate(),
                returnEntity.getReturnType(),
                returnEntity.getTotalRefund(),
                returnEntity.getOffsetDebtAmount(),
                returnEntity.getShiftReportID() != null ? returnEntity.getShiftReportID().getId() : null,
                returnEntity.getReason(),
                returnEntity.getStatus(),
                returnEntity.getApprovedAt(),
                returnEntity.getNote(),
                returnEntity.getTotalVATRefund(),
                returnEntity.getAppliedRefundRate()
        );
    }
}
