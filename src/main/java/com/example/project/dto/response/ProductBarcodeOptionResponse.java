package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Dữ liệu sản phẩm trả về cho chức năng tìm kiếm và chọn sản phẩm
 * trong modal in barcode.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductBarcodeOptionResponse {

    /**
     * Khóa chính của sản phẩm.
     */
    private Integer productId;

    /**
     * Mã hàng hóa nội bộ, ví dụ: SP001.
     */
    private String code;

    /**
     * Tên sản phẩm.
     */
    private String name;

    /**
     * Mã barcode đang được lưu trong Product.
     */
    private String barcode;

    /**
     * Tên đơn vị bán mặc định.
     *
     * Nếu sản phẩm không có đơn vị mặc định thì sử dụng đơn vị cơ bản.
     */
    private String unitName;

    /**
     * Giá bán của đơn vị được chọn để hiển thị trên tem.
     */
    private BigDecimal sellPrice;
}