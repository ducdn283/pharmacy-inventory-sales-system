package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Một hóa đơn chọn được trong hộp thoại "Chọn hóa đơn trả hàng" trên màn tạo phiếu trả. */
@Getter
@AllArgsConstructor
public class ReturnableInvoiceResponse {

    /** Id hóa đơn. */
    private Integer invoiceId;
    /** Mã hóa đơn hiển thị. */
    private String invoiceCode;

    /** Ngày giờ lập hóa đơn hiển thị. */
    private String dateDisplay;

    /** Tên nhân viên bán. */
    private String employeeName;
    /** Tên khách hàng. */
    private String customerName;

    /** Tổng tiền hóa đơn. */
    private BigDecimal total;

    /**
     * Công nợ còn lại của hóa đơn. Hóa đơn còn nợ vẫn trả hàng được: tiền hoàn sẽ cấn trừ vào
     * khoản nợ này trước, phần dư mới chi ra cho khách.
     */
    private BigDecimal debtAmount;

    /** Trạng thái trả hiện tại (chưa trả / trả một phần) — hóa đơn trả toàn bộ bị loại khỏi danh sách. */
    private String returnStatusDisplay;

    /**
     * Tên các mặt hàng còn trả được, hiện dưới số hóa đơn để người lập nhận ra hóa đơn phù hợp.
     */
    private String productSummary;

    /**
     * Chuỗi tìm kiếm hộp thoại: số hóa đơn + tên khách + tên/mã sản phẩm.
     * Không chứa số điện thoại — xem {@code ReturnService.searchTextOf}.
     */
    private String searchText;
}
