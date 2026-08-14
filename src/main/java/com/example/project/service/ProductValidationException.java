package com.example.project.service;

import java.util.List;

/**
 * Ném ra bởi {@link ProductService#createProduct} khi dữ liệu sản phẩm gửi lên không hợp lệ.
 * Mang theo danh sách thông báo lỗi để controller render lại form cho người dùng.
 */
public class ProductValidationException extends RuntimeException {

    private final List<String> errors;

    public ProductValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = errors;
    }

    public List<String> getErrors() {
        return errors;
    }
}
