package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * One selectable product on the create-invoice screen: identity + current base-unit stock + its
 * sellable units. Baked into inline JS (like the purchase-invoice create page) so the picker does
 * not touch lazy entity relations.
 */
@Getter
@AllArgsConstructor
public class SellProductOptionResponse {

    private Integer productId;
    private String code;
    private String name;
    private String barcode;

    /** On-hand stock in the base unit (SUM of batch storageQuantity, including expired lots). */
    private long baseStock;

    private List<SellUnitOptionResponse> units;

    /** In-stock batches (soonest-expiry first) for lot selection. */
    private List<SellBatchOptionResponse> batches;

    /** True when the product type is {@code Thuốc kê đơn}. */
    private boolean requiresPrescription;

    /** Storage positions configured for this product (e.g. Kệ A1). */
    private List<String> storagePositions;
}
