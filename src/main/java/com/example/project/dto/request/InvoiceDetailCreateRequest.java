package com.example.project.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** One line of the sell (create-invoice) form: product + chosen sell unit + quantity. */
@Getter
@Setter
public class InvoiceDetailCreateRequest {

    @NotNull(message = "Vui lòng chọn sản phẩm")
    private Integer productId;

    @NotNull(message = "Vui lòng chọn đơn vị bán")
    private Integer productUnitId;

    @NotNull(message = "Số lượng không được để trống")
    @Min(value = 1, message = "Số lượng phải lớn hơn 0")
    private Integer quantity;

    /** Optional override of the unit's sell price; falls back to the unit's configured price. */
    private BigDecimal unitSellPrice;

    /** Optional batch to deduct from; null means FEFO across all batches. */
    private Integer batchId;

    /** Optional sales note for this line (e.g. usage instructions). */
    private String note;
}
