package com.example.project.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Một dòng đơn vị quy đổi trong form tạo hàng hóa. Các đơn vị nhập theo thứ tự nhỏ → lớn.
 *
 * <p>{@code quantityRelativeToPrevious} là số lượng đơn vị của dòng TRƯỚC ĐÓ gộp thành 1 đơn vị
 * này (vd. "1 vỉ = 10 viên" → 10). Service sẽ quy đổi thành {@code ProductUnit.ratio} tích luỹ
 * (so với đơn vị nhỏ nhất/cơ bản). {@code sellPrice} là giá bán thật; để trống thì service tự
 * lấy giá đề xuất (giá đơn vị cơ bản × ratio).</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ProductUnitCreateRequest {
    private String unitName;
    /** Số lượng đơn vị trước quy đổi ra 1 đơn vị này. Bỏ qua với dòng cơ bản (dòng đầu tiên). */
    private Integer quantityRelativeToPrevious;
    /** Giá bán thật của đơn vị này; để trống/0 → service dùng giá đề xuất. */
    private BigDecimal sellPrice;
    private boolean baseUnit;
    private boolean defaultUnit;
    private boolean active;
}
