package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Một sản phẩm chọn được trên form bán hàng: thông tin hàng + tồn kho đơn vị cơ sở + các đơn vị bán.
 * Nhúng vào JavaScript inline để bộ chọn không phải tải chậm (lazy-load) quan hệ entity.
 */
@Getter
@AllArgsConstructor
public class SellProductOptionResponse {

    /** Id sản phẩm. */
    private Integer productId;
    /** Mã sản phẩm. */
    private String code;
    /** Tên sản phẩm. */
    private String name;
    /** Mã vạch. */
    private String barcode;

    /** Tồn kho theo đơn vị cơ sở (tổng {@code storageQuantity} các lô, kể cả lô hết hạn). */
    private long baseStock;

    /** Các đơn vị bán được cấu hình cho sản phẩm. */
    private List<SellUnitOptionResponse> units;

    /** Các lô còn hàng (HSD sớm nhất trước) — chọn lô khi bán. */
    private List<SellBatchOptionResponse> batches;

    /** {@code true} khi loại hàng là {@code Thuốc kê đơn}. */
    private boolean requiresPrescription;

    /** Vị trí kệ lưu trữ (VD: Kệ A1). */
    private List<String> storagePositions;
}
