package com.example.project.repository;

import com.example.project.entity.Type;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TypeRepository extends JpaRepository<Type, Integer> {

    //hiện danh sách loại (có tìm kiếm theo tên hoặc nhóm)
    @Query("""
            SELECT t FROM Type t
            WHERE (:keyword = '' OR LOWER(t.name) LIKE LOWER(CONCAT(:keyword, '%'))
                   OR LOWER(t.sortType) LIKE LOWER(CONCAT(:keyword, '%')))
              AND (:sortType = '' OR LOWER(t.sortType) = LOWER(:sortType))
            """)
    Page<Type> findFiltered(@Param("keyword") String keyword,
                            @Param("sortType") String sortType,
                            Pageable pageable);

    //lấy danh sách các sortType
    @Query("SELECT DISTINCT t.sortType FROM Type t WHERE t.sortType IS NOT NULL ORDER BY t.sortType")
    List<String> findDistinctSortTypes();
}
