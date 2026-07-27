package com.example.project.repository;

import com.example.project.entity.Purchaseinvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PurchaseinvoiceRepository extends JpaRepository<Purchaseinvoice, Integer> {

    @Query("""
           select p
           from Purchaseinvoice p
           left join fetch p.supplierID
           left join fetch p.employeeID
           left join fetch p.procurementID
           order by p.date desc
           """)
    List<Purchaseinvoice> findAllWithRelations();

    @Query("""
           select p
           from Purchaseinvoice p
           left join fetch p.supplierID
           left join fetch p.employeeID
           left join fetch p.procurementID
           where p.id = :id
           """)
    Optional<Purchaseinvoice> findByIdWithRelations(Integer id);

    /**
     * Import invoices received in the half-open interval {@code [from, to)}, for input-VAT
     * aggregation. {@code Purchaseinvoice.date} is a real {@code Instant}, so the bounds are the
     * instants of the VN-wall-clock period edges — unlike {@link InvoiceRepository#findInPeriod},
     * which takes local date-times. Deductibility is <em>not</em> filtered here: it is recomputed in
     * Java on every read (see {@code PurchaseinvoiceService.isDeductible}) and the stored
     * {@code isValidForDeduction} column is not trusted.
     */
    @Query("""
           select p
           from Purchaseinvoice p
           where p.date >= :from
             and p.date < :to
           order by p.date asc
           """)
    List<Purchaseinvoice> findInPeriod(@Param("from") Instant from, @Param("to") Instant to);
}