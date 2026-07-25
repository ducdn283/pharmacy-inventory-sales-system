package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * One editable unit row nested under a product on the Price Settings screen
 * ({@code /owner/price-settings}) — one {@code ProductUnit}. Product-level context (code, name,
 * type, average import price) lives on the owning {@link PriceSettingProductRowResponse} instead of
 * being repeated on every unit, since the table groups units under their product.
 */
@Getter
@AllArgsConstructor
public class PriceSettingRowResponse {

    private Integer productUnitId;
    private String unitName;
    private boolean baseUnit;
    private BigDecimal ratio;

    private BigDecimal currentSellPrice;
}
