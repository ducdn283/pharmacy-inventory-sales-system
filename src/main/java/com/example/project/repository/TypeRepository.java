package com.example.project.repository;

import com.example.project.entity.Type;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Truy vấn dữ liệu loại hàng ({@link Type}) cho màn quản lý Owner.
 */
public interface TypeRepository extends JpaRepository<Type, Integer> {

    /**
     * Danh sách loại hàng có lọc theo từ khóa (tên hoặc nhóm) và nhóm mặt hàng.
     * <p>Hiện chưa dùng trực tiếp — {@link com.example.project.service.TypeService#list}
     * lọc trong bộ nhớ để hỗ trợ tìm không dấu.</p>
     */
    @Query("""
            SELECT t FROM Type t
            WHERE (:keyword = '' OR LOWER(t.name) LIKE LOWER(CONCAT(:keyword, '%'))
                   OR LOWER(t.sortType) LIKE LOWER(CONCAT(:keyword, '%')))
              AND (:sortType = '' OR LOWER(t.sortType) = LOWER(:sortType))
            """)
    Page<Type> findFiltered(@Param("keyword") String keyword,
                            @Param("sortType") String sortType,
                            Pageable pageable);

    /** Các giá trị {@code sortType} khác nhau — dropdown lọc / form tạo-sửa Owner. */
    @Query("SELECT DISTINCT t.sortType FROM Type t WHERE t.sortType IS NOT NULL ORDER BY t.sortType")
    List<String> findDistinctSortTypes();
}
