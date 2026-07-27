package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Full sale-invoice detail page payload. */
@Getter
@AllArgsConstructor
public class InvoiceDetailPageResponse {

    private Integer id;
    private String invoiceCode;
    private String invoicePattern;
    /** Mã số thuế đơn vị — chỉ hiển thị khi hóa đơn đã ký. */
    private String taxCode;

    private LocalDateTime date;
    private String dateDisplay;

    private String customerName;
    private String customerPhone;

    private String employeeName;

    private String invoiceTypeDisplay;

    private String statusName;
    private String statusCssClass;

    private boolean prescriptionRequired;
    private String prescriptionCode;

    private String returnStatusDisplay;
    private String returnStatusCssClass;

    private Map<Integer, String> returnSlips;

    private Integer originalInvoiceId;
    private String originalInvoiceCode;

    private Integer rootInvoiceId;
    private String rootInvoiceCode;

    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal totalVATOutput;
    private BigDecimal total;

    private BigDecimal paidByCash;
    private BigDecimal paidByBanking;
    private BigDecimal debtAmount;
    private String paymentDisplay;

    private String note;

    private long totalItems;
    private int totalQuantity;

    private List<InvoiceDetailItemResponse> items;

    private List<InvoiceDetailProductGroupResponse> productGroups;
}
