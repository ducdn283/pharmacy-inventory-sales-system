package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One row in the sale-invoice list. */
@Getter
@AllArgsConstructor
public class InvoiceListItemResponse {

    private Integer id;
    private String code;

    private LocalDateTime date;
    private String dateDisplay;

    private String customerName;
    private String employeeName;

    private String invoiceTypeDisplay;

    private BigDecimal total;
    private BigDecimal debtAmount;

    private String paymentDisplay;

    private boolean prescriptionRequired;

    private String returnStatusDisplay;
    private String returnStatusCssClass;

    private String statusName;
    private String statusCssClass;
}
