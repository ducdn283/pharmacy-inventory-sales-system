package com.example.project.repository;

import com.example.project.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Integer> {

    /** Lấy toàn bộ sản phẩm kèm quan hệ cần cho màn danh sách (loại/nhà sản xuất), tránh N+1. */
    @Query("""
       select distinct p
       from Product p
       left join fetch p.typeID
       left join fetch p.producerID
       order by p.name asc
       """)
    List<Product> findAllWithRelations();

    /** Lấy 1 sản phẩm kèm quan hệ cần cho phần đầu màn Chi tiết hàng hóa. */
    @Query("""
       select p
       from Product p
       left join fetch p.typeID
       left join fetch p.producerID
       where p.productID = :productId
       """)
    Optional<Product> findDetailById(@Param("productId") Integer productId);

    /** Mã hàng là duy nhất; dùng để chặn trùng khi tạo sản phẩm mới. */
    boolean existsByCode(String code);

    /** Barcode duy nhất khi có nhập; dùng để chặn trùng khi tạo sản phẩm mới. */
    boolean existsByBarcode(String barcode);

    /** Kiểm tra trùng barcode khi sửa: true nếu có sản phẩm KHÁC đã dùng barcode này. */
    @Query("select count(p) > 0 from Product p where p.barcode = :barcode and p.productID <> :productId")
    boolean existsByBarcodeExcludingProduct(@Param("barcode") String barcode, @Param("productId") Integer productId);

    /**
     * Tên hàng hóa phải duy nhất.
     *
     * <p>So sánh dựa vào collation của cột ({@code utf8mb4_0900_ai_ci} — không phân biệt dấu và
     * hoa/thường), nên "Paracetamol", "paracetamol", "Páracetamol" đều tính là trùng tên mà không
     * cần {@code LOWER()} hay bỏ dấu trong query. Đây là tính chất của schema, không phải của code
     * — nếu đổi sang collation phân biệt hoa/thường thì việc kiểm trùng sẽ chỉ còn khớp chính xác.</p>
     */
    boolean existsByName(String name);

    /** Kiểm tra trùng tên khi sửa: true nếu có sản phẩm KHÁC đã dùng tên này. */
    @Query("select count(p) > 0 from Product p where p.name = :name and p.productID <> :productId")
    boolean existsByNameExcludingProduct(@Param("name") String name, @Param("productId") Integer productId);

    /** Số đăng ký duy nhất KHI CÓ NHẬP; để trống nghĩa là "chưa khai báo", không tính là trùng. */
    boolean existsByRegistrationNumber(String registrationNumber);

    /** Kiểm tra trùng số đăng ký khi sửa: true nếu có sản phẩm KHÁC đã dùng số này. */
    @Query("""
       select count(p) > 0
       from Product p
       where p.registrationNumber = :registrationNumber and p.productID <> :productId
       """)
    boolean existsByRegistrationNumberExcludingProduct(@Param("registrationNumber") String registrationNumber,
                                                        @Param("productId") Integer productId);

    /**
     * Số thứ tự lớn nhất trong các mã nội bộ dạng {@code SP<số>} (0 nếu chưa có mã nào).
     * Dùng để tự sinh mã sản phẩm tiếp theo. Câu lệnh đặc thù MySQL (REGEXP + CAST).
     */
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(code, 3) AS UNSIGNED)), 0) "
            + "FROM product WHERE code REGEXP '^SP[0-9]+$'", nativeQuery = true)
    long findMaxProductCodeSequence();

    /**
     * Nguồn gợi ý thuế suất VAT theo sản phẩm: (productID, vatRateOverride, type.defaultVATRate).
     * Dùng để điền sẵn "Thuế suất VAT" khi tạo Phiếu nhập — ưu tiên vatRateOverride nếu có, không
     * thì lấy defaultVATRate của Type.
     */
    @Query("select p.productID, p.vatRateOverride, t.defaultVATRate from Product p left join p.typeID t")
    List<Object[]> findVatRateSuggestions();
}