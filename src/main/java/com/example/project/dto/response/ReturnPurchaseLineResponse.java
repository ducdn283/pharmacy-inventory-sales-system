package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * One returnable line of a chosen purchase invoice, fetched as JSON by the create screen after the
 * user picks a purchase. Carries the current on-hand stock so the UI can cap the input.
 */
@Getter
@AllArgsConstructor
public class ReturnPurchaseLineResponse {

    private Integer purchaseDetailId;

    private Integer productId;
    private String productName;

    private String lotNumber;
    private String expirationDateDisplay;

    private String unitName;

    /** Units originally imported on this line. */
    private Integer importedQty;
    /** Units already returned to the supplier across prior approved returns. */
    private Integer alreadyReturned;
    /** Current on-hand stock across the line's batches — the max this line can still return. */
    private Integer returnableQty;

    /** GROSS import price per import-unit (đã gồm thuế — chốt nhóm) — hiển thị cột "Đơn giá nhập". */
    private BigDecimal importPricePerBase;

    /** Đơn giá hoàn / đơn vị = 100% gross = đúng {@link #importPricePerBase} (NCC hoàn đúng số đã trả). */
    private BigDecimal refundUnitPrice;

    // vatRate đã bỏ 04/08/2026 — không còn tách thuế đầu vào để đảo ngược khi trả hàng NCC.
}
