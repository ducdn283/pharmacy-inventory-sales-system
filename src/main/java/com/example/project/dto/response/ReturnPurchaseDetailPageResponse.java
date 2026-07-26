package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Everything the supplier-return detail screen renders for one slip. */
@Getter
@AllArgsConstructor
public class ReturnPurchaseDetailPageResponse {

    private Integer id;
    private String returnCode;
    private Instant returnDate;
    private String returnDateDisplay;

    private Integer purchaseId;
    private String purchaseCode;
    private String supplierName;
    private String creatorName;

    private String returnType;
    private String returnTypeDisplay;
    private String reason;
    private String note;

    private String status;
    private String statusCssClass;
    private String approvedAtDisplay;

    private int itemCount;
    private int totalQuantity;

    private BigDecimal totalRefund;
    private BigDecimal offsetDebtAmount;

    private List<ReturnPurchaseDetailItemResponse> items;
}
