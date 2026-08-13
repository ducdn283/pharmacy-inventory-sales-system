package com.example.project.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class StockAdjustmentBatchCandidateResponse {

    private Integer batchId;

    private Integer productId;
    private String productName;

    private String lotNumber;

    // Inlined into JS via Thymeleaf, whose serializer has no Java-8-time module. The client only uses
    // the preformatted display string, so keep the raw LocalDate out of JSON serialization.
    @JsonIgnore
    private LocalDate expirationDate;
    private String expirationDateDisplay;

    /**
     * Lô đã QUÁ hạn dùng tính tới hôm nay. Hàng quá hạn vẫn chọn được cho phiếu DESTROY (đó chính là
     * lý do hủy), nhưng bị chặn với INTERNAL_USE/SAMPLE/GIFT — xem StockadjustmentService.
     */
    private boolean expired;

    /** Lô còn hạn nhưng sắp hết (trong 90 ngày — cùng ngưỡng cảnh báo hết hạn F-08 của hệ thống). */
    private boolean nearExpiry;

    private Integer storageQuantity;

    private Integer productUnitId;
    private String unitName;

    private BigDecimal unitCostPrice;

    /** Giá bán niêm yết của đơn vị cơ sở — nguồn của {@code refSellPrice} khi ghi dòng điều chỉnh. */
    private BigDecimal sellPrice;
}