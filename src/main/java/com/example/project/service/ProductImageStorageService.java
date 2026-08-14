package com.example.project.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Upload/xoá ảnh sản phẩm trên Cloudinary. {@code public_id} luôn là
 * {@code "pharmacy-products/" + product.code} — {@code Product.code} là duy nhất và không đổi sau
 * khi gán, nên dùng luôn làm khóa asset ổn định, không cần thêm cột lưu id riêng của Cloudinary.
 *
 * <p>Folder/public_id cố tình để phẳng (không chia theo {@code Type}) vì {@code Type.name} và
 * {@code typeId} của sản phẩm đều có thể đổi sau khi ảnh đã tồn tại, nếu chia theo Type sẽ dễ tạo
 * asset mồ côi/trùng lặp. Thay vào đó mỗi ảnh được gắn thêm tag Cloudinary {@code "type-{slug}"}
 * để vẫn lọc/quản lý được theo danh mục — xem {@link #typeTag(String)} và
 * {@link #retag(String, String, String)}.</p>
 */
@Service
public class ProductImageStorageService {

    private static final String FOLDER = "pharmacy-products";
    private static final String TYPE_TAG_FALLBACK = "type-chua-phan-loai";

    private static final Pattern DIACRITICS = Pattern.compile("\\p{Mn}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("^-+|-+$");

    private final Cloudinary cloudinary;

    public ProductImageStorageService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    /**
     * Upload (hoặc ghi đè nếu sản phẩm đã có ảnh) và trả về URL HTTPS công khai. {@code public_id}
     * truyền vào chỉ là mã sản phẩm thô (không ghép sẵn {@link #FOLDER}) vì Cloudinary tự thêm
     * tiền tố {@code folder} — nếu ghép sẵn sẽ bị lặp đường dẫn (vd.
     * {@code pharmacy-products/pharmacy-products/SP000002}). Public_id thực tế vẫn là
     * {@code "pharmacy-products/" + productCode}, khớp với {@link #publicId(String)} mà
     * {@link #retag}/{@link #delete} dùng.
     */
    public String upload(MultipartFile file, String productCode, String typeName) {
        try {
            Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                    "public_id", productCode,
                    "folder", FOLDER,
                    "overwrite", true,
                    "invalidate", true,
                    "tags", List.of(typeTag(typeName))
            ));
            return (String) result.get("secure_url");
        } catch (IOException e) {
            throw new IllegalStateException("Không thể tải ảnh lên Cloudinary", e);
        }
    }

    /**
     * Đổi tag danh mục của ảnh đã upload mà không cần upload lại — dùng khi đổi Loại hàng nhưng
     * ảnh giữ nguyên. Chỉ đụng vào 2 tag liên quan, các tag khác của ảnh không bị ảnh hưởng.
     */
    public void retag(String productCode, String oldTag, String newTag) {
        try {
            String[] publicIds = {publicId(productCode)};
            if (oldTag != null) {
                cloudinary.uploader().removeTag(oldTag, publicIds, ObjectUtils.emptyMap());
            }
            cloudinary.uploader().addTag(newTag, publicIds, ObjectUtils.emptyMap());
        } catch (IOException e) {
            throw new IllegalStateException("Không thể cập nhật tag ảnh trên Cloudinary", e);
        }
    }

    /** Xoá ảnh của mã sản phẩm này nếu có. */
    public void delete(String productCode) {
        try {
            cloudinary.uploader().destroy(publicId(productCode), ObjectUtils.emptyMap());
        } catch (IOException e) {
            throw new IllegalStateException("Không thể xoá ảnh trên Cloudinary", e);
        }
    }

    /** Trả về {@code "type-{slug}"} cho tên Loại hàng, hoặc tag mặc định nếu null/rỗng/không slug được. */
    public String typeTag(String typeName) {
        String slug = slugify(typeName);
        return slug.isEmpty() ? TYPE_TAG_FALLBACK : "type-" + slug;
    }

    // Chuẩn hoá tên Loại hàng thành slug không dấu, chữ thường, nối bằng dấu gạch ngang.
    private String slugify(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String dReplaced = trimmed.replace('đ', 'd').replace('Đ', 'D');
        String decomposed = Normalizer.normalize(dReplaced, Normalizer.Form.NFD);
        String withoutDiacritics = DIACRITICS.matcher(decomposed).replaceAll("");
        String lower = withoutDiacritics.toLowerCase(Locale.ROOT);
        String hyphenated = NON_ALNUM.matcher(lower).replaceAll("-");
        return EDGE_HYPHENS.matcher(hyphenated).replaceAll("");
    }

    // Dựng public_id Cloudinary đầy đủ (folder + mã sản phẩm) từ mã sản phẩm.
    private String publicId(String productCode) {
        return FOLDER + "/" + productCode;
    }
}
