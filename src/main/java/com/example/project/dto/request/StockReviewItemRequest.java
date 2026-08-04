package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Getter
@Setter
public class StockReviewItemRequest {

    private Integer batchId;

    private Integer actualQty;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate actualExpirationDate;

    private String conditionStatus;

    private String note;
}