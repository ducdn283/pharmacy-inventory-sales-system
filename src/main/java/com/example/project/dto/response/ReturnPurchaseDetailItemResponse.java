package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** One product line on the supplier-return detail screen. */
@Getter
@AllArgsConstructor
public class ReturnPurchaseDetailItemResponse {

    private Integer productId;
    private String productName;
    private String lotNumber;
    private String expirationDateDisplay;
    private String unitName;
    private Integer returnQty;
    private BigDecimal unitImportPrice;
    /** Giá trị nhập GỐC 100% của dòng trả, TRƯỚC khi áp tỷ lệ NCC chấp nhận hoàn. */
    private BigDecimal originalLineValue;
    /** Số NCC THỰC hoàn của dòng = originalLineValue × appliedRefundRate. */
    private BigDecimal lineRefund;

    // Thuế GTGT ĐẦU VÀO phải giảm trừ cho dòng này, tách từ giá nhập gross theo thuế suất trên phiếu
    // nhập gốc. Chỉ có giá trị với Nhóm 3/4 (khấu trừ) — Nhóm 2 chưa từng khấu trừ nên không có gì đảo
    // (Huong_dan_tinh_thue sheet 12, mục 5B).
    private BigDecimal vatRate;
    private BigDecimal preTaxAmount;
    private BigDecimal vatAmount;
}
