package com.example.project.repository;

import com.example.project.entity.Position;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

/**
 * Truy vấn dữ liệu vị trí lưu kho ({@link Position}) cho màn quản lý Owner và màn hàng hóa.
 */
public interface PositionRepository extends JpaRepository<Position, Integer> {

    /** Vị trí lưu kho của một hàng hóa — màn chi tiết / sửa hàng hóa. */
    @Query("""
            SELECT p FROM Position p
            WHERE p.productID.productID = :productId
            ORDER BY p.id ASC
            """)
    List<Position> findByProductId(@Param("productId") Integer productId);

    /** Kiểm tra sản phẩm đã được gắn vị trí chưa — ràng buộc 1 sản phẩm / 1 vị trí. */
    @Query("""
            SELECT COUNT(p) > 0 FROM Position p
            WHERE p.productID.productID = :productId
            """)
    boolean existsByProductId(@Param("productId") Integer productId);

    /** Kiểm tra sản phẩm đã có vị trí khác (trừ bản ghi đang sửa). */
    @Query("""
            SELECT COUNT(p) > 0 FROM Position p
            WHERE p.productID.productID = :productId AND p.id <> :positionId
            """)
    boolean existsByProductIdAndIdNot(@Param("productId") Integer productId,
                                      @Param("positionId") Integer positionId);

    /** Tập id sản phẩm đã có vị trí — lọc dropdown form tạo vị trí. */
    @Query("""
            SELECT p.productID.productID FROM Position p
            WHERE p.productID IS NOT NULL
            """)
    Set<Integer> findAllAssignedProductIds();

    /** Toàn bộ vị trí kèm thông tin hàng hóa — danh sách Owner và màn bán hàng. */
    @Query("""
            SELECT p FROM Position p
            JOIN FETCH p.productID
            ORDER BY p.productID.productID ASC, p.id ASC
            """)
    List<Position> findAllWithProduct();

    /**
     * Danh sách vị trí có lọc theo tên vị trí, tên hoặc mã hàng hóa.
     * <p>Hiện chưa dùng trực tiếp — {@link com.example.project.service.PositionService#list}
     * lọc trong bộ nhớ để hỗ trợ tìm không dấu.</p>
     */
    @Query("""
            SELECT p FROM Position p
            JOIN p.productID prod
            WHERE (:keyword = '' OR LOWER(p.name) LIKE LOWER(CONCAT(:keyword, '%'))
                   OR LOWER(prod.name) LIKE LOWER(CONCAT(:keyword, '%'))
                   OR LOWER(prod.code) LIKE LOWER(CONCAT(:keyword, '%')))
            """)
    Page<Position> findFiltered(@Param("keyword") String keyword, Pageable pageable);
}
