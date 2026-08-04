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

    // 3 cột vatRate/preTaxAmount/vatAmount đã bị BỎ khỏi `returndetail` (04/08/2026): hộ kinh doanh tính
    // GTGT bằng doanh thu × tỷ lệ % ở mọi nhóm, không khấu trừ đầu ra ⇒ phiếu trả không có số thuế nào
    // để giảm trừ. Doanh thu kỳ suy từ tổng hóa đơn CÒN HIỆU LỰC (Tax_Invoice.xlsx sheet 02).

    private boolean restockable;
    private String restockableDisplay;
}
