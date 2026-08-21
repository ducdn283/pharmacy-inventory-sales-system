package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Đại diện cho một sản phẩm trong danh sách tem barcode cần in.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductBarcodePrintItemResponse {

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
     * Giá trị barcode được dùng để sinh ảnh Code 128.
     */
    private String barcode;

    /**
     * Tên đơn vị bán mặc định hoặc đơn vị cơ bản.
     */
    private String unitName;

    /**
     * Giá bán được hiển thị trên tem.
     */
    private BigDecimal sellPrice;

    /**
     * Số lượng tem barcode cần in cho sản phẩm.
     */
    private Integer quantity;
}