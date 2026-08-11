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
    /** Số hóa đơn 8 chữ số hiển thị trên mẫu in (vd. 00008131). */
    private String invoiceSerialNumber;
    private String invoiceTypeDisplay;
    private boolean signed;
    /** Ngày 24 tháng 06 năm 2026 */
    private String dateLongDisplay;
    /** dd/MM/yyyy HH:mm — phiếu in bán hàng (POS). */
    private String dateDisplay;
    /** Tên thương hiệu ngắn trên phiếu in (vd. nhathuochangngoc). */
    private String pharmacyBrandName;
    /** Mã hóa đơn trên phiếu in POS (vd. 00000018). */
    private String receiptInvoiceCode;
    /** Mã CQT — chỉ có khi hóa đơn đã ký. */
    private String taxAuthorityCode;
    /** Thời điểm ký hiển thị trên khung chữ ký — chỉ có khi hóa đơn đã ký. */
    private String signedAtDisplay;

    private String pharmacyName;
    private String pharmacyTaxCode;
    private String pharmacyAddress;
    private String pharmacyLocationCode;
    private String pharmacyPhone;
    private String pharmacyEmail;
    private String pharmacyBankAccountNumber;
    private String pharmacyBankName;

    private String buyerCompanyName;
    private String buyerTaxCode;
    private String buyerAddress;
    private String paymentMethodShort;
    private String totalInWords;

    private String customerName;
    private String customerPhone;
    private String employeeName;

    private int totalQuantity;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal total;
    private BigDecimal paidByCash;
    private BigDecimal paidByBanking;
    private BigDecimal debtAmount;
    private String paymentDisplay;

    private String note;

    private List<InvoicePrintLineResponse> lines;
}
