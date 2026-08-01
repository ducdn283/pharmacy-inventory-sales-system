package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Full payload for the Product Detail screen. All values are display-ready; the store is single
 * (no per-branch breakdown) so stock is a single system-wide total.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductDetailResponse {

    // --- 1. General information ---
    private Integer productId;
    private String code;
    private String name;
    private String barcode;
    private String imageUrl;
    private String typeName;
    private String producerName;
    private String originName;
    private String registrationNumber;
    private boolean statusActive;
    private String statusLabel;
    private Integer minStock;
    private Integer maxStock;
    private String note;
    /** Active ingredients formatted as "name strength" (may be empty). */
    private List<String> ingredients;
    private List<ProductUnitDetailResponse> units;
    /** Name of the unit flagged as base unit, or "" if none is set up yet — for unit-clarifying display. */
    private String baseUnitName;

    /** Total on-hand stock (single store — no per-branch breakdown). */
    private long totalStock;
    private String stockStatusLabel;
    private String stockStatusCss;

    // --- 2. In-stock batches ---
    private List<ProductBatchDetailResponse> batches;

    // --- 3. Recent stock-movement preview (only populated when the role may view it) ---
    private boolean canViewRecentHistory;
    private List<ProductRecentHistoryResponse> recentHistory;
}
