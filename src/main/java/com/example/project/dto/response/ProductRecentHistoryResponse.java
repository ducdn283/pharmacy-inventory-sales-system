package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Một dòng biến động tồn kho gần đây, dùng cho khối xem trước "Lịch sử tồn kho gần đây".
 *
 * <p>Đây chỉ là bản xem trước nhẹ, gộp từ sự kiện nhập hàng (Batch), bán hàng (InvoiceDetail),
 * xuất kho (StockOutDetail) và trả hàng (ReturnDetail) — không phải màn Lịch sử tồn kho đầy đủ.
 * {@code occurredAt} chỉ dùng để sắp xếp thời gian; template hiển thị bằng {@code timeDisplay}.</p>
 *
 * <p>{@code resultingStock} là số dư tồn kho TOÀN SẢN PHẨM (gộp mọi lô — khác với bảng lô hàng ở
 * trên vốn đã hiển thị tồn riêng từng lô), dựng ngược từ tổng tồn thật hiện tại qua các dòng trong
 * chính bản xem trước này (xem {@code ProductService.loadRecentHistory()}). Đây KHÔNG phải sổ cái
 * đầy đủ: một sự kiện cũ hơn phạm vi top-N này sẽ làm đứt chuỗi tính toán cho mọi dòng trước đó,
 * khi đó hiển thị null ("—") thay vì một con số tồn kho sai (âm).</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductRecentHistoryResponse {
    /** Mốc thời gian gốc, chỉ dùng để sắp xếp các dòng từ nhiều nguồn khác nhau. */
    private Instant occurredAt;
    private String timeDisplay;
    /** Nhãn loại biến động: "Nhập kho" / "Bán hàng" / "Nhập kho - ..."/"Xuất kho - ..." / "Trả hàng". */
    private String changeType;
    /** Mã chứng từ nguồn (mã hóa đơn, mã phiếu xuất, tên lô…). */
    private String reference;
    private String lotNumber;
    /** Số lượng thay đổi có dấu, theo đơn vị cơ bản: dương = nhập, âm = xuất. */
    private int quantityChange;
    /** Đơn vị của {@code quantityChange} — đơn vị nhập cho dòng "Nhập kho", còn lại là đơn vị cơ bản. */
    private String unitName;
    private String note;
    /** Tổng tồn kho (đơn vị cơ bản, mọi lô) ngay sau sự kiện này, null nếu không dựng lại được. */
    private Integer resultingStock;
}
