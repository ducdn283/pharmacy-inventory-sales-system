package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class ExpenseListItemResponse {

    private Integer id;
    private String code;
    private String dateDisplay;

    private String expenseType;
    private String expenseTypeDisplay;

    private String applicantName;

    private BigDecimal amount;
    private BigDecimal paid;

    private String statusName;
    private String statusCssClass;
}
