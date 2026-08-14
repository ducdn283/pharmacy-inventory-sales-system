package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Một kỳ thuế đã chốt, hiển thị trên màn chi tiết — gồm số liệu đã lưu và bối cảnh chuỗi kỳ (nhóm
 * áp dụng, kỳ trước cung cấp vatCarryforwardIn).
 */
@Getter
@AllArgsConstructor
public class TaxPeriodDetailResponse {

    private Integer id;
    private String periodLabel;

    private String startDateDisplay;
    private String endDateDisplay;

    private Integer revenueGroup;
    private String revenueGroupDisplay;
    private boolean deductionGroup;
    private boolean taxExempt;

    private BigDecimal vatOutput;
    private BigDecimal vatPayable;

    /** Thuế TNCN của kỳ — số đã lưu, không tính lại. */
    private BigDecimal incomeTax;

    private BigDecimal quarterlyRevenue;

    /** Nhóm áp dụng từ ngày sau {@code endDate}; mặc định bằng nhóm của chính kỳ này. */
    private Integer nextPeriodTaxType;
    private String nextPeriodTaxTypeDisplay;

    private String recordedAtDisplay;
    private String note;

    /** Nhãn kỳ cung cấp số khấu trừ chuyển tiếp; null nếu là kỳ đầu tiên. */
    private String previousPeriodLabel;

    private boolean editable;
}
