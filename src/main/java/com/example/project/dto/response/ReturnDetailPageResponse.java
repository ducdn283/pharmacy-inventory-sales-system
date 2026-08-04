package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Everything the customer-return detail screen needs for one slip. */
@Getter
@AllArgsConstructor
public class ReturnDetailPageResponse {

    private Integer id;
    private String code;

    private Instant date;
    private String dateDisplay;

    private Integer invoiceId;
    private String invoiceCode;
    private String customerName;

    private String createdByName;

    private String returnType;
    private String returnTypeDisplay;

    private String reason;
    private String note;

    private String statusName;
    private String statusCssClass;
    private String approvedAtDisplay;

    private long totalItems;
    private int totalQuantity;

    /** Số tiền THỰC hoàn = Σ lineRefund (đã áp tỷ lệ hoàn). */
    private BigDecimal totalRefund;
    /** Phần tiền hoàn được cấn trừ vào công nợ hóa đơn gốc = MIN(totalRefund, dư nợ lúc duyệt). */
    private BigDecimal offsetDebtAmount;
    /** Tiền thật còn phải hoàn cho khách sau bù trừ = totalRefund − offsetDebtAmount (phiếu chi chi ra). */
    private BigDecimal cashRefundDue;

    /** Tỷ lệ % hoàn áp cho phiếu này (Return.appliedRefundRate). */
    private BigDecimal appliedRefundRate;
    /** Tổng giá trị GỐC 100% của hàng trả, trước khi áp tỷ lệ hoàn = Σ originalLineValue. */
    private BigDecimal totalOriginalValue;
    /** Phần nhà thuốc GIỮ LẠI = totalOriginalValue − totalRefund; vẫn là doanh thu chịu thuế bình thường. */
    private BigDecimal retainedAmount;

    private List<ReturnDetailItemResponse> items;
}
