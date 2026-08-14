package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Một dòng chi tiết trên trang in phiếu dự trù. */
@Getter
@AllArgsConstructor
public class ProcurementPlanPrintLineResponse {

    /** Mã hàng hóa. */
    private String productCode;
    /** Tên hàng hóa. */
    private String productName;
    /** Tồn kho tại thời điểm lập phiếu. */
    private Integer currentStock;
    /** Đơn vị tồn (cơ sở). */
    private String stockUnit;
    /** Chuỗi quy đổi đơn vị — VD: {@code 1 Hộp = 10 Vỉ = 100 Viên}. */
    private String unitConversionHint;
    /** Số lượng dự trù. */
    private Integer requestedQuantity;
    /** Đơn vị dự trù. */
    private String unit;
    /** Đơn giá ước tính (= tổng giá ÷ số lượng). */
    private BigDecimal unitPrice;
    /** Tổng giá ước tính dòng. */
    private BigDecimal estimatedPrice;
    /** Tên nhà cung cấp dự kiến. */
    private String supplierName;
}
