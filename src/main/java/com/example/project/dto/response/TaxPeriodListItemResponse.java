package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** Một dòng trong danh sách kỳ thuế đã chốt. Ngày đã được format sẵn thành {@code String}. */
@Getter
@AllArgsConstructor
public class TaxPeriodListItemResponse {

    private Integer id;
    private String periodLabel;

    private String startDateDisplay;
    private String endDateDisplay;

    /** Nhóm mà kỳ này được khai — suy ra từ chuỗi kỳ, không lưu trực tiếp trên dòng. */
    private Integer revenueGroup;
    private String revenueGroupDisplay;

    /** Thuế GTGT thực phải nộp — con số duy nhất có nghĩa giống nhau ở mọi nhóm. */
    private BigDecimal vatPayable;

    /** Thuế TNCN của kỳ. */
    private BigDecimal incomeTax;

    private String recordedAtDisplay;

    /** Chỉ kỳ mới nhất mới được sửa — các kỳ trước đã bị khóa. */
    private boolean editable;
}
