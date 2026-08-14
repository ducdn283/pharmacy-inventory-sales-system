package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Một dòng sản phẩm có thể mở rộng trên màn Cài đặt giá bán. Mở dòng ra sẽ hiện các đơn vị
 * ({@link PriceSettingRowResponse}) — nơi thực sự sửa được giá.
 *
 * <p>{@code averageImportPricePerBase} chỉ mang tính tham khảo: trung bình cộng giá nhập
 * ({@code Batch.importPricePerBase}) của các lô <strong>còn tồn kho</strong>, giá GROSS, không
 * bao giờ ghi ngược lại hay tính vào công thức lưu giá. {@code null} nếu sản phẩm không còn lô tồn.</p>
 *
 * <p>{@code basePrice} là giá dùng để sắp xếp tăng/giảm theo giá — giá bán của đơn vị cơ bản, vì
 * mọi đơn vị khác đều suy ra từ đó theo tỷ lệ quy đổi.</p>
 */
@Getter
@AllArgsConstructor
public class PriceSettingProductRowResponse {

    private Integer productId;
    private String productCode;
    private String productName;
    private String typeName;

    private BigDecimal averageImportPricePerBase;
    private BigDecimal basePrice;

    private List<PriceSettingRowResponse> units;

    // Trả về số lượng đơn vị của sản phẩm (0 nếu chưa có đơn vị nào).
    public int getUnitCount() {
        return units == null ? 0 : units.size();
    }
}
