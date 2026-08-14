package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** Dữ liệu trang in hóa đơn — thông tin nhà thuốc, khách hàng và danh sách dòng hàng. */
@Getter
@AllArgsConstructor
public class InvoicePrintPageResponse {

    /** Id hóa đơn. */
    private Integer invoiceId;
    /** Mã hóa đơn hiển thị. */
    private String invoiceCode;
    /** Ký hiệu mẫu số hóa đơn. */
    private String invoicePattern;
    /** Số hóa đơn 8 chữ số hiển thị trên mẫu in (vd. 00008131). */
    private String invoiceSerialNumber;
    /** Loại hóa đơn hiển thị. */
    private String invoiceTypeDisplay;
    /** Hóa đơn đã ký điện tử (có mã CQT) hay chưa. */
    private boolean signed;
    /** Ngày dài trên mẫu in (vd. Ngày 24 tháng 06 năm 2026). */
    private String dateLongDisplay;
    /** Ngày giờ ngắn trên phiếu in POS ({@code dd/MM/yyyy HH:mm}). */
    private String dateDisplay;
    /** Tên thương hiệu ngắn trên phiếu in (vd. nhathuochangngoc). */
    private String pharmacyBrandName;
    /** Mã hóa đơn trên phiếu in POS (vd. 00000018). */
    private String receiptInvoiceCode;
    /** Mã CQT — chỉ có khi hóa đơn đã ký. */
    private String taxAuthorityCode;
    /** Thời điểm ký hiển thị trên khung chữ ký — chỉ có khi hóa đơn đã ký. */
    private String signedAtDisplay;

    /** Tên nhà thuốc / đơn vị bán. */
    private String pharmacyName;
    /** Mã số thuế nhà thuốc. */
    private String pharmacyTaxCode;
    /** Địa chỉ nhà thuốc. */
    private String pharmacyAddress;
    /** Mã địa điểm kinh doanh. */
    private String pharmacyLocationCode;
    /** Số điện thoại nhà thuốc. */
    private String pharmacyPhone;
    /** Email nhà thuốc. */
    private String pharmacyEmail;
    /** Số tài khoản ngân hàng nhà thuốc. */
    private String pharmacyBankAccountNumber;
    /** Tên ngân hàng nhà thuốc. */
    private String pharmacyBankName;

    /** Tên người mua / công ty mua (mẫu hóa đơn GTGT). */
    private String buyerCompanyName;
    /** Mã số thuế người mua. */
    private String buyerTaxCode;
    /** Địa chỉ người mua. */
    private String buyerAddress;
    /** Hình thức thanh toán viết tắt (TM / CK / TM/CK). */
    private String paymentMethodShort;
    /** Tổng tiền bằng chữ. */
    private String totalInWords;

    /** Tên khách hàng trên phiếu in POS. */
    private String customerName;
    /** Số điện thoại khách hàng. */
    private String customerPhone;
    /** Tên nhân viên bán. */
    private String employeeName;

    /** Tổng số lượng hàng trên hóa đơn. */
    private int totalQuantity;
    /** Tiền hàng trước giảm giá. */
    private BigDecimal subtotal;
    /** Số tiền giảm giá. */
    private BigDecimal discount;
    /** Tổng tiền sau giảm giá. */
    private BigDecimal total;
    /** Số tiền trả tiền mặt. */
    private BigDecimal paidByCash;
    /** Số tiền trả chuyển khoản. */
    private BigDecimal paidByBanking;
    /** Số tiền còn nợ. */
    private BigDecimal debtAmount;
    /** Hình thức thanh toán hiển thị đầy đủ. */
    private String paymentDisplay;

    /** Ghi chú trên hóa đơn. */
    private String note;

    /** Các dòng hàng in ra. */
    private List<InvoicePrintLineResponse> lines;
}
