package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** Màn chi tiết nợ của một đối tượng. */
@Getter
@AllArgsConstructor
public class PayableDetailResponse {

    /** Id KH/NCC; {@code 0} cho khách lẻ. */
    private Integer entityId;
    /** Tên đối tượng. */
    private String name;
    /** Mã loại: {@code CUSTOMER} hoặc {@code SUPPLIER}. */
    private String partyType;
    /** Nhãn tiếng Việt loại đối tượng. */
    private String partyTypeDisplay;
    /** Tổng nợ thực còn lại. */
    private BigDecimal totalPayable;
    /** Từng chứng từ còn nợ, kèm phiếu chi chờ thanh toán. */
    private List<PayableLineResponse> lines;
}
