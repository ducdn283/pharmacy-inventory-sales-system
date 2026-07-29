package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class ExpenseStatsResponse {

    private long monthlyCount;
    private BigDecimal monthlyPaidTotal;
    private long pendingCount;
    private long awaitingPaymentCount;
}
