package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** Khối sản phẩm trên trang chi tiết — gom các đơn vị bán khác nhau của cùng một hàng hóa. */
@Getter
@AllArgsConstructor
public class InvoiceDetailProductGroupResponse {

    /** Id sản phẩm. */
    private Integer productId;
    /** Mã sản phẩm. */
    private String productCode;
    /** Tên sản phẩm. */
    private String productName;
    /** Tổng tiền bán của sản phẩm (cộng các đơn vị). */
    private BigDecimal productSubtotal;
    /** Các dòng đơn vị bán khác nhau của sản phẩm. */
    private List<InvoiceDetailUnitLineResponse> lines;
}
