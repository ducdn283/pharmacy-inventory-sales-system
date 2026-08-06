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
       left join fetch i.signBy
       where i.id = :invoiceId
       """)
    Optional<Invoice> findByIdWithRelations(@Param("invoiceId") Integer invoiceId);

    /**
     * Sales in a half-open interval {@code [from, to)} that are still <strong>"còn hiệu lực"</strong>
     * for revenue/tax aggregation — the fix for a double-count bug where a superseded original and
     * its replacement/adjustment were both being summed. {@code Invoice.date} is a
     * {@code LocalDateTime} holding Vietnam wall-clock time (written by {@code InvoiceService} as
     * {@code LocalDateTime.now(VN_ZONE)}), so callers must pass VN wall-clock bounds — see
     * {@code TaxperiodsnapshotService}'s boundary helpers. The upper bound is exclusive so a sale at
     * 23:59 on the last day of a quarter still lands inside it.
     *
     * <p>An invoice counts when:
     * <ul>
     *   <li>it is a <strong>"Thay thế"</strong> (replacement) or <strong>"Điều chỉnh"</strong>
     *       (adjustment) invoice — always, since these already carry the post-return net amount
     *       ({@code total}); or</li>
     *   <li>it is a normal sale ("Bán hàng"/legacy "normal"/null) that has <strong>not</strong> been
     *       superseded by a "Thay thế" child ({@code ReturnService.isInvalidatedByReplacement}, done
     *       here as a proper query instead of loading every invoice into memory), <strong>and</strong>
     *       is not the one specific case where a "Thay thế" child should have existed but didn't: an
     *       <strong>unsigned</strong> invoice fully refunded ({@code returnStatus = 'FULL'}) with
     *       {@code appliedRefundRate = 100%} produces zero remaining lines, so
     *       {@code ReturnService.createReplacementInvoice} returns early and no child is ever
     *       created — the original must be treated as void by hand in that one case (confirmed with
     *       the BA; a <em>signed</em> original at {@code returnStatus = 'FULL'} is deliberately kept,
     *       since its own "Điều chỉnh" line(s) already net it to zero via plain addition).</li>
     * </ul>
     *
     * <p><strong>Known gap:</strong> this does not special-case the legacy "Hóa đơn GTGT" invoice
     * type (issued by group 3 before GTGT moved to the direct method) — such a row is treated as a
     * normal sale like any "Bán hàng" one. Pre-existing "Hóa đơn GTGT" rows in a period being
     * recomputed will therefore behave the same as "Bán hàng", which was not explicitly speced.</p>
     */
    @Query("""
       select i
       from Invoice i
       where i.date >= :from
         and i.date < :to
         and (
           i.invoiceType = 'Thay thế'
           or i.invoiceType = 'Điều chỉnh'
           or (
             (i.invoiceType is null or i.invoiceType = 'Bán hàng' or i.invoiceType = 'normal')
             and not exists (
               select 1 from Invoice r
               where r.invoiceType = 'Thay thế'
                 and r.originalInvoiceID = i
             )
             and not (
               i.signAt is null
               and (i.status is null or i.status <> 'Đã ký')
               and i.returnStatus = 'FULL'
             )
           )
         )
       order by i.date asc
       """)
    List<Invoice> findValidInPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
