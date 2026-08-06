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
     * Số hóa đơn bán ra khỏi lô {@code batchId} SAU mốc {@code after}. Dùng để chặn việc hủy (đảo
     * ngược) một phiếu điều chỉnh kho khi lô đã bị giao dịch khác động vào kể từ lúc phiếu đó được
     * áp dụng — {@code Dac_ta_Income_StockAdjustment.xlsx} sheet 05.
     *
     * <p>{@code Invoice.date} là giờ tường VN dạng {@code LocalDateTime}, nên bên gọi phải quy mốc
     * so sánh về cùng múi giờ trước khi truyền vào.</p>
     */
    @Query("""
       select count(d)
       from Invoicedetail d
       where d.batchID.id = :batchId
         and d.invoiceID.date > :after
       """)
    long countSalesFromBatchAfter(@Param("batchId") Integer batchId,
                                  @Param("after") LocalDateTime after);

    /**
     * Cost of the goods sold in {@code [from, to)}, valued at what each batch actually cost to buy:
     * {@code baseQtyDeducted × Batch.importPricePerBase}. Feeds the profit-based personal income tax
     * (group 3 always; group 2 when it opts into the profit method).
     *
     * <p>Bounds are VN wall-clock {@code LocalDateTime}s because they filter on
     * {@code Invoice.date} — see {@code TaxperiodsnapshotService}'s boundary helpers.</p>
     *
     * <p>Only counts lines on an invoice that is <strong>"còn hiệu lực"</strong> — same predicate as
     * {@code InvoiceRepository.findValidInPeriod}, duplicated here (JPQL has no shared fragments)
     * rather than reusing that query's result set, since this needs to join down to
     * {@code Invoicedetail}/{@code Batch} in one aggregate query instead of walking lines in Java.
     * Without this filter a superseded original's lines and its replacement's lines would both be
     * summed, double-counting giá vốn the same way revenue used to double-count doanh thu.</p>
     *
     * <p><strong>2026-08-06 fix — kept in lockstep with {@code InvoiceRepository.findValidInPeriod}:
     * </strong> dropped the {@code invoiceType = 'Điều chỉnh'} branch (nothing writes that value any
     * more — confirmed by grep, the only remaining references were this filter and a read-only
     * display fallback) and dropped the {@code status <> 'Đã ký'} condition gating the
     * fully-refunded-with-no-replacement exclusion (a leftover from the pre-04/08/2026 signed/unsigned
     * split; {@code ReturnService} no longer branches on it, every return goes through the same
     * {@code createReplacementInvoice()} path). Before this fix, a <em>signed</em> original fully
     * refunded with no replacement created was left in "còn hiệu lực" by mistake, double-counting its
     * already-void giá vốn.</p>
     */
    @Query("""
       select coalesce(sum(d.baseQtyDeducted * b.importPricePerBase), 0)
       from Invoicedetail d
       join d.batchID b
       join d.invoiceID i
       where i.date >= :from
         and i.date < :to
         and (
           i.invoiceType = 'Thay thế'
           or (
             (i.invoiceType is null or i.invoiceType = 'Bán hàng' or i.invoiceType = 'normal')
             and not exists (
               select 1 from Invoice r
               where r.invoiceType = 'Thay thế'
                 and r.originalInvoiceID = i
             )
             and (i.returnStatus is null or i.returnStatus <> 'FULL')
           )
         )
       """)
    BigDecimal sumCostOfGoodsSoldInPeriod(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);
}