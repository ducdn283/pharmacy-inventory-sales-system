package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** One document row on the payable (nợ) detail screen. */
@Getter
@AllArgsConstructor
public class PayableLineResponse {

    private Integer id;
    private String code;
    private String dateDisplay;
    /** Full debt still owed — unchanged by {@code Chờ thanh toán} slips until money actually leaves. */
    private BigDecimal payableAmount;
    private String detail;
    /** Portion not yet on a live {@code Chờ thanh toán} slip — drives Hoàn tiền / Chi trả. */
    private BigDecimal remainingCreatable;
    /** {@code Chờ thanh toán} slips linked to this document — {@code id} is expense id. */
    private List<PayableLineResponse> awaitingExpenses;
}
