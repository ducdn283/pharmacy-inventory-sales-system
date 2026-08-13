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

public interface IncomeRepository extends JpaRepository<Income, Integer> {

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
     * Sum of {@code Income.amount} for one income type in {@code [from, to)} — feeds tax-period
     * taxable-income-only revenue (EMPLOYEE, mục D). Only
     * completed income counts as real money received; {@code incomeType}/{@code status} are matched
     * as stored — {@code Income} persists the Vietnamese label, not the code, so callers pass
     * {@code IncomeTypeOptionResponse.labelOf(...)}. {@code statuses} takes more than one value so a
     * caller can include the legacy "Duyệt" label alongside "Hoàn thành" the same way
     * {@code IncomeService.isCompleted} does.
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
