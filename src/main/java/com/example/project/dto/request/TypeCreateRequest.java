package com.example.project.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Form tạo / cập nhật loại hàng — chỉ Owner gửi từ màn quản lý loại. */
@Getter
@Setter
public class TypeCreateRequest {

    /** Nhóm mặt hàng — dùng lọc và gom loại trên danh sách. */
    @NotBlank(message = "Nhóm mặt hàng không được để trống")
    @Size(max = 100, message = "Nhóm mặt hàng không được vượt quá 100 ký tự")
    private String sortType;

    /** Tên loại hàng hiển thị khi gán cho sản phẩm. */
    @NotBlank(message = "Tên loại hàng không được để trống")
    @Size(max = 100, message = "Tên loại hàng không được vượt quá 100 ký tự")
    private String name;

    /** Tỷ lệ thuế GTGT mặc định (%) cho sản phẩm thuộc loại này. */
    @NotNull(message = "Tỷ lệ VAT mặc định không được để trống")
    @DecimalMin(value = "0.0", message = "Tỷ lệ VAT mặc định không được âm")
    private BigDecimal defaultVATRate;
}
