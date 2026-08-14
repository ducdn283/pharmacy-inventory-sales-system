package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dữ liệu cho modal chi tiết giá của một sản phẩm — lấy từ
 * {@code GET /owner/price-settings/{productId}/detail}. Trả lời hai câu hỏi: giá vốn lô hàng còn
 * tồn so với giá đang bán ({@link #batches} với {@link #sellPricePerBase}), và thuế theo nhóm
 * doanh thu hiện tại ảnh hưởng thế nào tới lợi nhuận ({@link #taxProjection}).
 *
 * <p>{@link #batches} chỉ gồm lô <strong>còn tồn kho, đang hoạt động</strong> — lô đã bán hết
 * không phản ánh giá vốn của hàng còn lại.</p>
 */
@Getter
@AllArgsConstructor
public class PriceSettingDetailResponse {

    private Integer productId;
    private String productCode;
    private String productName;
    private String typeName;

    /** Tên đơn vị cơ bản — đơn vị mà mọi số tiền trên panel này quy về. */
    private String baseUnitName;
    /** Giá bán hiện tại của đơn vị cơ bản (GROSS); {@code null} nếu chưa có giá. */
    private BigDecimal sellPricePerBase;

    /** Trung bình cộng giá nhập của {@link #batches}; {@code null} nếu không còn tồn kho. */
    private BigDecimal averageImportPricePerBase;
    /** Giá nhập thấp nhất trong các lô còn tồn; {@code null} nếu không còn tồn kho. */
    private BigDecimal minImportPricePerBase;
    /** Giá nhập cao nhất trong các lô còn tồn; {@code null} nếu không còn tồn kho. */
    private BigDecimal maxImportPricePerBase;
    /** Tổng số lượng còn tồn của {@link #batches}, tính theo đơn vị cơ bản. */
    private long totalStock;

    private List<PriceSettingBatchPointResponse> batches;

    private PriceSettingTaxProjectionResponse taxProjection;

    /** True nếu có ít nhất một lô tồn kho có giá nhập cao hơn giá bán hiện tại (bán ra là lỗ). */
    public boolean isHasLossMakingBatch() {
        return batches != null && batches.stream()
                .anyMatch(batch -> batch.getGrossMargin() != null
                        && batch.getGrossMargin().signum() < 0);
    }
}
