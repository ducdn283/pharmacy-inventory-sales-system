package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** Receivable (cho nợ) detail page for one counterparty. */
@Getter
@AllArgsConstructor
public class ReceivableDetailResponse {

    private Integer entityId;
    private String name;
    private String partyType;
    private String partyTypeDisplay;
    private BigDecimal totalReceivable;
    private List<ReceivableLineResponse> lines;
}
