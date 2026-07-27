package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of the supplier-return list screen. */
@Getter
@AllArgsConstructor
public class ReturnPurchaseListItemResponse {

    private Integer id;
    private String returnCode;
    private Instant returnDate;
    private String returnDateDisplay;

    private String purchaseCode;
    private String supplierName;
    private String creatorName;

    private int itemCount;
    private BigDecimal totalRefund;

    /**
     * Số NCC CÒN phải hoàn — số dư động, không phải mốc cố định: lúc duyệt phiếu nó bằng
     * {@code totalRefund}, rồi {@code IncomeService} trừ dần mỗi lần NCC hoàn tiền, về 0 là đã thu đủ.
     */
    private BigDecimal offsetDebtAmount;

    private String returnType;
    private String returnTypeDisplay;

    private String status;
    private String statusCssClass;
}
