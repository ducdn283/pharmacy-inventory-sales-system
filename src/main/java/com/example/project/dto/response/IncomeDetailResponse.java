package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Dữ liệu trang chi tiết phiếu thu — thông tin chung, đối tượng thu và chứng từ liên quan. */
@Getter
@AllArgsConstructor
public class IncomeDetailResponse {

    /** Id phiếu thu. */
    private Integer id;
    /** Mã hiển thị — dạng {@code PT-xxxxxx}. */
    private String code;
    /** Ngày giờ lập phiếu hiển thị. */
    private String dateDisplay;

    /** Loại phiếu thu hiển thị. */
    private String incomeTypeDisplay;
    /** Tên người lập phiếu. */
    private String applicantName;

    /** Nội dung / lý do thu. */
    private String reason;
    /** Tổng số tiền thu. */
    private BigDecimal amount;
    /** Số tiền thu tiền mặt. */
    private BigDecimal paidByCash;
    /** Số tiền thu chuyển khoản. */
    private BigDecimal paidByBanking;
    /** Số tiền cấn trừ công nợ (phiếu bù trừ). */
    private BigDecimal paidByCredit;
    /** Hình thức thanh toán hiển thị. */
    private String paymentDisplay;

    /** Tên trạng thái phiếu thu. */
    private String statusName;
    /** Class CSS badge trạng thái. */
    private String statusCssClass;

    /** Loại đối tượng thu (Khách hàng, NCC, Người chịu trách nhiệm, …). */
    private String partyTypeDisplay;
    /** Tên đối tượng thu. */
    private String partyName;

    /** Loại chứng từ liên quan hiển thị. */
    private String referenceTypeDisplay;
    /** Mã chứng từ liên quan. */
    private String referenceCode;
    /** Id hóa đơn bán liên quan — nếu thu nợ khách. */
    private Integer invoiceId;
    /** Id phiếu trả NCC liên quan — nếu thu nợ NCC. */
    private Integer returnId;
    /** Id phiếu điều chỉnh kho liên quan — nếu thu đền bù nhân viên. */
    private Integer stockAdjustmentId;
    /** Id báo cáo ca liên quan — nếu thu thất thoát ca. */
    private Integer shiftReportOfAccountId;

    /** Ghi chú trên phiếu thu. */
    private String note;
    /** Có thể hủy phiếu hay không — {@code false} với phiếu bù trừ công nợ. */
    private boolean cancellable;
    /** Lý do không hủy được — {@code null} khi {@link #cancellable} là {@code true}. */
    private String cancelBlockedReason;
}
