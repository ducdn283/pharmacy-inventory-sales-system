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

    private BigDecimal totalRefund;
    private BigDecimal offsetDebtAmount;

    private List<ReturnDetailItemResponse> items;
}
