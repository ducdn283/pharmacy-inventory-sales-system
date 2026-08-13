package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class StockAdjustmentDetailItemResponse {

    private Integer productId;
    private String productName;
    private String lotNumber;
    private LocalDate expirationDate;
    private String expirationDateDisplay;
    private String unitName;
    /** Current on-hand stock of the batch (live value; after approval it already reflects this line). */
    private Integer currentStock;
    /** The adjusted amount (delta), always positive; pair it with {@link #direction} for the sign. */
    private Integer quantity;
    /** Movement direction of this line: {@code IN} (increase) or {@code OUT} (decrease). */
    private String direction;
    /** Vietnamese label for {@link #direction}: "Tăng" / "Giảm". */
    private String directionDisplay;
    private BigDecimal unitCostPrice;
    private BigDecimal lineCost;
    private String note;

    /**
     * Giá bán tham chiếu tại thời điểm ghi nhận (snapshot của {@code ProductUnit.sellPrice}).
     * Dùng cho INTERNAL_USE/GIFT/SAMPLE và cho dòng {@code COUNT} chiều IN (định giá hàng thừa).
     *
     * <p>3 cột {@code vatRate/preTaxAmount/vatAmount} đã bị BỎ khỏi bảng: hộ kinh doanh tính GTGT theo
     * {@code doanh thu × tỷ lệ %}, không có khấu trừ đầu ra/đầu vào nên phiếu điều chỉnh kho không
     * phát sinh số thuế nào để lưu (Tax_Invoice.xlsx sheet 02, 04/08/2026).</p>
     */
    private BigDecimal refSellPrice;

    // Chỉ có giá trị với phiếu DATE_ADJUSTMENT: hạn dùng trước và sau khi sửa.
    private String oldExpirationDateDisplay;
    private String newExpirationDateDisplay;

    /**
     * Giá trị đền bù của dòng, tính theo <strong>GIÁ BÁN</strong> niêm yết (không phải giá vốn) —
     * chỉ có ý nghĩa với phiếu {@code DESTROY_EMPLOYEE_FAULT} / {@code COUNT_DECREASE}, là số tiền
     * đề xuất cho phiếu thu "nhân viên đền bù". Tính LIVE lúc đọc, không lưu cột riêng.
     */
    private BigDecimal reimbursementUnitPrice;
    private BigDecimal reimbursementValue;
}