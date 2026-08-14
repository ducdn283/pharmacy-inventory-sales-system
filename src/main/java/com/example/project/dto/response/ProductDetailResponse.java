package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Payload đầy đủ cho màn Chi tiết hàng hóa. Mọi giá trị đã sẵn sàng hiển thị; chỉ có 1 cửa hàng
 * (không chia theo chi nhánh) nên tồn kho là tổng toàn hệ thống.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductDetailResponse {

    // --- 1. General information ---
    private Integer productId;
    private String code;
    private String name;
    private String barcode;
    private String imageUrl;
    private String typeName;
    /** Lô hàng của sản phẩm này có theo dõi/hiển thị hạn dùng hay không. */
    private boolean tracksExpirationDate;
    private String producerName;
    private String originName;
    private String registrationNumber;
    private boolean statusActive;
    private String statusLabel;
    private Integer minStock;
    private Integer maxStock;
    private String note;
    /** Hoạt chất, định dạng "tên hàm lượng" (có thể rỗng). */
    private List<String> ingredients;
    private List<ProductUnitDetailResponse> units;
    /** Tên đơn vị được đánh dấu là đơn vị cơ bản, hoặc "" nếu chưa cấu hình. */
    private String baseUnitName;

    /** Tổng tồn kho hiện có (1 cửa hàng, không chia chi nhánh). */
    private long totalStock;
    private String stockStatusLabel;
    private String stockStatusCss;

    // --- 2. Các lô hàng còn tồn ---
    private List<ProductBatchDetailResponse> batches;

    // --- 3. Xem trước lịch sử biến động tồn kho gần đây (chỉ có khi role được phép xem) ---
    private boolean canViewRecentHistory;
    private List<ProductRecentHistoryResponse> recentHistory;
}
