package com.example.project.repository;

import com.example.project.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Integer> {

    @Query("""
       select i
       from Invoice i
       left join fetch i.employeeID
       left join fetch i.customerID
       """)
    List<Invoice> findAllWithRelations();

    @Query("""
       select i
       from Invoice i
       left join fetch i.employeeID
       left join fetch i.customerID
       left join fetch i.originalInvoiceID
       left join fetch i.rootInvoiceID
       where i.id = :invoiceId
       """)
    Optional<Invoice> findByIdWithRelations(@Param("invoiceId") Integer invoiceId);

    /**
     * Sales in a half-open interval {@code [from, to)}, for tax-period and revenue aggregation.
     * {@code Invoice.date} is a {@code LocalDateTime} holding Vietnam wall-clock time (written by
     * {@code InvoiceService} as {@code LocalDateTime.now(VN_ZONE)}), so callers must pass VN
     * wall-clock bounds — see {@code TaxperiodsnapshotService}'s boundary helpers. The upper bound is
     * exclusive so a sale at 23:59 on the last day of a quarter still lands inside it.
     */
    @Query("""
       select i
       from Invoice i
       where i.date >= :from
         and i.date < :to
       order by i.date asc
       """)
    List<Invoice> findInPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
