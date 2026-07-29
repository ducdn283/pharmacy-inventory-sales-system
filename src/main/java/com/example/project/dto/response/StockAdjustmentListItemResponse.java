package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@AllArgsConstructor
public class StockAdjustmentListItemResponse {

    private Integer id;
    private String code;
    private Instant date;
    private String dateDisplay;

    private String adjustmentType;
    private String adjustmentTypeDisplay;

    // Bỏ createdByName cùng lúc DB bỏ cột createdBy (28/07/2026) — chỉ Owner thao tác màn này.

    private long totalItems;
    private BigDecimal estimatedValue;

    private String statusName;
    private String statusCssClass;
}