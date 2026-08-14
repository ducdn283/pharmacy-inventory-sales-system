package com.example.project.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Payload của form Tạo/Sửa hàng hóa.
 *
 * <p>{@code itemGroup} (Thuốc / Hàng hóa / Thiết bị y tế) chỉ dùng để quyết định form hiện phần
 * nào, KHÔNG lưu xuống DB — phân loại thật lưu ở {@code Type} ({@code typeId}). Form này chỉ tạo
 * phần "gốc" của sản phẩm: Product + ProductUnit + MedicineAPI (tuỳ chọn) + Position (tuỳ chọn),
 * không đụng đến tồn kho (Batch) hay hạn dùng — hạn dùng gắn với từng lô, tạo sau khi nhập hàng.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ProductCreateRequest {

    /** Chỉ dùng trong form, lấy từ {@code Type.sortType}; không lưu DB. */
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

    /** Ảnh mới cần upload (tuỳ chọn); bỏ trống thì bỏ qua. Không có giá trị khi GET form Sửa. */
    private MultipartFile imageFile;
    /** URL ảnh hiện tại, chỉ để preview ở form Sửa; không gửi lại khi submit. */
    private String existingImageUrl;
    /** Chỉ dùng khi Sửa: xoá ảnh hiện tại mà không thay ảnh mới. Bỏ qua nếu đã có {@link #imageFile}. */
    private Boolean removeImage;

    private List<ProductUnitCreateRequest> units = new ArrayList<>();
    private List<ProductIngredientCreateRequest> ingredients = new ArrayList<>();
    private List<ProductPositionCreateRequest> positions = new ArrayList<>();
}
