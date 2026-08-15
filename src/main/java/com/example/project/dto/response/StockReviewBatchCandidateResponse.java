package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@AllArgsConstructor
public class StockReviewBatchCandidateResponse {

    private Integer batchId;

    private Integer productId;

    private String productCode;

    private String productName;

    private String lotNumber;

    private LocalDate expirationDate;

    private String expirationDateDisplay;

    private Integer systemQty;

    private Integer typeId;

    private String typeName;

    private List<String> positions;
}