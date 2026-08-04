package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One selectable "Đã duyệt" stock review in the create-adjustment picker. Read-only projection of
 * a {@code Stockreview} (owned by the separate Stock Review screen) plus how many of its lines have a
 * real discrepancy — i.e. would become adjustment lines. Belongs to the stock-adjustment feature.
 */
@Getter
@AllArgsConstructor
public class StockAdjustmentReviewOptionResponse {

    private Integer stockReviewId;
    private String stockReviewCode;

    /**
     * Loại phiếu kiểm kê: {@code COUNT} (đếm số lượng) / {@code DATE} (kiểm hạn dùng) /
     * {@code CONDITION} (kiểm tình trạng). Quyết định loại phiếu điều chỉnh được sinh ra, nên màn tạo
     * phải lọc đúng loại thay vì hiện chung một danh sách.
     */
    private String reviewType;
    private String reviewTypeDisplay;

    private String countDateDisplay;

    /**
     * Số dòng sẽ trở thành dòng điều chỉnh: với {@code COUNT} là số dòng lệch số lượng, với
     * {@code DATE} là số lô lệch hạn dùng, với {@code CONDITION} là số lô có ghi nhận tình trạng.
     */
    private int discrepancyLineCount;

    private String note;
}
