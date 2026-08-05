package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@AllArgsConstructor
public class StockAdjustmentDetailPageResponse {

    private Integer id;
    private String code;

    private Instant date;
    private String dateDisplay;

    private String adjustmentType;
    private String adjustmentTypeDisplay;

    // Không còn người lập / người thực hiện / thời điểm thực hiện: DB đã bỏ 3 cột createdBy,
    // approvedBy, approvedAt (28/07/2026) vì chỉ Owner thao tác được màn này.

    private String reason;
    private String note;

    private String statusName;
    private String statusCssClass;

    private long totalItems;
    private int totalQuantity;
    private BigDecimal estimatedValue;

    private String costImpactDisplay;

    private List<StockAdjustmentDetailItemResponse> items;

    /**
     * Phiếu loại {@code DATE_ADJUSTMENT} — màn chi tiết đổi hẳn bộ cột (hạn cũ → hạn mới thay cho
     * chênh lệch số lượng) vì phiếu này KHÔNG làm đổi tồn kho.
     */
    private boolean dateAdjustment;

    /** Mã phiếu rà soát kho làm căn cứ (null nếu phiếu lập tay không gắn phiếu nào) + nhãn loại của nó. */
    private String stockReviewCode;
    private String stockReviewTypeDisplay;

    /**
     * True khi loại phiếu là {@code DESTROY_EMPLOYEE_FAULT} / {@code COUNT_DECREASE} — hai loại thất
     * thoát mà nhân viên có thể phải đền bù, và là hai loại duy nhất được liên kết phiếu thu
     * (Dac_ta_Income_StockAdjustment sheet 03, cột "Liên kết Income?").
     */
    private boolean employeeLiable;

    /**
     * Tổng giá trị đền bù đề xuất, tính theo <strong>GIÁ BÁN</strong> (sheet 01: "tính THEO GIÁ BÁN
     * (giá niêm yết), KHÔNG phải giá vốn"). Bằng 0 với các loại phiếu khác.
     */
    private BigDecimal totalReimbursementValue;

    /** Phiếu thu đền bù đã lập cho phiếu này (null nếu chưa có) — để màn chi tiết trỏ qua. */
    private Integer linkedIncomeId;
    private String linkedIncomeCode;

    /**
     * Phiếu này có hủy được không. Phiếu {@code Nháp} thì luôn được (không đảo gì cả); phiếu
     * {@code Hoàn thành} thì tùy loại — xem {@code StockadjustmentService.assertReversible}.
     */
    private boolean cancellable;

    /** Câu giải thích vì sao không hủy được, để hiện thay cho nút Hủy. Null khi hủy được. */
    private String cancelBlockedReason;
}