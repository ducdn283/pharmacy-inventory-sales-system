package com.example.project.repository;

import com.example.project.entity.Return;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReturnRepository extends JpaRepository<Return, Integer> {

    /** All returns with their invoice / customer / creator, for the list screen. */
    @Query("""
       select r
       from Return r
       left join fetch r.invoiceID inv
       left join fetch inv.customerID
       left join fetch r.returnedBy
       """)
    List<Return> findAllWithRelations();

    /** One return with its invoice / customer / creator, for the detail screen. */
    @Query("""
       select r
       from Return r
       left join fetch r.invoiceID inv
       left join fetch inv.customerID
       left join fetch r.returnedBy
       where r.id = :id
       """)
    Optional<Return> findByIdWithRelations(@Param("id") Integer id);

    /** Customer returns for a sale invoice, newest first. */
    List<Return> findByInvoiceID_IdOrderByReturnDateDesc(Integer invoiceId);

    /**
     * Supplier returns for a purchase invoice, newest first — đối xứng với
     * {@link #findByInvoiceID_IdOrderByReturnDateDesc}. Dùng để dò phiếu trùng khi người dùng bấm
     * Tạo lần thứ hai (xem {@code ReturnPurchaseService#findRecentDuplicate}).
     */
    List<Return> findByPurchaseID_IdOrderByReturnDateDesc(Integer purchaseId);

    /**
     * Return slips — customer and supplier alike — whose {@code returnDate} falls in the half-open
     * interval {@code [from, to)}. Used by the tax period, where the docx is explicit that the
     * period a refund is deducted from is <em>derived from {@code returnDate}</em>: "trừ vào kỳ
     * phát sinh trả hàng". Callers discriminate the two kinds by FK afterwards
     * ({@code invoiceID != null} = customer), the same way every other service does.
     */
    @Query("""
       select r
       from Return r
       where r.returnDate >= :from
         and r.returnDate < :to
       order by r.returnDate asc
       """)
    List<Return> findInPeriod(@Param("from") Instant from, @Param("to") Instant to);
}
