package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Một dòng hàng trên trang chi tiết hóa đơn. */
@Getter
@AllArgsConstructor
public class InvoiceDetailItemResponse {

    /** Id sản phẩm. */
    private Integer productId;
    /** Mã sản phẩm. */
    private String productCode;
    /** Tên sản phẩm. */
    private String productName;
    /** Mã lô hàng. */
    private String batchCode;
    /** Số lô hàng. */
    private String lotNumber;
    /** Hạn sử dụng hiển thị. */
    private String expirationDateDisplay;
    /** Nhãn lô hiển thị (mã lô + HSD). */
    private String batchLabel;
    /** Đơn vị bán. */
    private String unitName;
    /** Có phải đơn vị mặc định của sản phẩm hay không. */
    private boolean defaultUnit;
    /** Số lượng bán. */
    private Integer quantity;
    /** Đơn giá bán. */
    private BigDecimal unitSellPrice;
    /** Thành tiền dòng. */
    private BigDecimal subtotal;
    /** Số lượng đã trả. */
    private Integer returnedQty;
    /** Ghi chú dòng hàng. */
    private String note;
}
