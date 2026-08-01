package com.example.project.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class FinancialSettingUpdateRequest {

    @NotNull(message = "Phương thức tính thuế không được để trống")
    @Min(value = 1, message = "Phương thức tính thuế không hợp lệ")
    @Max(value = 2, message = "Phương thức tính thuế không hợp lệ")
    private Integer taxCalculationMethod;

    @NotNull(message = "Nhóm doanh thu không được để trống")
    @Min(value = 1, message = "Nhóm doanh thu không hợp lệ")
    @Max(value = 3, message = "Nhóm doanh thu không hợp lệ")
    private Integer revenueGroup;

    @NotNull(message = "Ngưỡng doanh thu miễn thuế không được để trống")
    @DecimalMin(value = "0", message = "Ngưỡng doanh thu không được nhỏ hơn 0")
    private BigDecimal annualRevenueThreshold1;

    @NotNull(message = "Ngưỡng doanh thu bắt buộc tính theo lợi nhuận không được để trống")
    @DecimalMin(value = "0", message = "Ngưỡng doanh thu không được nhỏ hơn 0")
    private BigDecimal annualRevenueThreshold2;

    @NotNull(message = "Tỷ lệ phần trăm hàng trả không được để trống")
    @DecimalMin(value = "0", message = "Tỷ lệ phần trăm hàng trả không được nhỏ hơn 0")
    @DecimalMax(value = "100", message = "Tỷ lệ phần trăm hàng trả không được vượt quá 100")
    private BigDecimal returnProductOnInvoiceValueRate;

    private Boolean autoGenerateVATInvoice;

    @NotBlank(message = "Ký hiệu mẫu số hóa đơn VAT không được để trống")
    @Size(max = 10, message = "Ký hiệu mẫu số hóa đơn VAT không được vượt quá 10 ký tự")
    private String vatInvoiceSeries;

    @NotNull(message = "Tiền mặt mở ca mặc định không được để trống")
    @DecimalMin(value = "0", message = "Tiền mặt mở ca mặc định không được nhỏ hơn 0")
    private BigDecimal openingCashDefault;

    @NotBlank(message = "Mã số thuế không được để trống")
    @Size(max = 100, message = "Mã số thuế không được vượt quá 100 ký tự")
    private String taxCode;

    @NotBlank(message = "Mã địa điểm kinh doanh không được để trống")
    @Size(max = 20, message = "Mã địa điểm kinh doanh không được vượt quá 20 ký tự")
    private String locationCode;

    @NotBlank(message = "Tên địa điểm kinh doanh không được để trống")
    @Size(max = 100, message = "Tên địa điểm kinh doanh không được vượt quá 100 ký tự")
    private String locationName;

    @NotBlank(message = "Địa chỉ không được để trống")
    @Size(max = 100, message = "Địa chỉ không được vượt quá 100 ký tự")
    private String address;

    @NotBlank(message = "Số điện thoại không được để trống")
    @Size(max = 10, message = "Số điện thoại không được vượt quá 10 ký tự")
    @Pattern(regexp = "0(2|3|5|7|8|9)[0-9]{8}", message = "Số điện thoại không hợp lệ")
    private String phoneNumber;

    @NotBlank(message = "Địa chỉ email không được để trống")
    @Email(message = "Địa chỉ email không hợp lệ")
    @Size(max = 100, message = "Địa chỉ email không được vượt quá 100 ký tự")
    private String email;

    @NotBlank(message = "Số tài khoản ngân hàng không được để trống")
    @Size(max = 20, message = "Số tài khoản ngân hàng không được vượt quá 20 ký tự")
    private String bankAccountNumber;

    @NotBlank(message = "Tên ngân hàng không được để trống")
    @Size(max = 100, message = "Tên ngân hàng không được vượt quá 100 ký tự")
    private String bankName;

    private Boolean autoOffsetDebtOnRefund;

    @Min(value = 1, message = "Số ngày cho phép trả hàng phải lớn hơn 0")
    private Integer returnPolicyMaxDays;

    /** Bỏ trống nghĩa là giữ nguyên số dư quỹ hiện tại — chỉ ghi đè khi người dùng thực sự nhập. */
    @DecimalMin(value = "0", message = "Số dư quỹ tiền mặt không được nhỏ hơn 0")
    private BigDecimal cashSafeBalance;

    @DecimalMin(value = "0", message = "Số dư quỹ ngân hàng không được nhỏ hơn 0")
    private BigDecimal bankAccountBalance;
}
