package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Dữ liệu một sản phẩm được dùng để xem trước
 * và xuất file PDF barcode.
 */
@Getter
@AllArgsConstructor
public class ProductBarcodePrintResponse {

    private final Integer productId;

    /**
     * Nội dung được chuyển thành barcode.
     * Không sử dụng cột Product.barcode.
     */
    private final String code;

    private final String name;

    private final String unitName;

    private final BigDecimal sellPrice;

    private final Integer quantity;
}