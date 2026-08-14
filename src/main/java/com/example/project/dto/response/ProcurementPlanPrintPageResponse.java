package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** Dữ liệu trang in phiếu dự trù — header và danh sách dòng chi tiết. */
@Getter
@AllArgsConstructor
public class ProcurementPlanPrintPageResponse {

    /** Id phiếu dự trù. */
    private Integer planId;
    /** Mã phiếu — {@code DT-xxxxxx}. */
    private String procurementCode;
    /** Ngày lập hiển thị ({@code dd/MM/yyyy HH:mm}). */
    private String dateDisplay;
    /** Trạng thái phiếu. */
    private String status;
    /** Ghi chú. */
    private String note;
    /** Tổng số dòng sản phẩm. */
    private int totalItems;
    /** Tổng giá ước tính toàn phiếu. */
    private BigDecimal totalEstimatedAmount;
    /** Các dòng chi tiết in ra. */
    private List<ProcurementPlanPrintLineResponse> lines;
}
