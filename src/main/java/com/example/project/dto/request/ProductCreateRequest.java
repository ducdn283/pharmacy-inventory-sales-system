package com.example.project.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Payload of the Create Product form.
 *
 * <p>{@code itemGroup} (Thuốc / Hàng hóa / Thiết bị y tế) only drives which sections the form
 * shows; it is NOT persisted as a new column — the persisted classification is {@code Type}
 * ({@code typeId}). This form creates only the product master: Product + ProductUnit + optional
 * MedicineAPI + optional Position. It does not touch Batch/stock or expiry — expiry lives on
 * individual batches created later during goods-receipt.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ProductCreateRequest {

    /** Form-only selector derived from {@code Type.sortType}; not persisted. */
    private String itemGroup;

    private String name;
    private String code;
    private String barcode;
    private Integer typeId;
    private Integer producerId;
    private String origin;
    private String registrationNumber;
    private Integer minStock;
    private Integer maxStock;
    private Boolean status;
    private String note;

    /** New photo to upload (optional); ignored when empty. Never populated on the Edit GET — see {@link #existingImageUrl}. */
    private MultipartFile imageFile;
    /** Current photo URL, for the Edit form's preview only; not bound back on submit. */
    private String existingImageUrl;
    /** Edit only: remove the current photo without replacing it. Ignored when {@link #imageFile} is also set. */
    private Boolean removeImage;

    private List<ProductUnitCreateRequest> units = new ArrayList<>();
    private List<ProductIngredientCreateRequest> ingredients = new ArrayList<>();
    private List<ProductPositionCreateRequest> positions = new ArrayList<>();
}
