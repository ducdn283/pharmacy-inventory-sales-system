package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Một đơn vị quy đổi của sản phẩm — dropdown chọn đơn vị trên form dự trù. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcurementProductUnitResponse {
    /** Tên đơn vị (VD: Viên, Vỉ, Hộp). */
    private String unitName;
    /** Tỷ lệ quy đổi so với đơn vị cơ sở. */
    private BigDecimal ratio;
    /** Có phải đơn vị cơ sở (nhỏ nhất) không. */
    private boolean baseUnit;
}
