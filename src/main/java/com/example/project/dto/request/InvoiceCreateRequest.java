package com.example.project.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Form bán hàng — Chủ nhà thuốc/Dược sĩ gửi từ màn tạo hóa đơn. {@code customerId} null nghĩa là khách lẻ. */
@Getter
@Setter
public class InvoiceCreateRequest {

    /** Id khách hàng — null nếu bán cho khách lẻ. */
    private Integer customerId;

    /** Giảm giá toàn hóa đơn. */
    @DecimalMin(value = "0.0", message = "Giảm giá không được âm")
    private BigDecimal discount = BigDecimal.ZERO;

    /** Số tiền khách trả bằng tiền mặt. */
    @DecimalMin(value = "0.0", message = "Tiền mặt không được âm")
    private BigDecimal paidByCash = BigDecimal.ZERO;

    /** Số tiền khách trả chuyển khoản. */
    @DecimalMin(value = "0.0", message = "Chuyển khoản không được âm")
    private BigDecimal paidByBanking = BigDecimal.ZERO;

    /** Có phải hóa đơn thuốc kê đơn hay không. */
    private Boolean prescriptionRequired = Boolean.FALSE;

    /** Mã đơn thuốc — bắt buộc khi {@code prescriptionRequired = true}. */
    private String prescriptionCode;

    /** Ghi chú trên hóa đơn. */
    private String note;

    /** Danh sách dòng hàng bán — ít nhất một dòng. */
    @Valid
    @NotEmpty(message = "Hóa đơn phải có ít nhất một sản phẩm")
    private List<InvoiceDetailCreateRequest> details = new ArrayList<>();
}
