package com.example.project.repository;

import com.example.project.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;

public interface ExpenseRepository extends JpaRepository<Expense, Integer> {

    /**
     * Running costs paid in {@code [from, to)} — the deductible half of the group-3 profit
     * calculation.
     *
     * <p>Deliberately narrow. Only <em>approved</em> slips of the given types count, and only those
     * <strong>not</strong> tied to a purchase invoice: money paid for goods is already carried as
     * cost of goods sold, so counting the slip as well would deduct it twice. Refund payouts are
     * excluded for the mirror reason — they have already been taken off revenue.</p>
     */
    @Query("""
           select coalesce(sum(e.paid), 0)
           from Expense e
           where e.purchaseID is null
             and e.expenseType in :types
             and e.status in :approvedStatuses
             and e.date >= :from
             and e.date < :to
           """)
    BigDecimal sumOperatingCostInPeriod(@Param("from") Instant from,
                                        @Param("to") Instant to,
                                        @Param("types") Collection<String> types,
                                        @Param("approvedStatuses") Collection<String> approvedStatuses);
}