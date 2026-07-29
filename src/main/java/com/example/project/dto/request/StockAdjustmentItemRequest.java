package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class StockAdjustmentItemRequest {

    private Integer batchId;

    private Integer quantity;

    private String reason;

    // Thuế suất GTGT đầu ra do người lập tự nhập — chỉ dùng cho INTERNAL_USE/GIFT/SAMPLE
    // (0 nếu chương trình khuyến mãi đã đăng ký hợp lệ). Bỏ qua với DESTROY/COUNT_*.
    private BigDecimal vatRate;
}