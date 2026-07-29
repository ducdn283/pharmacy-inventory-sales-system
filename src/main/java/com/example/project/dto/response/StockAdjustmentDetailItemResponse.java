package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class StockAdjustmentDetailItemResponse {

    private Integer productId;
    private String productName;
    private String lotNumber;
    private LocalDate expirationDate;
    private String expirationDateDisplay;
    private String unitName;
    /** Current on-hand stock of the batch (live value; after approval it already reflects this line). */
    private Integer currentStock;
    /** The adjusted amount (delta), always positive; pair it with {@link #direction} for the sign. */
    private Integer quantity;
    /** Movement direction of this line: {@code IN} (increase) or {@code OUT} (decrease). */
    private String direction;
    /** Vietnamese label for {@link #direction}: "Tăng" / "Giảm". */
    private String directionDisplay;
    private BigDecimal unitCostPrice;
    private BigDecimal lineCost;
    private String note;

    // Thuế GTGT đầu ra (chỉ INTERNAL_USE/GIFT/SAMPLE; null với DESTROY/COUNT_*).
    // refSellPrice = giá bán/đơn vị (đã gồm VAT); preTaxAmount/vatAmount tách net-thuế của giá trị tính thuế.
    private BigDecimal refSellPrice;
    private BigDecimal vatRate;
    private BigDecimal preTaxAmount;
    private BigDecimal vatAmount;

    /**
     * Giá trị đền bù của dòng, tính theo <strong>GIÁ BÁN</strong> niêm yết (không phải giá vốn) —
     * chỉ có ý nghĩa với phiếu {@code DESTROY_EMPLOYEE_FAULT} / {@code COUNT_DECREASE}, là số tiền
     * đề xuất cho phiếu thu "nhân viên đền bù". Tính LIVE lúc đọc, không lưu cột riêng.
     */
    private BigDecimal reimbursementUnitPrice;
    private BigDecimal reimbursementValue;
}