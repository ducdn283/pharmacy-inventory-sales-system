package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Kết quả tìm nhà cung cấp trên form dự trù — kèm giá nhập nếu đã chọn sản phẩm. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcurementSupplierSearchResponse {
    /** Id nhà cung cấp. */
    private Integer supplierId;
    /** Tên nhà cung cấp. */
    private String name;
    /** Giá nhập gần nhất của sản phẩm đã chọn (nếu có). */
    private BigDecimal lastCostPrice;
}
