package com.example.project.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

/** Một lô còn hàng nhúng vào form bán hàng — dùng khi chọn lô trừ tồn. */
@Getter
@AllArgsConstructor
public class SellBatchOptionResponse {

    /** Id lô hàng. */
    private Integer batchId;
    /** Mã lô. */
    private String batchCode;
    /** Số lô. */
    private String lotNumber;

    /** Hạn sử dụng — không gửi ra JSON (chỉ dùng nội bộ). */
    @JsonIgnore
    private LocalDate expirationDate;
    /** Hạn sử dụng hiển thị ({@code dd/MM/yyyy}). */
    private String expirationDateDisplay;

    /** Tồn kho còn lại trên lô (đơn vị cơ sở). */
    private Integer storageQuantity;

    /** {@code true} khi HSD trước hôm nay — hiển thị nhưng không cho chọn bán. */
    private boolean expired;
}
