package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A prospective adjustment line derived from one stock-review detail. Phục vụ CẢ hai loại nguồn:
 * <ul>
 *   <li><b>{@code COUNT}</b> — dòng lệch số lượng. Thừa (actual &gt; system) → {@code IN}, thiếu →
 *       {@code OUT}; {@code adjustmentType} luôn là {@code COUNT} (đã gộp 04/08/2026).</li>
 *   <li><b>{@code DATE_ADJUSTMENT}</b> — dòng lệch hạn dùng. {@code direction = NONE} (không đụng
 *       tồn kho), số liệu nằm ở {@code oldExpirationDate}/{@code newExpirationDate}.</li>
 *   <li><b>{@code DESTROY}</b> — dòng hàng không đạt chuẩn của phiếu rà soát tình trạng, luôn
 *       {@code OUT}. {@code quantity} là số lượng KHÔNG ĐẠT CHUẨN của lô, không phải tồn cả lô.</li>
 * </ul>
 *
 * <p>Purely read-only preview data for the create screen; the server rebuilds these authoritatively
 * on submit and never trusts posted quantities. Belongs to the stock-adjustment feature.</p>
 */
@Getter
@AllArgsConstructor
public class StockAdjustmentReviewLineResponse {

    private Integer batchId;

    private Integer productId;
    private String productName;

    private String lotNumber;

    private LocalDate expirationDate;
    private String expirationDateDisplay;

    private String unitName;

    private Integer systemQty;
    private Integer actualQty;

    /** Absolute discrepancy = the quantity to adjust (always &gt; 0). */
    private Integer quantity;

    /** {@code COUNT}, {@code DATE_ADJUSTMENT} hoặc {@code DESTROY} — loại phiếu sẽ được sinh ra. */
    private String adjustmentType;
    /** {@code IN} thừa, {@code OUT} thiếu, {@code NONE} với dòng sửa hạn dùng. */
    private String direction;

    private BigDecimal unitCostPrice;
    private BigDecimal lineCost;

    // Chỉ có giá trị với nguồn DATE: hạn đang lưu trên lô và hạn thực tế đọc được lúc kiểm.
    private String oldExpirationDateDisplay;
    private String newExpirationDateDisplay;
}
