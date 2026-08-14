package com.example.project.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Một dòng sản phẩm trên form bán hàng: hàng hóa + đơn vị bán + số lượng. */
@Getter
@Setter
public class InvoiceDetailCreateRequest {

    /** Id hàng hóa bán. */
    @NotNull(message = "Vui lòng chọn sản phẩm")
    private Integer productId;

    /** Id đơn vị bán (VD: Vỉ, Hộp). */
    @NotNull(message = "Vui lòng chọn đơn vị bán")
    private Integer productUnitId;

    /** Số lượng bán theo đơn vị đã chọn. */
    @NotNull(message = "Số lượng không được để trống")
    @Min(value = 1, message = "Số lượng phải lớn hơn 0")
    private Integer quantity;

    /** Giá bán ghi đè — null thì lấy giá cấu hình trên đơn vị. */
    private BigDecimal unitSellPrice;

    /** Lô trừ tồn — null thì trừ theo FEFO trên tất cả lô còn hàng. */
    private Integer batchId;

    /** Ghi chú dòng hàng (VD: hướng dẫn sử dụng). */
    private String note;
}
