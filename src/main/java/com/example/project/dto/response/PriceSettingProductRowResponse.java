package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * One collapsible product row on the Price Settings screen ({@code /owner/price-settings}). The
 * table lists products; expanding a row reveals its {@link PriceSettingRowResponse} unit rows,
 * which are the actually-editable cells.
 *
 * <p>{@code averageImportPricePerBase} is read-only reference info: the arithmetic mean
 * ("trung bình cộng") of {@code Batch.importPricePerBase} across the product's <strong>in-stock</strong>
 * batches only ({@code storageQuantity > 0} and not deactivated), so it reflects the cost of goods
 * actually sitting in the warehouse rather than being dragged around by long-since-sold-out lots.
 * It is GROSS/VAT-inclusive per the team's confirmed convention (see CLAUDE.md) and is never
 * written back or folded into a saved price. {@code null} when the product has no in-stock batch.</p>
 *
 * <p>{@code basePrice} is the product's representative price for the price asc/desc sort — the
 * base unit's {@code sellPrice}, since every other unit is derived from it by ratio.</p>
 */
@Getter
@AllArgsConstructor
public class PriceSettingProductRowResponse {

    private Integer productId;
    private String productCode;
    private String productName;
    private String typeName;

    private BigDecimal averageImportPricePerBase;
    private BigDecimal basePrice;

    private List<PriceSettingRowResponse> units;

    public int getUnitCount() {
        return units == null ? 0 : units.size();
    }
}
