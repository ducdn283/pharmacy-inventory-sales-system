package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Một dòng trên danh sách hóa đơn bán hàng. */
@Getter
@AllArgsConstructor
public class InvoiceListItemResponse {

    /** Id hóa đơn. */
    private Integer id;
    /** Mã hiển thị — số hóa đơn 8 chữ số. */
    private String code;

    /** Ngày giờ lập hóa đơn (giờ tường VN). */
    private LocalDateTime date;
    /** Ngày giờ hiển thị ({@code dd/MM/yyyy HH:mm}). */
    private String dateDisplay;

    /** Tên khách hàng — "Khách lẻ" nếu không chọn khách. */
    private String customerName;
    /** Tên nhân viên bán. */
    private String employeeName;

    /** Loại hóa đơn hiển thị (Bán hàng, Thay thế, …). */
    private String invoiceTypeDisplay;

    /** Tổng tiền hóa đơn sau giảm giá. */
    private BigDecimal total;
    /** Số tiền còn nợ trên hóa đơn. */
    private BigDecimal debtAmount;

    /** Hình thức thanh toán hiển thị (Tiền mặt, Ghi nợ, …). */
    private String paymentDisplay;

    /** Có phải hóa đơn thuốc kê đơn hay không. */
    private boolean prescriptionRequired;

    /** Trạng thái trả hàng hiển thị. */
    private String returnStatusDisplay;
    /** Class CSS badge trạng thái trả hàng. */
    private String returnStatusCssClass;

    /** Tên trạng thái hóa đơn (Hoàn thành, Còn nợ, …). */
    private String statusName;
    /** Class CSS badge trạng thái hóa đơn. */
    private String statusCssClass;
}
