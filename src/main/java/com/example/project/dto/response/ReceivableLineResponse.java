package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Một dòng chứng từ trên màn chi tiết cho nợ. */
@Getter
@AllArgsConstructor
public class ReceivableLineResponse {

    /** Id chứng từ gốc (hóa đơn hoặc phiếu trả NCC). */
    private Integer id;
    /** Mã chứng từ hiển thị. */
    private String code;
    /** Ngày đã định dạng cho giao diện. */
    private String dateDisplay;
    /** Số cho nợ còn lại trên chứng từ này. */
    private BigDecimal receivableAmount;
    /** Mô tả phụ (tổng HĐ, phiếu nhập liên quan, …). */
    private String detail;
}
