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

    /**
     * Công nợ còn lại của hóa đơn. Hóa đơn còn nợ VẪN trả hàng được (bỏ gate 28/07): tiền hoàn sẽ được
     * cấn trừ vào đúng khoản nợ này trước, phần dư mới thực sự phải chi ra cho khách.
     */
    private BigDecimal debtAmount;

    /** Current return status of the invoice (NONE / PARTIAL) — FULL invoices are excluded from the list. */
    private String returnStatusDisplay;

    /**
     * Tên các mặt hàng còn trả được, hiện thành dòng phụ dưới số hóa đơn: người lập gõ tên thuốc thì
     * nhìn ra ngay vì sao hóa đơn này khớp.
     */
    private String productSummary;

    /**
     * Chuỗi cho ô tìm kiếm của modal: số hóa đơn + tên khách + tên/mã sản phẩm.
     * KHÔNG chứa số điện thoại — xem {@code ReturnService.searchTextOf}.
     */
    private String searchText;
}
