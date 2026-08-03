package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One selectable "Đã duyệt" stock count in the create-adjustment dropdown. Read-only projection of
 * a {@code Stockcount} (owned by the separate Stock Count screen) plus how many of its lines have a
 * real discrepancy — i.e. would become adjustment lines. Belongs to the stock-adjustment feature.
 */
@Getter
@AllArgsConstructor
public class StockAdjustmentCountOptionResponse {

    private Integer stockReviewId;
    private String stockReviewCode;

    private String countDateDisplay;

    /** Number of detail lines with actualQty != systemQty (and a batch) — the lines we can adjust. */
    private int discrepancyLineCount;

    private String note;
}
