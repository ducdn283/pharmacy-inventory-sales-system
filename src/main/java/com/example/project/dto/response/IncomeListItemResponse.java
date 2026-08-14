package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/** Một dòng trên danh sách phiếu thu. */
@Getter
@AllArgsConstructor
public class IncomeListItemResponse {

    /** Id phiếu thu. */
    private Integer id;
    /** Mã hiển thị — dạng {@code PT-xxxxxx}. */
    private String code;

    /** Ngày giờ lập phiếu. */
    private Instant date;
    /** Ngày giờ hiển thị ({@code dd/MM/yyyy HH:mm}). */
    private String dateDisplay;

    /** Loại phiếu thu hiển thị. */
    private String incomeTypeDisplay;
    /** Nội dung / lý do thu. */
    private String reason;
    /** Tên người lập phiếu. */
    private String applicantName;

    /** Tổng số tiền thu. */
    private BigDecimal amount;
    /** Hình thức thanh toán hiển thị. */
    private String paymentDisplay;

    /** Tên trạng thái phiếu thu. */
    private String statusName;
    /** Class CSS badge trạng thái. */
    private String statusCssClass;
}
