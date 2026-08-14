package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** Kết quả tìm sản phẩm trên form dự trù — autocomplete và nạp dòng chi tiết. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcurementProductSearchResponse {
    /** Id hàng hóa. */
    private Integer productID;
    /** Tên hàng hóa. */
    private String name;
    /** Mã hàng hóa. */
    private String code;
    /** Mã vạch. */
    private String barcode;
    /** Tồn kho hiện tại (đơn vị cơ sở). */
    private Integer currentStock;
    /** Tên đơn vị cơ sở — hiển thị tồn. */
    private String stockUnit;
    /** Đơn vị nhập mặc định. */
    private String unit;
    /** Tỷ lệ quy đổi của đơn vị nhập so với đơn vị cơ sở. */
    private BigDecimal unitRatio;
    /** Giá dự kiến gợi ý (nếu có). */
    private BigDecimal estimatedPrice;
    /** Danh sách đơn vị quy đổi của sản phẩm. */
    private List<ProcurementProductUnitResponse> units;
    /** Giá bán của đơn vị nhập hiện tại ({@code Productunit.sellPrice}). */
    private BigDecimal currentSellPrice;
}
