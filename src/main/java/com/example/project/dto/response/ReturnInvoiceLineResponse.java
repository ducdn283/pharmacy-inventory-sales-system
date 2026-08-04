package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * One returnable line of a chosen invoice, fetched as JSON by the create screen after the user picks
 * an invoice. Carries the still-returnable quantity so the UI can cap the input.
 */
@Getter
@AllArgsConstructor
public class ReturnInvoiceLineResponse {

    private Integer invoiceDetailId;

    private Integer productId;
    private String productName;

    private String lotNumber;
    private String expirationDateDisplay;

    private String unitName;

    /** Units originally sold on this line. */
    private Integer soldQty;
    /** Units already returned across prior returns. */
    private Integer returnedQty;
    /** {@code soldQty - returnedQty} — the max this line can still return. */
    private Integer returnableQty;

    private BigDecimal unitSellPrice;

    /**
     * Whether this line's returned goods will be put back into stock — hard-coded by item type
     * (only the manufacturer's default packaging unit, {@code productunit.isDefault}). Shown read-only
     * on the create screen; there is no manual checkbox .
     */
    private boolean restockable;
}
