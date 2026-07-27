package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** Payable (nợ) detail page for one counterparty. */
@Getter
@AllArgsConstructor
public class PayableDetailResponse {

    private Integer entityId;
    private String name;
    private String partyType;
    private String partyTypeDisplay;
    private BigDecimal totalPayable;
    private List<PayableLineResponse> lines;
}
