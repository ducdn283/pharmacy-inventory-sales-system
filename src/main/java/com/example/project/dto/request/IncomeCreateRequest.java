package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Form tạo phiếu thu — người lập luôn là tài khoản hiện tại.
 * Chứng từ liên quan bắt buộc theo loại: hóa đơn nợ (khách hàng), phiếu trả NCC (nhà cung cấp),
 * phiếu điều chỉnh kho (nhân viên), báo cáo ca thiếu quỹ (thất thoát ca).
 */
@Getter
@Setter
public class IncomeCreateRequest {

    /** Một trong các mã loại phiếu thu — xem {@link com.example.project.dto.response.IncomeTypeOptionResponse}. */
    private String incomeType;

    /** Nội dung / lý do thu tiền. */
    private String reason;

    /** Tổng số tiền thu. */
    private BigDecimal amount;

    /** Số tiền thu bằng tiền mặt. */
    private BigDecimal paidByCash;

    /** Số tiền thu chuyển khoản. */
    private BigDecimal paidByBanking;

    /** Id nhà cung cấp — bắt buộc khi loại SUPPLIER. */
    private Integer supplierId;

    /** Id khách hàng — bắt buộc khi loại CUSTOMER. */
    private Integer customerId;

    /** Id người chịu trách nhiệm — bắt buộc khi loại EMPLOYEE hoặc SHIFT_SHORTAGE. */
    private Integer accountId;

    /** Id hóa đơn bán còn nợ — bắt buộc khi loại CUSTOMER. */
    private Integer invoiceId;

    /** Id phiếu trả NCC đã duyệt — bắt buộc khi loại SUPPLIER. */
    private Integer returnId;

    /** Id phiếu điều chỉnh kho — bắt buộc khi loại EMPLOYEE. */
    private Integer stockAdjustmentId;

    /** Id báo cáo ca còn thiếu tiền mặt — bắt buộc khi loại SHIFT_SHORTAGE. */
    private Integer shiftReportOfAccountId;

    /** Ghi chú trên phiếu thu. */
    private String note;
}
