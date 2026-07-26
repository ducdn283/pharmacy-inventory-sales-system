package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** One selectable invoice in the "Chọn hóa đơn trả hàng" modal on the create screen. */
@Getter
@AllArgsConstructor
public class ReturnableInvoiceResponse {

    private Integer invoiceId;
    private String invoiceCode;

    private String dateDisplay;

    private String employeeName;
    private String customerName;

    private BigDecimal total;

    /** Current return status of the invoice (NONE / PARTIAL) — FULL invoices are excluded from the list. */
    private String returnStatusDisplay;
}
