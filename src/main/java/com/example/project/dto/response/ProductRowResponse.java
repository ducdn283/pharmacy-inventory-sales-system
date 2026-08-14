package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Một dòng của màn Danh sách hàng hóa. Đã mang sẵn giá trị hiển thị (tên loại, hoạt chất, đơn vị
 * bán chính, giá bán, tồn kho, trạng thái tồn) để template Thymeleaf không cần đụng entity lazy.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductRowResponse {
    /** Khóa chính – dùng để tạo link tới trang chi tiết. */
    private Integer productId;
    /** Mã nghiệp vụ hiển thị ở cột "Mã hàng". */
    private String code;
    private String name;
    private String typeName;
    /** Hoạt chất, nối chuỗi; rỗng nếu sản phẩm không có hoạt chất. */
    private String ingredient;
    /** Tên đơn vị bán chính (đơn vị mặc định, hoặc đơn vị cơ bản nếu chưa có mặc định). */
    private String unitName;
    /** Giá bán của đơn vị bán chính; null nếu chưa cấu hình đơn vị nào. */
    private BigDecimal sellPrice;
    /** Tồn kho hiện có = SUM(Batch.storageQuantity). */
    private long stock;
    /** Nhãn trạng thái tồn: "Còn hàng" / "Sắp hết" / "Hết hàng". */
    private String stockStatusLabel;
    /** Class CSS cho badge trạng thái tồn. */
    private String stockStatusCss;
    /** "Có" / "Không" suy từ tên Loại hàng; "—" nếu chưa gán loại. */
    private String prescriptionDisplay;
}
