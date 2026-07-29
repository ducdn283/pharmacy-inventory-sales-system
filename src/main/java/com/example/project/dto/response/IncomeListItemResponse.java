package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/** One row in the income list. */
@Getter
@AllArgsConstructor
public class IncomeListItemResponse {

    private Integer id;
    private String code;

    private Instant date;
    private String dateDisplay;

    private String incomeTypeDisplay;
    private String reason;
    private String applicantName;

    private BigDecimal amount;
    private String paymentDisplay;

    private String statusName;
    private String statusCssClass;
}
