package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Sản phẩm được hiển thị trong kết quả tìm kiếm
 * và danh sách chọn in barcode.
 */
@Getter
@AllArgsConstructor
public class ProductBarcodeOptionResponse {

    private final Integer productId;

    /**
     * Nội dung dùng để sinh barcode Code 128.
     * Giá trị lấy trực tiếp từ Product.code.
     */
    private final String code;

    private final String name;

    private final String unitName;

    private final BigDecimal sellPrice;

    /**
     * Số lượng tem mặc định.
     * Khi thêm từ hóa đơn nhập, giá trị này là số lượng thực nhập.
     */
    private final Integer quantity;
}