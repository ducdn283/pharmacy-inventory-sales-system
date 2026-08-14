package com.example.project.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Một dòng vị trí lưu kho trong form tạo hàng hóa (lưu thành Position). */
@Getter
@Setter
@NoArgsConstructor
public class ProductPositionCreateRequest {
    private String name;
}
