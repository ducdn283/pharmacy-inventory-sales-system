package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * One <strong>in-stock</strong> batch of a product, as plotted on the Price Settings detail modal
 * ({@code /owner/price-settings}). One point on the chart = one lot still sitting in the warehouse.
 *
 * <p>Every money figure is <strong>per base unit</strong> so the batch's cost and the product's sell
 * price are directly comparable — {@code Batch.importPricePerBase} is already per base unit, and the
 * sell price the chart draws against is the base unit's. Both are <strong>GROSS</strong>
 * (VAT-inclusive) per the team's confirmed convention; see {@code CLAUDE.md}.</p>
 *
 * <p>Dates are pre-formatted {@code String}s: this DTO is serialised to JSON for the modal, and the
 * project's Thymeleaf/JS convention is that a date crossing into a script is already a string.</p>
 */
@Getter
@AllArgsConstructor
public class PriceSettingBatchPointResponse {

    private Integer batchId;
    private String batchCode;
    /** Lot number, or {@code "—"} when the batch was received without one. */
    private String lotNumber;
    /** {@code dd/MM/yyyy}, or {@code "—"} when the product has no expiry. */
    private String expirationDate;

    /** Remaining quantity, in base units. */
    private Integer storageQuantity;

    /** {@code Batch.importPricePerBase} — GROSS. */
    private BigDecimal importPricePerBase;

    /**
     * VAT already embedded in {@link #importPricePerBase}: {@code gross × rate / (100 + rate)}.
     *
     * <p>This is a real number for every group — it is what the pharmacy actually paid the supplier
     * in tax. Whether it can be <em>deducted</em> is a different question and depends on the revenue
     * group (and, per invoice, on {@code Purchaseinvoice.isValidForDeduction}), which is why the
     * modal only surfaces it as deductible under the deduction method.</p>
     */
    private BigDecimal embeddedInputVat;

    /**
     * {@code sellPricePerBase − importPricePerBase}. <strong>Can be negative</strong> — a lot bought
     * above the current sell price is exactly the thing this screen exists to make visible, so it is
     * never floored at zero.
     */
    private BigDecimal grossMargin;

    /** {@link #grossMargin} as a percentage of the sell price; {@code null} when there is no price. */
    private BigDecimal grossMarginPercent;
}
