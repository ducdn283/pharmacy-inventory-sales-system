package com.example.project.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Sửa một dòng sản phẩm cung ứng ({@code supplierproduct}) trên màn chi tiết nhà cung cấp.
 *
 * <p>Ba trường này là toàn bộ phần người dùng nhập được của dòng — {@code costPrice} KHÔNG nằm ở đây:
 * giá nhập cuối do luồng nhập hàng ghi, màn nhà cung cấp chỉ đọc.</p>
 *
 * <p>Dùng {@code Boolean} chứ không phải {@code boolean} vì checkbox không tick thì trình duyệt KHÔNG
 * gửi tham số nào — nhận {@code null} rồi tự quy về false, khỏi vướng lỗi bind.</p>
 */
@Getter
@Setter
public class SupplierProductUpdateRequest {

    /** Còn cung ứng hay đã ngừng — cột {@code isActive}. */
    private Boolean isActive;

    /** Nhà cung cấp ưu tiên cho sản phẩm này — cột {@code isPreferred}. */
    private Boolean isPreferred;

    @Size(max = 500, message = "Ghi chú không được vượt quá 500 ký tự")
    private String note;
}
