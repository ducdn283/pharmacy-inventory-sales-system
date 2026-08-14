package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Giá nhập của nhà cung cấp cho sản phẩm — API form dự trù ({@code /owner/procurements/supplier-cost-price}). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SupplierCostPriceResponse {
    /** Giá nhập đơn vị; {@code null} nếu chưa có liên kết NCC–sản phẩm. */
    private BigDecimal costPrice;
}
