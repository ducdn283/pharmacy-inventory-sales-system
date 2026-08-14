package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Một dòng trên màn danh sách công nợ — gom theo khách hàng hoặc NCC. */
@Getter
@AllArgsConstructor
public class DebtListItemResponse {

    /** Id KH/NCC; {@code 0} cho khách lẻ. */
    private Integer entityId;
    /** Tên hiển thị trên danh sách. */
    private String name;
    /** Mã loại: {@code CUSTOMER} hoặc {@code SUPPLIER}. */
    private String partyType;
    /** Nhãn tiếng Việt loại đối tượng. */
    private String partyTypeDisplay;
    /** Số nhà thuốc còn phải trả đối tượng (nợ). */
    private BigDecimal payableAmount;
    /** Số đối tượng còn phải trả nhà thuốc (cho nợ). */
    private BigDecimal receivableAmount;
}
