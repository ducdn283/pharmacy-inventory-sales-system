package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Một đơn vị quy đổi + giá bán của sản phẩm, dùng cho màn Chi tiết hàng hóa. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductUnitDetailResponse {
    private String unitName;
    private BigDecimal ratio;
    private BigDecimal sellPrice;
    private boolean isDefault;
    private boolean isBaseUnit;
    private boolean isActive;
}
