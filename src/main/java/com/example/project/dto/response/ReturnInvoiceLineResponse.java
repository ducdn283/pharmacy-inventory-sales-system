package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Một dòng hàng còn trả được của hóa đơn đã chọn — trả về dạng JSON sau khi người dùng chọn hóa đơn
 * trên màn tạo phiếu trả. Mang số lượng còn trả để UI giới hạn nhập.
 */
@Getter
@AllArgsConstructor
public class ReturnInvoiceLineResponse {

    /** Id dòng chi tiết hóa đơn gốc. */
    private Integer invoiceDetailId;

    /** Id sản phẩm. */
    private Integer productId;
    /** Tên sản phẩm. */
    private String productName;

    /** Tên lô ({@code batch.batchName}), không phải số lô — đồng bộ với màn chi tiết phiếu trả. */
    private String batchName;
    /** Hạn sử dụng hiển thị. */
    private String expirationDateDisplay;

    /** Đơn vị bán trên hóa đơn gốc. */
    private String unitName;

    /** Số lượng đã bán trên dòng này. */
    private Integer soldQty;
    /** Số lượng đã trả qua các phiếu trả trước. */
    private Integer returnedQty;
    /** {@code soldQty - returnedQty} — số lượng tối đa còn trả được. */
    private Integer returnableQty;

    /** Đơn giá bán trên hóa đơn gốc. */
    private BigDecimal unitSellPrice;

    /**
     * Hàng trả có nhập lại kho hay không — cố định theo loại đơn vị
     * (chỉ đơn vị đóng gói mặc định của nhà sản xuất, {@code productunit.isDefault}).
     * Hiển thị chỉ đọc trên màn tạo; không có ô tick thủ công.
     */
    private boolean restockable;
}
