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

    // Ô nhập "Thuế suất GTGT đầu ra" đã bỏ 04/08/2026 — phiếu điều chỉnh kho không phát sinh thuế
    // (hộ kinh doanh tính GTGT bằng doanh thu × tỷ lệ %, không khấu trừ đầu ra).
}