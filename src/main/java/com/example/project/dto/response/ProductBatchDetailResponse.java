package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Một lô hàng còn tồn của sản phẩm, dùng cho khối "Lô hàng còn bán". Ngày tháng đã format sẵn. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductBatchDetailResponse {
    private Integer batchId;
    private String batchName;
    private String lotNumber;
    private String importDateDisplay;
    private String productionDateDisplay;
    private String expirationDateDisplay;
    private Integer storageQuantity;
    private String importUnitName;
    private BigDecimal importPrice;
    private String statusLabel;
    private String note;
}
