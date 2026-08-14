package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Một dòng hàng của hóa đơn — hộp thoại xem nhanh trên danh sách (dạng JSON). */
@Getter
@AllArgsConstructor
public class InvoiceLineResponse {

    /** Id dòng chi tiết hóa đơn. */
    private Integer id;
    /** Tên sản phẩm. */
    private String productName;
    /** Số lô hàng. */
    private String lotNumber;
    /** Hạn sử dụng hiển thị ({@code dd/MM/yyyy}). */
    private String expirationDate;
    /** Đơn vị bán. */
    private String unitName;
    /** Số lượng bán. */
    private Integer quantity;
    /** Đơn giá bán. */
    private BigDecimal unitSellPrice;
    /** Thành tiền dòng (= đơn giá × số lượng). */
    private BigDecimal subtotal;
    /** Số lượng đã trả qua các phiếu trả trước. */
    private Integer returnedQty;
    /** Ghi chú dòng hàng. */
    private String note;
}
