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

    /** "Tiền mặt" / "Chuyển khoản" / "TM + CK" / "—", suy ra từ paidByCash + paidByBanking. */
    private String paymentDisplay;

    private String statusName;
    private String statusCssClass;
}
