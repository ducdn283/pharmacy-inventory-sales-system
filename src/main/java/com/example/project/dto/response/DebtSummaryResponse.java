package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Tổng KPI màn danh sách công nợ — khớp sáu thẻ trên {@code debt/debt-list.html}. */
@Getter
@AllArgsConstructor
public class DebtSummaryResponse {

    /** Số đối tượng còn cho nợ &gt; 0 hoặc nợ &gt; 0. */
    private long entityCount;
    /** Tổng cho nợ (KH + NCC). */
    private BigDecimal totalReceivable;
    /** Cho nợ từ khách hàng. */
    private BigDecimal customerReceivable;
    /** Cho nợ từ nhà cung cấp. */
    private BigDecimal supplierReceivable;
    /** Tổng nợ (KH + NCC). */
    private BigDecimal totalPayable;
    /** Nợ phải trả khách hàng. */
    private BigDecimal customerPayable;
    /** Nợ phải trả nhà cung cấp. */
    private BigDecimal supplierPayable;
}
