package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** One document row on the receivable (cho nợ) detail screen. */
@Getter
@AllArgsConstructor
public class ReceivableLineResponse {

    private Integer id;
    private String code;
    private String dateDisplay;
    private BigDecimal receivableAmount;
    private String detail;
}
