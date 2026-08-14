package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Một dòng đơn vị bán trong khối sản phẩm trên trang chi tiết hóa đơn. */
@Getter
@AllArgsConstructor
public class InvoiceDetailUnitLineResponse {

    /** Id đơn vị bán. */
    private Integer productUnitId;
    /** Tên đơn vị bán. */
    private String unitName;
    /** Có phải đơn vị mặc định hay không. */
    private boolean defaultUnit;
    /** Số lượng bán theo đơn vị này. */
    private Integer quantity;
    /** Đơn giá bán. */
    private BigDecimal unitSellPrice;
    /** Thành tiền dòng. */
    private BigDecimal lineSubtotal;
    /** Số lượng đã trả. */
    private Integer returnedQty;
    /** Nhãn lô hiển thị. */
    private String batchLabel;
    /** Ghi chú dòng hàng. */
    private String note;
    /** Giá vốn / đơn vị bán — chỉ điền khi Chủ nhà thuốc xem chi tiết. */
    private BigDecimal unitCostPrice;
}
