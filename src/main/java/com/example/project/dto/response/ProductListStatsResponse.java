package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Các thẻ tổng hợp phía trên Danh sách hàng hóa. Số liệu tính từ tồn kho thật (SUM Batch.storageQuantity so với minStock). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductListStatsResponse {
    /** Tổng số sản phẩm. */
    private long totalProducts;
    /** Số sản phẩm có tồn kho trên ngưỡng tối thiểu. */
    private long inStockCount;
    /** Số sản phẩm tồn kho ở/dưới ngưỡng tối thiểu nhưng vẫn > 0. */
    private long lowStockCount;
    /** Số sản phẩm hết hàng (tồn <= 0). */
    private long outOfStockCount;
}
