package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** One document row on the payable (nợ) detail screen. */
@Getter
@AllArgsConstructor
public class PayableLineResponse {

    private Integer id;
    private String code;
    private String dateDisplay;
    private BigDecimal payableAmount;
    private String detail;
}
