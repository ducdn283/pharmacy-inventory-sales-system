package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ApprovalStatsResponse {

    private long totalCount;

    private long returnCount;

    // Không còn stockAdjustmentCount: phiếu điều chỉnh kho bỏ bước duyệt từ 2026-07-27.

    private long stockCountCount;

    private long shiftReportCount;

    private long expenseCount;

    /** Phiếu nhập đang "Chờ duyệt" — bước soát lại trước khi hàng thật sự vào kho (thêm 04/08/2026). */
    private long purchaseInvoiceCount;
}
