package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@AllArgsConstructor
public class InvoicePrintPageResponse {

    private Integer invoiceId;
    private String invoiceCode;
    private String invoicePattern;
    private String invoiceTypeDisplay;
    private boolean showVatBreakdown;
    private String dateDisplay;

    private String pharmacyName;
    private String pharmacyTaxCode;
    private String pharmacyPhone;

    private String customerName;
    private String customerPhone;
    private String employeeName;
    /** Mã số thuế trên hóa đơn đã ký — null nếu chưa ký. */
    private String taxCode;

    private int totalQuantity;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal totalPreTaxAmount;
    private BigDecimal totalVATOutput;
    private BigDecimal total;
    private BigDecimal paidByCash;
    private BigDecimal paidByBanking;
    private BigDecimal debtAmount;
    private String paymentDisplay;

    private String note;

    private List<InvoicePrintLineResponse> lines;
}
