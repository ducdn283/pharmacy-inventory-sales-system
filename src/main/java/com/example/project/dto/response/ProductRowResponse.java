package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One row of the Product List screen. Carries already-resolved, display-ready values
 * (type name, ingredient, main selling unit, sell price, current stock, stock status)
 * so the Thymeleaf template never touches lazy entity relations.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductRowResponse {
    /** Primary key – used to build the detail link. */
    private Integer productId;
    /** Business code shown in the "Mã hàng" column. */
    private String code;
    private String name;
    private String typeName;
    /** Joined active ingredient(s); empty string when the product has none. */
    private String ingredient;
    /** Main selling unit name (default unit, else base unit). */
    private String unitName;
    /** Sell price of the main selling unit; null when no unit is configured. */
    private BigDecimal sellPrice;
    /** Current on-hand stock = SUM(Batch.storageQuantity). */
    private long stock;
    /** Stock-status label: "Còn hàng" / "Sắp hết" / "Hết hàng". */
    private String stockStatusLabel;
    /** CSS class for the stock-status badge. */
    private String stockStatusCss;
    /** Prescription-required display; the schema has no such column → always "—". */
    private String prescriptionDisplay;
}
