package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Dữ liệu trang chi tiết hóa đơn bán hàng — phần đầu trang, tổng tiền và danh sách dòng hàng. */
@Getter
@AllArgsConstructor
public class InvoiceDetailPageResponse {

    /** Id hóa đơn. */
    private Integer id;
    /** Mã hóa đơn hiển thị. */
    private String invoiceCode;
    /** Ký hiệu mẫu số hóa đơn. */
    private String invoicePattern;
    /** Mã số thuế đơn vị. */
    private String taxCode;

    /** Ngày giờ lập hóa đơn. */
    private LocalDateTime date;
    /** Ngày giờ hiển thị ({@code dd/MM/yyyy HH:mm}). */
    private String dateDisplay;

    /** Tên khách hàng. */
    private String customerName;
    /** Số điện thoại khách hàng. */
    private String customerPhone;

    /** Tên nhân viên bán. */
    private String employeeName;

    /** Loại hóa đơn hiển thị. */
    private String invoiceTypeDisplay;

    /** Tên trạng thái hóa đơn. */
    private String statusName;
    /** Class CSS badge trạng thái hóa đơn. */
    private String statusCssClass;

    /** Có phải hóa đơn thuốc kê đơn hay không. */
    private boolean prescriptionRequired;
    /** Mã đơn thuốc. */
    private String prescriptionCode;

    /** Trạng thái trả hàng hiển thị. */
    private String returnStatusDisplay;
    /** Class CSS badge trạng thái trả hàng. */
    private String returnStatusCssClass;

    /** Ánh xạ id phiếu trả → mã phiếu trả. */
    private Map<Integer, String> returnSlips;

    /** Id hóa đơn gốc — nếu là hóa đơn thay thế. */
    private Integer originalInvoiceId;
    /** Mã hóa đơn gốc. */
    private String originalInvoiceCode;

    /** Tiền hàng trước giảm giá. */
    private BigDecimal subtotal;
    /** Số tiền giảm giá. */
    private BigDecimal discount;
    /** Tổng giá vốn hàng bán — chỉ điền khi Chủ nhà thuốc xem chi tiết. */
    private BigDecimal totalCost;
    /** Tổng tiền sau giảm giá. */
    private BigDecimal total;

    /** Số tiền trả tiền mặt. */
    private BigDecimal paidByCash;
    /** Số tiền trả chuyển khoản. */
    private BigDecimal paidByBanking;
    /** Số tiền còn nợ. */
    private BigDecimal debtAmount;
    /** Hình thức thanh toán hiển thị. */
    private String paymentDisplay;

    /** Ghi chú trên hóa đơn. */
    private String note;

    /** Số loại sản phẩm khác nhau trên hóa đơn. */
    private long totalItems;
    /** Tổng số lượng bán (cộng tất cả dòng). */
    private int totalQuantity;

    /** Danh sách dòng hàng phẳng — bảng chi tiết. */
    private List<InvoiceDetailItemResponse> items;

    /** Nhóm theo sản phẩm (một sản phẩm có thể bán nhiều đơn vị). */
    private List<InvoiceDetailProductGroupResponse> productGroups;
}
