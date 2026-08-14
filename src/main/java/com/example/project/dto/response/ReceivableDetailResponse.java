package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** Màn chi tiết cho nợ của một đối tượng. */
@Getter
@AllArgsConstructor
public class ReceivableDetailResponse {

    /** Id KH/NCC; {@code 0} cho khách lẻ. */
    private Integer entityId;
    /** Tên đối tượng. */
    private String name;
    /** Mã loại: {@code CUSTOMER} hoặc {@code SUPPLIER}. */
    private String partyType;
    /** Nhãn tiếng Việt loại đối tượng. */
    private String partyTypeDisplay;
    /** Tổng cho nợ còn lại. */
    private BigDecimal totalReceivable;
    /** Từng chứng từ còn cho nợ. */
    private List<ReceivableLineResponse> lines;
}
