package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Header counters on the customer-return list. */
@Getter
@AllArgsConstructor
public class ReturnStatsResponse {

    private long monthlyCount;
    private long draftCount;
    private long pendingCount;
    /** Đã duyệt và CÒN phải hoàn tiền — không gồm phiếu đã tất toán, xem {@link #completedCount}. */
    private long debtCount;
    /** Đã duyệt và không còn nghĩa vụ tiền (bù trừ hết nợ, hoặc phiếu chi đã trả xong). */
    private long completedCount;
    private long rejectedCount;
}
