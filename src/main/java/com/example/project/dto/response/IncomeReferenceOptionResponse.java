package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** One selectable reference document on the create-income screen. */
@Getter
@AllArgsConstructor
public class IncomeReferenceOptionResponse {

    private Integer id;
    private String code;
    private String dateDisplay;
    private BigDecimal amount;
    private String detail;
}
