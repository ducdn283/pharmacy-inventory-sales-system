package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class ShiftReportListItemResponse {

    private Integer id;

    private String shiftReportCode;

    private String cashierName;

    private String shiftDateDisplay;

    private String shiftType;

    private String startTimeDisplay;

    private String endTimeDisplay;

    private BigDecimal openingCash;

    private BigDecimal totalRevenue;

    private BigDecimal totalReturnAmount;

    private BigDecimal cashDiscrepancy;

    private String status;

    private String statusCssClass;
}
