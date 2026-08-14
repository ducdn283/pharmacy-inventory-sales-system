package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Một dòng đơn vị (một {@code ProductUnit}) có thể sửa giá, nằm trong sản phẩm cha
 * ({@link PriceSettingProductRowResponse}) — thông tin sản phẩm (mã, tên, loại, giá nhập trung
 * bình) không lặp lại ở đây vì bảng đã nhóm theo sản phẩm.
 */
@Getter
@AllArgsConstructor
public class PriceSettingRowResponse {

    private Integer productUnitId;
    private String unitName;
    private boolean baseUnit;
    private BigDecimal ratio;

    private BigDecimal currentSellPrice;
}
