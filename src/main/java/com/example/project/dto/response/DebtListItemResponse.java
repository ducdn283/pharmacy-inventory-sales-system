package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** One row on the debt list screen. */
@Getter
@AllArgsConstructor
public class DebtListItemResponse {

    private Integer entityId;
    private String name;
    /** Party type code: {@code CUSTOMER} or {@code SUPPLIER}. */
    private String partyType;
    private String partyTypeDisplay;
    /** Amount we owe the counterparty (payable). */
    private BigDecimal payableAmount;
    /** Amount the counterparty owes us (receivable). */
    private BigDecimal receivableAmount;
}
