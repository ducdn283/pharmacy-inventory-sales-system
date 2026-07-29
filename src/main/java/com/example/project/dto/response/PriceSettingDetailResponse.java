package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payload of the Price Settings detail modal — fetched per product from
 * {@code GET /owner/price-settings/{productId}/detail}, not rendered with the list, so opening the
 * screen costs nothing extra and only the product the Owner actually clicks is loaded.
 *
 * <p>It answers two questions on one panel: <em>what did the stock on hand cost me versus what am I
 * selling it for</em> ({@link #batches} against {@link #sellPricePerBase}, drawn as the chart), and
 * <em>what does the tax regime I am on do to that margin</em> ({@link #taxProjection}).</p>
 *
 * <p>{@link #batches} holds only <strong>in-stock, active</strong> lots — a sold-out lot says
 * nothing about the cost of what is left to price, which is the same rule the list column's
 * average already follows.</p>
 */
@Getter
@AllArgsConstructor
public class PriceSettingDetailResponse {

    private Integer productId;
    private String productCode;
    private String productName;
    private String typeName;

    /** Name of the base unit every money figure on this panel is expressed in ("Viên"). */
    private String baseUnitName;
    /** Current base-unit sell price (GROSS); {@code null} when no base unit is priced. */
    private BigDecimal sellPricePerBase;

    /** Arithmetic mean of {@link #batches}' import prices; {@code null} when there is no stock. */
    private BigDecimal averageImportPricePerBase;
    /** Cheapest in-stock lot, for the chart's reference band; {@code null} when there is no stock. */
    private BigDecimal minImportPricePerBase;
    /** Dearest in-stock lot; {@code null} when there is no stock. */
    private BigDecimal maxImportPricePerBase;
    /** Total remaining quantity across {@link #batches}, in base units. */
    private long totalStock;

    private List<PriceSettingBatchPointResponse> batches;

    private PriceSettingTaxProjectionResponse taxProjection;

    /**
     * True when at least one in-stock lot cost more than the product currently sells for — the
     * chart highlights those bars, because it is the case a price screen exists to catch.
     */
    public boolean isHasLossMakingBatch() {
        return batches != null && batches.stream()
                .anyMatch(batch -> batch.getGrossMargin() != null
                        && batch.getGrossMargin().signum() < 0);
    }
}
