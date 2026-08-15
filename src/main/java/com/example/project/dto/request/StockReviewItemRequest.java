package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Getter
@Setter
public class StockReviewItemRequest {

    private Integer batchId;

    /**
     * Số lượng thực tế.
     * Chỉ sử dụng khi rà soát theo số lượng.
     */
    private Integer actualQty;

    /**
     * Số lượng hàng đạt chuẩn.
     * Chỉ sử dụng khi rà soát theo tình trạng.
     */
    private Integer compliantQty;

    /**
     * Số lượng hàng không đạt chuẩn.
     * Chỉ sử dụng khi rà soát theo tình trạng.
     */
    private Integer nonCompliantQty;

    /**
     * Hạn dùng thực tế.
     * Chỉ sử dụng khi rà soát theo hạn dùng.
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate actualExpirationDate;

    /**
     * Giữ lại để tương thích với những phần code cũ.
     *
     * Khi tạo phiếu CONDITION, Service sẽ tự sinh hai bản ghi
     * COMPLIANT và NON_COMPLIANT nên giao diện không cần gửi trường này.
     */
    private String conditionStatus;

    private String note;
}