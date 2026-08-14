package com.example.project.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Vị trí lưu kho tùy chọn trong form tạo/sửa hàng hóa (lưu thành Position). */
@Getter
@Setter
@NoArgsConstructor
public class ProductPositionCreateRequest {
    private String name;
}
