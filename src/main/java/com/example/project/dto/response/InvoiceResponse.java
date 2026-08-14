package com.example.project.dto.response;

import com.example.project.entity.Invoice;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Đối tượng truyền dữ liệu ánh xạ {@link Invoice} — dùng khi service khác cần dữ liệu hóa đơn dạng phẳng. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceResponse {

    /** Id hóa đơn. */
    private Integer id;
    /** Ký hiệu mẫu số hóa đơn (7 ký tự). */
    private String invoicePattern;
    /** Số hóa đơn (8 chữ số). */
    private String invoiceNumber;
    /** Ngày giờ lập hóa đơn (giờ tường VN). */
    private LocalDateTime date;
    /** Id nhân viên bán. */
    private Integer employeeId;
    /** Id khách hàng — null nếu khách lẻ. */
    private Integer customerId;
    /** Tiền hàng trước giảm giá. */
    private BigDecimal subtotal;
    /** Số tiền giảm giá. */
    private BigDecimal discount;
    /** Tổng tiền sau giảm giá. */
    private BigDecimal total;
    /** Số tiền khách trả tiền mặt. */
    private BigDecimal paidByCash;
    /** Số tiền khách trả chuyển khoản. */
    private BigDecimal paidByBanking;
    /** Số tiền còn nợ. */
    private BigDecimal debtAmount;
    /** Có phải hóa đơn thuốc kê đơn hay không. */
    private Boolean prescriptionRequired;
    /** Loại hóa đơn (Bán hàng, Thay thế, …). */
    private String invoiceType;
    /** Id hóa đơn gốc — nếu là hóa đơn thay thế. */
    private Integer originalInvoiceID;
    /** Id phiếu trả sinh ra hóa đơn thay thế. */
    private Integer returnID;
    /** Trạng thái hóa đơn. */
    private String status;
    /** Trạng thái trả hàng (NONE / PARTIAL / FULL). */
    private String returnStatus;
    /** Ảnh đính kèm (nếu có). */
    private String image;
    /** Id ca bán gắn với hóa đơn. */
    private Integer shiftReportId;
    /** Mã đơn thuốc — hóa đơn kê đơn. */
    private String prescriptionCode;
    /** Ghi chú trên hóa đơn. */
    private String note;

    /** Ánh xạ {@link Invoice} sang đối tượng truyền dữ liệu. */
    public static InvoiceResponse from(Invoice invoice) {
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getInvoicePattern(),
                invoice.getInvoiceNumber(), 
                invoice.getDate(),
                invoice.getEmployeeID() != null ? invoice.getEmployeeID().getId() : null,
                invoice.getCustomerID() != null ? invoice.getCustomerID().getId() : null,
                invoice.getSubtotal(),
                invoice.getDiscount(),
                invoice.getTotal(),
                invoice.getPaidByCash(),
                invoice.getPaidByBanking(),
                invoice.getDebtAmount(),
                invoice.getPrescriptionRequired(),
                invoice.getInvoiceType(),
                invoice.getOriginalInvoiceID() != null ? invoice.getOriginalInvoiceID().getId() : null,
                invoice.getReturnID() != null ? invoice.getReturnID().getId() : null,
                invoice.getStatus(),
                invoice.getReturnStatus(),
                invoice.getImage(),
                invoice.getShiftReportID() != null ? invoice.getShiftReportID().getId() : null,
                invoice.getPrescriptionCode(),
                invoice.getNote()
        );
    }
}
