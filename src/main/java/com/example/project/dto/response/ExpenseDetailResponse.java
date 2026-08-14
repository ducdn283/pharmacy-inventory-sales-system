package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class ExpenseDetailResponse {

    private Integer id;
    private String code;
    private String dateDisplay;

    private String expenseType;
    private String expenseTypeDisplay;

    private String applicantName;

    private String reason;

    private BigDecimal amount;
    private BigDecimal paid;
    private BigDecimal paidByCash;
    private BigDecimal paidByBanking;
    private BigDecimal paidByCredit;
    private String paymentDisplay;

    private String statusName;
    private String statusCssClass;

    private String approvedByName;
    private String approvedAtDisplay;

    private String note;

    /** Phiếu trả hàng liên kết, {@code null} nếu phiếu chi này không phải hoàn tiền. */
    private Integer returnId;
    private String returnCode;

    /** Khách hàng của hóa đơn gốc thuộc phiếu trả hàng liên kết; {@code null} nếu là khách lẻ. */
    private String customerName;

    /** Phiếu nhập liên kết, {@code null} nếu phiếu chi này không tất toán phiếu nhập nào. */
    private Integer purchaseId;
    private String purchaseCode;

    /** Nhà cung cấp của phiếu nhập liên kết. */
    private String supplierName;

    /** Ca làm việc được đóng dấu, {@code null} nếu lúc đó không có ca mở (luôn vậy với Kế toán). */
    private Integer shiftReportId;
    private String shiftReportCode;

    /**
     * Số tiền phiếu có dưới ngưỡng {@code ExpenseType.PHARMACIST_AUTO_APPROVE_LIMIT} hay không —
     * ngưỡng mà chính Dược sĩ lập phiếu được tự xác nhận thanh toán thay vì chờ Chủ nhà thuốc.
     * Route chi tiết đã tự giới hạn Dược sĩ chỉ xem phiếu của mình, nên template chỉ cần kiểm thêm
     * cờ này.
     */
    private boolean pharmacistCanConfirmPayment;
}
