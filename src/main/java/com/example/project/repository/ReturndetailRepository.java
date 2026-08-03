package com.example.project.repository;

import com.example.project.entity.Returndetail;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

}