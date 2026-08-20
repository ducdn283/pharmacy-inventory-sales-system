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

    /**
     * Mã lô do hệ thống cấp. Cần cho màn chọn lô: cùng một sản phẩm có thể có NHIỀU dòng lô mang y
     * hệt số lô và hạn dùng (mỗi lần nhập / mỗi lần khách trả hàng là một dòng riêng), lúc đó mã lô
     * là thứ DUY NHẤT phân biệt được chúng.
     */
    private String batchCode;

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

    /**
     * Số ngày còn lại tới hạn dùng ({@code null} khi lô không khai hạn, âm khi đã quá hạn).
     *
     * <p>Có trường này để màn hình tự lọc được theo bất kỳ ngưỡng nào mà không phải thêm cờ mới cho mỗi
     * mốc — ô lọc đang dùng nó cho mục "cận hạn trong 30 ngày". <b>Không dùng để thay {@link #nearExpiry}:</b>
     * cờ đó là ngưỡng 90 ngày dùng chung với {@code ProductService} và {@code InventoryNotificationService},
     * quyết định nhãn vàng "Cận hạn" trên dòng lô.</p>
     */
    private Integer daysToExpiry;

    private Integer storageQuantity;

    private Integer productUnitId;
    private String unitName;

    private BigDecimal unitCostPrice;

    /** Giá bán niêm yết của đơn vị cơ sở — nguồn của {@code refSellPrice} khi ghi dòng điều chỉnh. */
    private BigDecimal sellPrice;
}