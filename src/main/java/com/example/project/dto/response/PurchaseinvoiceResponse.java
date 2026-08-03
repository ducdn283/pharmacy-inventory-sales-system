package com.example.project.dto.response;

import com.example.project.entity.Purchaseinvoice;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseinvoiceResponse {
    private Integer id;
    private String purchaseInvoiceCode;
    private Instant date;
    private Integer supplierId;
    private Integer employeeId;
    private LocalDateTime approvedAt;
    private BigDecimal additionCost;
    private BigDecimal discount;
    private BigDecimal totalAmount;
    private Integer procurementID;
    private String returnStatus;
    private BigDecimal paid;
    private String status;
    private String note;
    private String vatInvoiceNumber;
    private LocalDate vatInvoiceDate;
    private BigDecimal totalVATInput;
    private Boolean isValidForDeduction;
    private LocalDate dueDate;

    public static PurchaseinvoiceResponse from(Purchaseinvoice purchaseinvoice) {
        return new PurchaseinvoiceResponse(
                purchaseinvoice.getId(),
                purchaseinvoice.getPurchaseInvoiceCode(),
                purchaseinvoice.getDate(),
                purchaseinvoice.getSupplierID() != null ? purchaseinvoice.getSupplierID().getId() : null,
                purchaseinvoice.getEmployeeID() != null ? purchaseinvoice.getEmployeeID().getId() : null,
                purchaseinvoice.getApprovedAt(),
                purchaseinvoice.getAdditionCost(),
                purchaseinvoice.getDiscount(),
                purchaseinvoice.getTotalAmount(),
                purchaseinvoice.getProcurementID() != null ? purchaseinvoice.getProcurementID().getId() : null,
                purchaseinvoice.getReturnStatus(),
                purchaseinvoice.getPaid(),
                purchaseinvoice.getStatus(),
                purchaseinvoice.getNote(),
                purchaseinvoice.getVatInvoiceNumber(),
                purchaseinvoice.getVatInvoiceDate(),
                purchaseinvoice.getTotalVATInput(),
                purchaseinvoice.getIsValidForDeduction(),
                purchaseinvoice.getDueDate()
        );
    }
}