package com.example.project.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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

    @NotNull(message = "Giá nhập không được để trống")
    @DecimalMin(value = "0.0", inclusive = false, message = "Giá nhập phải lớn hơn 0")
    private BigDecimal importPrice;

    // Xem PurchaseInvoiceCreateRequest.vatInvoiceDate cho lý do cần @DateTimeFormat trên LocalDate.
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate productionDate;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate expirationDate;

    /**
     * Xác nhận rõ ràng "sản phẩm này không có hạn sử dụng" — không phải cứ để trống
     * {@code expirationDate} là được coi là không có hạn; phải tick xác nhận thì server mới bỏ qua
     * yêu cầu bắt buộc, tránh trường hợp quên điền bị hiểu nhầm thành "không có hạn".
     */
    private Boolean noExpirationDate;

    private String lotNumber;

    /**
     * Not a validated/trusted input — the server always resolves the real VAT rate from the
     * product's {@code Type.defaultVATRate} (see {@code PurchaseinvoiceService.resolvePurchaseVatRate}).
     * Kept only so a validation-error re-render can echo back the read-only field's displayed value.
     */
    private BigDecimal vatRate;
}