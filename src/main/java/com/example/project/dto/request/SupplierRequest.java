package com.example.project.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierRequest {

    @NotBlank(message = "Tên nhà cung cấp không được để trống")
    @Size(max = 255, message = "Tên không được vượt quá 255 ký tự")
    private String name;

    // NOT NULL from 2026-07-14 (needed to validate input VAT invoices against the tax authority).
    @NotBlank(message = "Mã số thuế không được để trống")
    @Pattern(
            regexp = "^[0-9]{10}(-[0-9]{3})?$",
            message = "Mã số thuế gồm 10 chữ số, hoặc 10 chữ số kèm 3 số chi nhánh (vd 0101234567-001)"
    )
    private String taxCode;

    @NotBlank(message = "Số điện thoại không được để trống")
    @Pattern(
            regexp = "^0(2|3|5|7|8|9)[0-9]{8}$",
            message = "Số điện thoại phải có 10 chữ số và bắt đầu bằng 02, 03, 05, 07, 08 hoặc 09"
    )
    private String phone;

    /**
     * KHÔNG bắt buộc (BA chốt 2026-08-15) — nhiều nhà cung cấp chỉ liên hệ qua điện thoại.
     *
     * <p>Nhánh {@code ^$} trong regex là bắt buộc: {@code @Pattern} bỏ qua {@code null} nhưng KHÔNG
     * bỏ qua chuỗi rỗng, mà ô để trống trên form gửi lên đúng là chuỗi rỗng. Bỏ trống thì
     * {@code SupplierService} lưu {@code null} chứ không lưu {@code ""} — cột này có UNIQUE index,
     * MySQL cho nhiều NULL nhưng hai chuỗi rỗng là trùng nhau.</p>
     */
    @Pattern(
            regexp = "^$|^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$",
            message = "Email chỉ gồm chữ, số, '.', '-', '_' và phải có đuôi hợp lệ (ví dụ .com, .vn)"
    )
    @Size(max = 100, message = "Email không được vượt quá 100 ký tự")
    private String email;

    @NotBlank(message = "Địa chỉ không được để trống")
    private String address;
}
