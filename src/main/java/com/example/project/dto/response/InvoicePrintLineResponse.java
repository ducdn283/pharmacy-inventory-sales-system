package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class InvoicePrintLineResponse {

    private String productCode;
    private String productName;
    private String unitName;
    private Integer quantity;
    private BigDecimal unitSellPrice;
    private BigDecimal lineSubtotal;
    private String note;
    /** Dòng tiền không kèm hàng trên hóa đơn thay thế ({@code quantity = 0}). */
    private boolean retainedMoneyLine;
    /** Tỷ lệ % nhà thuốc giữ lại (= 100% − tỷ lệ hoàn trên phiếu trả), hiển thị dưới thành tiền. */
    private String retainedPercentDisplay;
}
