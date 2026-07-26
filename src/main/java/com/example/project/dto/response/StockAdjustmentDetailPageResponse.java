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

    private String createdByName;
    private String approvedByName;
    private String approvedAtDisplay;

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
}