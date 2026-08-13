package com.example.project.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class PurchaseInvoiceDetailCreateRequest {

    @NotNull(message = "Vui lòng chọn sản phẩm")
    private Integer productId;

    @NotNull(message = "Số lượng không được để trống")
    @Min(value = 1, message = "Số lượng phải lớn hơn 0")
    private Integer quantity;

    @NotNull(message = "Đơn giá không được để trống")
    @DecimalMin(value = "0.0", inclusive = false, message = "Đơn giá phải lớn hơn 0")
    private BigDecimal importPrice;

    // Xem PurchaseInvoiceCreateRequest.vatInvoiceDate cho lý do cần @DateTimeFormat trên LocalDate.
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate productionDate;

    // Bắt buộc hay bỏ qua hoàn toàn tùy loại sản phẩm (Type.sortType/.name) — xem
    // PurchaseinvoiceService.requiresExpirationDate. Không còn cách nào để người dùng tự xác nhận
    // "không có hạn" cho một sản phẩm mà loại của nó có theo dõi hạn sử dụng.
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate expirationDate;

    // Kept only for backward-compatible request binding. The purchase-invoice form no longer
    // displays/submits this field and PurchaseinvoiceService always generates the stored name.
    private String batchName;

    @Size(max = 50, message = "Số lô không được vượt quá 50 ký tự")
    private String lotNumber;

    /**
     * Not a validated/trusted input — the server always resolves the real VAT rate from the
     * product's {@code Type.defaultVATRate} (see {@code PurchaseinvoiceService.resolvePurchaseVatRate}).
     * Kept only so a validation-error re-render can echo back the read-only field's displayed value.
     */
    private BigDecimal vatRate;
}
