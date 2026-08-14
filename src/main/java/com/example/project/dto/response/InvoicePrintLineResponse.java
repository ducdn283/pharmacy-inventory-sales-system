package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Một dòng hàng trên mẫu in hóa đơn. */
@Getter
@AllArgsConstructor
public class InvoicePrintLineResponse {

    /** Mã sản phẩm. */
    private String productCode;
    /** Tên sản phẩm. */
    private String productName;
    /** Đơn vị bán. */
    private String unitName;
    /** Số lượng bán. */
    private Integer quantity;
    /** Đơn giá bán. */
    private BigDecimal unitSellPrice;
    /** Thành tiền dòng. */
    private BigDecimal lineSubtotal;
    /** Ghi chú dòng hàng. */
    private String note;
    /** Dòng tiền không kèm hàng trên hóa đơn thay thế ({@code quantity = 0}). */
    private boolean retainedMoneyLine;
    /** Tỷ lệ % nhà thuốc giữ lại (= 100% − tỷ lệ hoàn trên phiếu trả), hiển thị dưới thành tiền. */
    private String retainedPercentDisplay;
}
