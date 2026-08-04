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
    /**
     * Số NCC THỰC hoàn của dòng = originalLineValue × appliedRefundRate.
     *
     * <p>KHÔNG còn tách net/thuế: 3 cột {@code vatRate/preTaxAmount/vatAmount} đã bị bỏ khỏi
     * {@code returndetail} (04/08/2026). Hộ kinh doanh không khấu trừ GTGT đầu vào ở bất kỳ nhóm nào
     * nên trả hàng NCC không có khoản thuế nào để đảo ngược — {@code lineRefund} là số gộp cuối cùng.</p>
     */
    private BigDecimal lineRefund;
}
