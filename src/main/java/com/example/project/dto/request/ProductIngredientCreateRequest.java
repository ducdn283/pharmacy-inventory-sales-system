package com.example.project.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Một dòng hoạt chất trong form tạo hàng hóa (lưu thành MedicineAPI). */
@Getter
@Setter
@NoArgsConstructor
public class ProductIngredientCreateRequest {
    private String apiName;
    private String strength;
}
