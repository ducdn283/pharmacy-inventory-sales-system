package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One recent stock-movement row for the "Lịch sử tồn kho gần đây" preview block.
 *
 * <p>This is a lightweight preview assembled from import (Batch), sale (InvoiceDetail), stock-out
 * (StockOutDetail) and return (ReturnDetail) events — NOT the full Product Inventory History screen.
 * {@code occurredAt} is kept only for chronological sorting; the template uses {@code timeDisplay}.</p>
 *
 * <p>{@code resultingStock} is a best-effort PRODUCT-WIDE running balance (all batches combined —
 * the batches table above this preview already shows each individual batch's own current total, so
 * this is deliberately not per-batch), reconstructed backward from the product's real current total
 * stock through only the rows visible in this same preview (see
 * {@code ProductService.loadRecentHistory()}). It is NOT a true, complete ledger: an event older than
 * what this top-N preview shows breaks the chain for every row before it, surfacing as null (shown
 * as "—") rather than a stock figure that would have to go negative to "balance".</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductRecentHistoryResponse {
    /** Raw timestamp, used only to sort rows from the different sources. */
    private Instant occurredAt;
    private String timeDisplay;
    /** Movement type label: "Nhập kho" / "Bán hàng" / "Nhập kho - .../"Xuất kho - ..." / "Trả hàng". */
    private String changeType;
    /** Reference code of the source document (invoice code, stock-out code, batch name…). */
    private String reference;
    private String lotNumber;
    /** Signed base-unit quantity change: positive = stock in, negative = stock out. */
    private int quantityChange;
    /** Unit {@code quantityChange} is expressed in — the import unit for "Nhập kho", else the product's base unit. */
    private String unitName;
    private String note;
    /** Product's total base-unit stock (all batches) right after this event, or null if it couldn't
     *  be reconstructed. */
    private Integer resultingStock;
}
