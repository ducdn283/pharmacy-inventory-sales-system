package com.example.project.repository;

import com.example.project.entity.Returndetail;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface ReturndetailRepository extends JpaRepository<Returndetail, Integer> {

    /** Most recent return lines of one product, for the Product Detail history preview. */
    @Query("""
       select d
       from Returndetail d
       left join fetch d.returnID
       left join fetch d.batchID
       where d.productID.productID = :productId
       order by d.returnID.returnDate desc
       """)
    List<Returndetail> findRecentReturnsByProduct(@Param("productId") Integer productId,
                                                  Pageable pageable);

    /** All return lines with their product/unit/batch, for the list-screen item counts. */
    @Query("""
       select d
       from Returndetail d
       left join fetch d.productID
       left join fetch d.productUnitID
       left join fetch d.batchID
       """)
    List<Returndetail> findAllWithRelations();

    /** The lines of one return slip, fully loaded for the detail screen. */
    @Query("""
       select d
       from Returndetail d
       left join fetch d.productID
       left join fetch d.productUnitID
       left join fetch d.batchID
       where d.returnID.id = :returnId
       """)
    List<Returndetail> findByReturnIdWithRelations(@Param("returnId") Integer returnId);

    /**
     * Base units already returned to the supplier, per purchase-invoice detail line, across return
     * slips in the given status (used to derive a supplier return's per-line returnable quantity
     * without a {@code returnedQty} column on {@code purchasedetail}). Each row is
     * {@code [purchaseDetailID (Integer), totalReturnQty (Long)]}.
     */
    @Query("""
       select d.purchaseDetailID.id, coalesce(sum(d.returnQty), 0)
       from Returndetail d
       where d.purchaseDetailID is not null and d.returnID.status = :status
       group by d.purchaseDetailID.id
       """)
    List<Object[]> sumReturnedQtyByPurchaseDetail(@Param("status") String status);

    /**
     * The gap between what a supplier return line was originally worth and what the supplier
     * actually refunded — a real loss to the pharmacy (see {@code Tax-Invoice.xlsx}, sheet
     * "03_Cong_Thuc_TNCN"). Both {@code originalLineValue}/{@code lineRefund} are gross (VAT-
     * inclusive, no net/VAT split since 04/08/2026 — see {@code ReturnPurchaseService.Chunk}), so the
     * difference is the loss figure directly, with no further tax adjustment needed.
     */
    @Query("""
       select coalesce(sum(rd.originalLineValue - rd.lineRefund), 0)
       from Returndetail rd
       join rd.returnID r
       where r.purchaseID is not null
         and r.invoiceID is null
         and r.status = :approvedStatus
         and r.returnDate >= :from
         and r.returnDate < :to
       """)
    BigDecimal sumSupplierReturnShortfallInPeriod(
            @Param("approvedStatus") String approvedStatus,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * Phần tiền nhà thuốc GIỮ LẠI khi hoàn tiền cho khách ở tỷ lệ &lt;100%
     * ({@code Ho_so_nghiep_vu_v2.xlsx}, sheet "05_Tra_Hang": "Thu nhập phát sinh (thu nhập khác) =
     * X − totalRefund", với X là giá trị gốc 100% của phần hàng trả). Cùng công thức từng dòng
     * {@code ReturnService.retainedValueOf()} đã dùng để dựng dòng "tiền không kèm hàng" trên hóa đơn
     * thay thế — <strong>KHÔNG</strong> được suy ra bằng
     * {@code Invoice(gốc).total − Invoice(thay thế).total}: hiệu đó luôn đúng bằng
     * {@code Return.totalRefund} (vì {@code ReturnService.createReplacementInvoice()} định nghĩa
     * {@code newTotal = oldTotal − totalRefund}), không bao giờ ra đúng phần giữ lại — đã xác nhận
     * bằng ví dụ số cụ thể trước khi thêm hàm này.
     */
    @Query("""
       select coalesce(sum(rd.originalLineValue - rd.lineRefund), 0)
       from Returndetail rd
       join rd.returnID r
       where r.invoiceID is not null
         and r.purchaseID is null
         and r.status in (:debtStatus, :completedStatus)
         and r.returnDate >= :from
         and r.returnDate < :to
       """)
    BigDecimal sumCustomerReturnRetainedInPeriod(
            @Param("debtStatus") String debtStatus,
            @Param("completedStatus") String completedStatus,
            @Param("from") Instant from,
            @Param("to") Instant to);

}