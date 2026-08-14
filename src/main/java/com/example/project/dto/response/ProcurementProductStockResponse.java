package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** Tồn kho sản phẩm — modal "Xem tồn sản phẩm" trên form tạo dự trù. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcurementProductStockResponse {
    /** Id hàng hóa. */
    private Integer productID;
    /** Tên hàng hóa. */
    private String name;
    /** Mã hàng hóa. */
    private String code;
    /** Mã vạch. */
    private String barcode;
    /** Tồn tối thiểu cảnh báo. */
    private Integer minStock;
    /** Tồn tối đa. */
    private Integer maxStock;
    /** Tồn kho hiện tại (đơn vị cơ sở). */
    private Integer currentStock;
    /** Tên đơn vị cơ sở. */
    private String stockUnit;
    /** Đơn vị nhập mặc định. */
    private String unit;
    /** Tỷ lệ quy đổi đơn vị nhập. */
    private BigDecimal unitRatio;
    /** Giá dự kiến gợi ý. */
    private BigDecimal estimatedPrice;
    /** Danh sách đơn vị quy đổi. */
    private List<ProcurementProductUnitResponse> units;
    /** Giá bán đơn vị nhập hiện tại. */
    private BigDecimal currentSellPrice;
}
