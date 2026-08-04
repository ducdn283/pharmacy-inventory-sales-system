package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Form backing the "create stock adjustment" screen. Carries the slip-level adjustment type,
 * reason/note plus the per-batch lines the user picked (manual source) — or a chosen stock count
 * (stock-review source), from which the server rebuilds the lines itself.
 */
@Getter
@Setter
public class StockAdjustmentCreateRequest {

    /** Source of the lines: {@code MANUAL} (default, user picks batches) or {@code STOCK_REVIEW}. */
    private String sourceMode;

    /**
     * Phiếu rà soát kho đã duyệt được chọn. Mang hai nghĩa tùy ngữ cảnh:
     * <ul>
     *   <li>{@code sourceMode = STOCK_REVIEW} — phiếu NGUỒN, hệ thống dựng dòng điều chỉnh từ nó;</li>
     *   <li>phiếu hủy hàng lập tay — phiếu rà soát tình trạng gắn kèm làm CĂN CỨ (tùy chọn).</li>
     * </ul>
     * Hai ngữ cảnh loại trừ nhau nên dùng chung một trường.
     */
    private Integer stockReviewId;

    /**
     * One of DESTROY / DESTROY_EMPLOYEE_FAULT / INTERNAL_USE / SAMPLE / GIFT.
     * {@code COUNT} và {@code DATE_ADJUSTMENT} suy ra từ loại phiếu rà soát kho, không chọn tay.
     */
    private String adjustmentType;

    /**
     * Nguồn STOCK_REVIEW, các dòng THỪA: id lô mà người lập đánh dấu <em>không xác định được nguồn
     * gốc</em>. Với những lô này hệ thống <strong>tạo lô MỚI</strong> (không có hóa đơn mua thật)
     * thay vì cộng thẳng vào lô cũ — {@code Dac_ta_Income_StockAdjustment.xlsx} sheet 06 mục 1.
     *
     * <p>Lô không được đánh dấu = trường hợp (a): lỗi ghi sổ của một lô đã biết, cộng thẳng vào lô
     * đó, không phát sinh ảnh hưởng thuế nào.</p>
     */
    private List<Integer> unknownOriginBatchIds = new ArrayList<>();

    private String reason;

    private String note;

    private List<StockAdjustmentItemRequest> items = new ArrayList<>();
}
