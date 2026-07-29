package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of the supplier-return list screen. */
@Getter
@AllArgsConstructor
public class ReturnPurchaseListItemResponse {

    private Integer id;
    private String returnCode;
    private Instant returnDate;
    private String returnDateDisplay;

    private String purchaseCode;
    private String supplierName;
    private String creatorName;

    private int itemCount;
    private BigDecimal totalRefund;

    /**
     * Phần giá trị hàng trả đã CẤN TRỪ vào công nợ nhà thuốc nợ NCC = {@code MIN(totalRefund, dư nợ phiếu
     * nhập lúc duyệt)}. Đây là số CỐ ĐỊNH (đặc tả bổ sung 27/07) — khác nghĩa cũ "NCC còn phải hoàn".
     */
    private BigDecimal offsetDebtAmount;

    /** Tiền thật NCC còn phải hoàn = {@code totalRefund − offsetDebtAmount}. */
    private BigDecimal cashRefundDue;

    private String returnType;
    private String returnTypeDisplay;

    private String status;
    private String statusCssClass;
}
