package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Một chứng từ chọn được trên form tạo phiếu thu (hóa đơn nợ, phiếu trả NCC, …). */
@Getter
@AllArgsConstructor
public class IncomeReferenceOptionResponse {

    /** Id chứng từ. */
    private Integer id;
    /** Mã chứng từ hiển thị. */
    private String code;
    /** Ngày giờ chứng từ hiển thị. */
    private String dateDisplay;
    /** Số tiền còn thu được / số tiền liên quan. */
    private BigDecimal amount;
    /** Dòng mô tả phụ (VD: tổng HĐ, mã phiếu nhập). */
    private String detail;
}
