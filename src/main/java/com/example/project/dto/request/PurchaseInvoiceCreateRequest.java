package com.example.project.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class PurchaseInvoiceCreateRequest {

    @NotNull(message = "Vui lòng chọn nhà cung cấp")
    private Integer supplierId;

    /** Optional — purely a cross-reference link to a procurement plan, not a required field. */
    private Integer requisitionId;

    @DecimalMin(value = "0.0", message = "Chi phí phát sinh không được âm")
    private BigDecimal additionCost = BigDecimal.ZERO;

    @DecimalMin(value = "0.0", message = "Chiết khấu không được âm")
    private BigDecimal discount = BigDecimal.ZERO;

    // Không có `paid` ở đây: phiếu nhập luôn được lập ở trạng thái "Nợ", tiền chỉ chuyển động khi
    // có phiếu chi trỏ vào phiếu nhập này (PurchaseinvoiceService.applyPayment).

    private String note;

    @NotBlank(message = "Vui lòng nhập số hóa đơn GTGT")
    private String vatInvoiceNumber;

    @NotNull(message = "Vui lòng nhập ngày hóa đơn GTGT")
    private LocalDate vatInvoiceDate;

    /** Optional — hạn thanh toán công nợ NCC theo thỏa thuận, dùng để tự áp dụng Điều 26 NĐ 181/2025/NĐ-CP. */
    private LocalDate dueDate;

    @Valid
    @NotEmpty(message = "Phiếu nhập phải có ít nhất một sản phẩm")
    private List<PurchaseInvoiceDetailCreateRequest> details = new ArrayList<>();
}