package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Một dòng {@link com.example.project.entity.Procurementplandetail} gợi ý cho form tạo phiếu nhập
 * khi đã chọn nhà cung cấp và phiếu dự trù — dùng để điền sẵn số lượng/giá (xem
 * {@link com.example.project.service.PurchaseinvoiceService#getProcurementPlanDetailsForSupplier}).
 * Chỉ mang tính gợi ý; người dùng vẫn có thể sửa trước khi lưu.
 */
@Getter
@AllArgsConstructor
public class ProcurementPlanDetailOptionResponse {

    /** Id hàng hóa. */
    private Integer productId;
    /** Tên hàng hóa. */
    private String productName;
    /** Số lượng dự trù trên phiếu. */
    private Integer requestedQuantity;
    /** Đơn vị dự trù. */
    private String unit;

    /** Tổng giá ước tính cho {@code requestedQuantity} — đã ghi trên phiếu dự trù. */
    private BigDecimal estimatedPrice;

    /** {@code estimatedPrice ÷ requestedQuantity} — dùng trực tiếp làm "Giá nhập" gợi ý. */
    private BigDecimal unitPrice;
}
