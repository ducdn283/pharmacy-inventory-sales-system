package com.example.project.repository;

import com.example.project.entity.Income;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Truy vấn dữ liệu phiếu thu ({@link Income}).
 */
public interface IncomeRepository extends JpaRepository<Income, Integer> {

    /** Một phiếu thu kèm người lập và các chứng từ liên quan — trang chi tiết. */
    @Query("""
           select i
           from Income i
           left join fetch i.applicantID
           left join fetch i.invoiceID
           left join fetch i.returnID
           left join fetch i.shiftReportID
           left join fetch i.supplierID
           left join fetch i.customerID
           left join fetch i.accountID
           left join fetch i.stockAdjustmentID
           left join fetch i.shiftReportOfAccountID
           where i.id = :id
           """)
    Optional<Income> findByIdWithRelations(Integer id);

    /** Danh sách phiếu thu kèm quan hệ — màn danh sách và lọc trong bộ nhớ. */
    @Query("""
           select i
           from Income i
           left join fetch i.applicantID
           left join fetch i.invoiceID
           left join fetch i.returnID
           left join fetch i.shiftReportID
           left join fetch i.supplierID
           left join fetch i.customerID
           left join fetch i.accountID
           left join fetch i.stockAdjustmentID
           left join fetch i.shiftReportOfAccountID
           """)
    List<Income> findAllWithRelations();

    /**
     * Tổng {@code Income.amount} theo loại phiếu thu trong khoảng {@code [from, to)} — phục vụ
     * doanh thu tính thuế (EMPLOYEE, mục D). Chỉ phiếu hoàn thành mới tính; {@code incomeType}/
     * {@code status} so khớp giá trị lưu DB (nhãn tiếng Việt). {@code statuses} có thể gồm cả
     * legacy "Duyệt" và "Hoàn thành" — giống {@code IncomeService.isCompletedStatus}.
     */
    @Query("""
           select coalesce(sum(i.amount), 0)
           from Income i
           where i.incomeType = :incomeType
             and i.status in :statuses
             and i.date >= :from
             and i.date < :to
           """)
    BigDecimal sumByTypeInPeriod(@Param("incomeType") String incomeType,
                                 @Param("statuses") Collection<String> statuses,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);
}
