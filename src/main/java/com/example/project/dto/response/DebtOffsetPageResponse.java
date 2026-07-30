package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@AllArgsConstructor
public class DebtOffsetPageResponse {

    private Integer entityId;
    private String name;
    private String partyType;
    private String partyTypeDisplay;
    private BigDecimal totalReceivable;
    private BigDecimal totalPayable;
    private BigDecimal maxOffset;
    private List<ReceivableLineResponse> receivableLines;
    private List<PayableLineResponse> payableLines;
}
