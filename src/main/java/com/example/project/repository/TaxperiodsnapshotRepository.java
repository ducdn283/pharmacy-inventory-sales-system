package com.example.project.repository;

import com.example.project.entity.Taxperiodsnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Kỳ thuế đã chốt. Entity không có quan hệ nào, các query ở đây chỉ để sắp thứ tự — mỗi kỳ cần biết
 * chuỗi kỳ trước nó (nhóm doanh thu và số khấu trừ chuyển tiếp đều lấy từ kỳ liền trước).
 */
public interface TaxperiodsnapshotRepository extends JpaRepository<Taxperiodsnapshot, Integer> {

    /** Kỳ mới nhất trước — thứ tự màn danh sách hiển thị. */
    @Query("""
           select t
           from Taxperiodsnapshot t
           order by t.startDate desc, t.id desc
           """)
    List<Taxperiodsnapshot> findAllNewestFirst();

    /** Kỳ cũ nhất trước — thứ tự cần duyệt chuỗi kỳ. */
    @Query("""
           select t
           from Taxperiodsnapshot t
           order by t.startDate asc, t.id asc
           """)
    List<Taxperiodsnapshot> findAllOldestFirst();

    /** Kỳ đã chốt gần nhất — nhóm và số khấu trừ chuyển tiếp của nó quyết định kỳ chốt tiếp theo. */
    Optional<Taxperiodsnapshot> findFirstByOrderByStartDateDescIdDesc();

    /** Chống chốt trùng một quý — {@code periodLabel} là khóa nghiệp vụ. */
    List<Taxperiodsnapshot> findByPeriodLabelOrderByIdAsc(String periodLabel);

    /** Kiểm tra một nhãn kỳ đã tồn tại (đã chốt) hay chưa. */
    boolean existsByPeriodLabel(String periodLabel);
}
