package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** One product line on the customer-return detail screen. */
@Getter
@AllArgsConstructor
public class ReturnDetailItemResponse {

    private Integer productId;
    private String productName;

    private String lotNumber;
    private String expirationDateDisplay;

    private String unitName;

    private Integer returnQty;
    private BigDecimal unitSellPrice;
    /** Giá trị GỐC 100% của dòng trả, TRƯỚC khi áp tỷ lệ hoàn (Returndetail.originalLineValue). */
    private BigDecimal originalLineValue;
    /** Tiền THỰC hoàn của dòng = originalLineValue × appliedRefundRate. */
    private BigDecimal lineRefund;

    // Thuế GTGT giảm trừ của dòng, tách TỪ TRONG tiền hoàn theo đúng thuế suất của dòng hóa đơn gốc
    // (Huong_dan_tinh_thue sheet 04: net = tiền hoàn ÷ (1+t), thuế = tiền hoàn − net). Phiếu trả không
    // chi tiền thật, nhưng phải kê đủ 3 số này để phiếu chi/kế toán lấy chi tiết mà giảm trừ đúng kỳ.
    private BigDecimal vatRate;
    private BigDecimal preTaxAmount;
    private BigDecimal vatAmount;

    private boolean restockable;
    private String restockableDisplay;
}
