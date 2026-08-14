package com.example.project.repository;

import com.example.project.entity.Stockadjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

public interface StockadjustmentRepository extends JpaRepository<Stockadjustment, Integer> {

    @Query("""
           select s
           from Stockadjustment s
           left join fetch s.stockReviewID sc
           left join fetch sc.createdBy
           order by s.date desc
           """)
    List<Stockadjustment> findAllWithRelations();

    @Query("""
           select s
           from Stockadjustment s
           left join fetch s.stockReviewID sc
           left join fetch sc.createdBy
           where s.id = :id
           """)
    Optional<Stockadjustment> findByIdWithRelations(Integer id);

    /**
     * Phiếu điều chỉnh lập sau mốc {@code after}, mới nhất trước. Dùng để dò phiếu trùng khi người
     * dùng bấm Tạo lần thứ hai (xem {@code StockadjustmentService#findRecentDuplicate}). Bảng này
     * KHÔNG có cột người thao tác nên chỉ lọc được theo thời gian — chấp nhận được vì màn này
     * Owner-only.
     */
    List<Stockadjustment> findByDateAfterOrderByDateDesc(Instant after);
}