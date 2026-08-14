package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Một đơn vị bán của sản phẩm — nhúng vào form bán hàng cho JavaScript inline. */
@Getter
@AllArgsConstructor
public class SellUnitOptionResponse {

    /** Id đơn vị bán ({@code productUnitId}). */
    private Integer id;
    /** Tên đơn vị (VD: Vỉ, Hộp). */
    private String unitName;
    /** Hệ số quy đổi sang đơn vị cơ sở. */
    private BigDecimal ratio;
    /** Giá bán cấu hình trên đơn vị. */
    private BigDecimal sellPrice;
    /** Có phải đơn vị mặc định hay không. */
    private boolean defaultUnit;
}
