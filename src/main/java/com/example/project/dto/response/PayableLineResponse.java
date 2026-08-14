package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** Một dòng chứng từ trên màn chi tiết nợ. */
@Getter
@AllArgsConstructor
public class PayableLineResponse {

    /** Id chứng từ gốc (phiếu trả KH hoặc phiếu nhập); với dòng phiếu chi chờ thì là id phiếu chi. */
    private Integer id;
    /** Mã chứng từ hiển thị. */
    private String code;
    /** Ngày đã định dạng cho giao diện. */
    private String dateDisplay;
    /** Nợ thực còn lại — chưa giảm khi chỉ có phiếu chi {@code Chờ thanh toán}. */
    private BigDecimal payableAmount;
    /** Mô tả phụ (tên KH, tổng phiếu nhập, …). */
    private String detail;
    /** Phần chưa nằm trên phiếu chi {@code Chờ thanh toán} — dùng cho nút Hoàn tiền / Chi trả. */
    private BigDecimal remainingCreatable;
    /** Phiếu chi {@code Chờ thanh toán} gắn chứng từ — {@code id} là id phiếu chi. */
    private List<PayableLineResponse> awaitingExpenses;
}
