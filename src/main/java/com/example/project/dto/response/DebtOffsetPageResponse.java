package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** View model màn {@code debt/debt-offset.html} — hai bên có thể cấn trừ. */
@Getter
@AllArgsConstructor
public class DebtOffsetPageResponse {

    /** Id KH/NCC; {@code 0} cho khách lẻ. */
    private Integer entityId;
    /** Tên đối tượng đang bù trừ. */
    private String name;
    /** Mã loại: {@code CUSTOMER} hoặc {@code SUPPLIER}. */
    private String partyType;
    /** Nhãn tiếng Việt loại đối tượng. */
    private String partyTypeDisplay;
    /** Tổng cho nợ còn lại của đối tượng. */
    private BigDecimal totalReceivable;
    /** Tổng nợ thực (chưa trừ phiếu chi chờ thanh toán). */
    private BigDecimal totalPayable;
    /** {@code min(totalReceivable, totalPayableOffsetable)} — trần một lần bù trừ. */
    private BigDecimal maxOffset;
    /** Các dòng chứng từ cho nợ có thể chọn bù trừ. */
    private List<ReceivableLineResponse> receivableLines;
    /** Dòng nợ với {@code remainingCreatable} tính lại cho điều kiện bù trừ. */
    private List<PayableLineResponse> payableLines;
}
