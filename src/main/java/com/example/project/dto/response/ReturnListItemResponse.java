package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/** One row in the customer-return list. */
@Getter
@AllArgsConstructor
public class ReturnListItemResponse {

    private Integer id;
    private String code;

    private Instant date;
    private String dateDisplay;

    private String invoiceCode;
    private String customerName;

    private String createdByName;

    private long totalItems;

    /** Tiền hoàn ĐÃ áp tỷ lệ hoàn (Σ lineRefund), không phải giá trị gốc 100%. */
    private BigDecimal totalRefund;

    /** Phần tiền hoàn đã cấn trừ vào công nợ của chính hóa đơn gốc; chốt lúc duyệt. */
    private BigDecimal offsetDebtAmount;

    /** {@code totalRefund − offsetDebtAmount} — tiền thật còn phải trả khách, phần phiếu chi chi ra. */
    private BigDecimal cashRefundDue;

    private String returnType;
    private String returnTypeDisplay;

    private String statusName;
    private String statusCssClass;
}
