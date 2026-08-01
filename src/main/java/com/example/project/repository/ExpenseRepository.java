package com.example.project.repository;

import com.example.project.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ExpenseRepository
        extends JpaRepository<Expense, Integer> {

    @Query("""
           select e
           from Expense e
           left join fetch e.applicantID
           left join fetch e.returnID r
           left join fetch r.invoiceID ri
           left join fetch ri.customerID
           left join fetch r.purchaseID rp
           left join fetch rp.supplierID
           left join fetch e.purchaseID p
           left join fetch p.supplierID
           left join fetch e.shiftReportID
           left join fetch e.supplierID
           left join fetch e.customerID
           left join fetch e.accountID
           order by e.date desc
           """)
    List<Expense> findAllWithRelations();

    @Query("""
           select coalesce(sum(e.paid), 0)
           from Expense e
           where e.purchaseID is null
             and e.expenseType in :types
             and e.status in :approvedStatuses
             and e.date >= :from
             and e.date < :to
           """)
    BigDecimal sumOperatingCostInPeriod(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("types") Collection<String> types,
            @Param("approvedStatuses")
            Collection<String> approvedStatuses
    );
}