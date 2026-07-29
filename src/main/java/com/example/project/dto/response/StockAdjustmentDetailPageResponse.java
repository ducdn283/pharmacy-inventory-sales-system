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

    /** True chỉ khi loại phiếu là INTERNAL_USE/GIFT/SAMPLE → hiện cột thuế GTGT đầu ra trên màn chi tiết. */
    private boolean showOutputVat;

    /** Tổng thuế GTGT đầu ra của phiếu (0 nếu loại không phát sinh). */
    private BigDecimal totalOutputVat;

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
}