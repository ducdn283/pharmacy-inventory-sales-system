package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Một dòng chi tiết hiển thị lại trên form tạo/sửa dự trù (từ {@code procurementPlanForm}). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcurementPlanDetailRowView {
    /** Id hàng hóa. */
    private Integer productId;
    /** Số lượng dự trù. */
    private Integer requestedQuantity;
    /** Đơn vị nhập. */
    private String unit;
    /** Tổng giá dự kiến dòng. */
    private BigDecimal estimatedPrice;
    /** Id nhà cung cấp dự kiến. */
    private Integer supplierId;
}
