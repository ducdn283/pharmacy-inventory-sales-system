package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Một lô hàng <strong>còn tồn kho</strong> của sản phẩm, tương ứng một điểm trên biểu đồ của modal
 * chi tiết giá ({@code /owner/price-settings}).
 *
 * <p>Mọi số tiền đều tính <strong>trên một đơn vị cơ bản</strong> để so sánh trực tiếp với giá
 * bán, và đều là giá <strong>GROSS</strong> (đã gồm thuế) theo quy ước chung của dự án.</p>
 */
@Getter
@AllArgsConstructor
public class PriceSettingBatchPointResponse {

    private Integer batchId;
    private String batchCode;
    /** Số lô, hoặc {@code "—"} nếu lô không có số lô. */
    private String lotNumber;
    /** Hạn dùng dạng {@code dd/MM/yyyy}, hoặc {@code "—"} nếu sản phẩm không theo dõi hạn dùng. */
    private String expirationDate;

    /** Số lượng còn tồn, tính theo đơn vị cơ bản. */
    private Integer storageQuantity;

    /** {@code Batch.importPricePerBase} — giá GROSS. */
    private BigDecimal importPricePerBase;

    /** Phần thuế GTGT nằm trong {@link #importPricePerBase}: {@code gross × rate / (100 + rate)}. */
    private BigDecimal embeddedInputVat;

    /**
     * {@code sellPricePerBase − importPricePerBase}. <strong>Có thể âm</strong> — lô nhập đắt hơn
     * giá bán hiện tại chính là điều màn hình này cần chỉ ra, nên không làm tròn về 0.
     */
    private BigDecimal grossMargin;

    /** {@link #grossMargin} tính theo phần trăm giá bán; {@code null} nếu chưa có giá bán. */
    private BigDecimal grossMarginPercent;
}
