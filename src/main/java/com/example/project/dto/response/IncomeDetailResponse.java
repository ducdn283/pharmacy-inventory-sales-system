package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Full income slip detail page payload. */
@Getter
@AllArgsConstructor
public class IncomeDetailResponse {

    private Integer id;
    private String code;
    private String dateDisplay;

    private String incomeTypeDisplay;
    private String applicantName;

    private String reason;
    private BigDecimal amount;
    private BigDecimal paidByCash;
    private BigDecimal paidByBanking;
    private String paymentDisplay;

    private String statusName;
    private String statusCssClass;

    private String partyTypeDisplay;
    private String partyName;

    private String referenceTypeDisplay;
    private String referenceCode;
    private Integer invoiceId;
    private Integer returnId;
    private Integer stockAdjustmentId;

    private String note;
}
