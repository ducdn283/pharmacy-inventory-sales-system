package com.example.project.repository;

import com.example.project.entity.Invoicedetail;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface InvoicedetailRepository extends JpaRepository<Invoicedetail, Integer> {

    /** Most recent sale lines of one product, for the Product Detail history preview. */
    @Query("""
       select d
       from Invoicedetail d
       left join fetch d.invoiceID
       left join fetch d.batchID
       where d.productID.productID = :productId
       order by d.invoiceID.date desc
       """)
    List<Invoicedetail> findRecentSalesByProduct(@Param("productId") Integer productId,
                                                 Pageable pageable);

    @Query("""
       select d
       from Invoicedetail d
       left join fetch d.productID
       left join fetch d.productUnitID
       left join fetch d.batchID b
       left join fetch b.importUnitID
       where d.invoiceID.id = :invoiceId
       order by d.id
       """)
    List<Invoicedetail> findByInvoiceIdWithRelations(@Param("invoiceId") Integer invoiceId);

    @Query("""
       select d.invoiceID.id, sum(d.quantity), sum(coalesce(d.returnedQty, 0))
       from Invoicedetail d
       group by d.invoiceID.id
       """)
    List<Object[]> sumQuantitiesGroupedByInvoice();

    /**
     * Cost of the goods sold in {@code [from, to)}, valued at what each batch actually cost to buy:
     * {@code baseQtyDeducted × Batch.importPricePerBase}. Feeds the group-3 personal income tax,
     * which taxes profit rather than revenue.
     *
     * <p>Bounds are VN wall-clock {@code LocalDateTime}s because they filter on
     * {@code Invoice.date} — see {@code TaxperiodsnapshotService}'s boundary helpers.</p>
     */
    @Query("""
       select coalesce(sum(d.baseQtyDeducted * b.importPricePerBase), 0)
       from Invoicedetail d
       join d.batchID b
       where d.invoiceID.date >= :from
         and d.invoiceID.date < :to
       """)
    BigDecimal sumCostOfGoodsSoldInPeriod(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);
}