package com.example.project.dto.response;

import com.example.project.entity.Type;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Dữ liệu loại hàng trả về màn danh sách / form sửa Owner. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TypeResponse {
    /** Mã loại — hiển thị dạng {@code LH-xxxxx}. */
    private Integer id;
    /** Nhóm mặt hàng ({@code thuốc}, {@code hàng hóa}, …). */
    private String sortType;
    /** Tên loại hàng. */
    private String name;
    /** Tỷ lệ VAT mặc định (%). */
    private BigDecimal defaultVATRate;

    /** Ánh xạ entity {@link com.example.project.entity.Type} sang DTO hiển thị. */
    public static TypeResponse from(Type type) {
        return new TypeResponse(
                type.getId(),
                type.getSortType(),
                type.getName(),
                type.getDefaultVATRate()
        );
    }
}