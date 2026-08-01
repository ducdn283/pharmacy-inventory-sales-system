package com.example.project.repository;

import com.example.project.entity.Batch;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface BatchRepository extends JpaRepository<Batch, Integer> {

    /**
     * Current on-hand stock per product = SUM(storageQuantity) across all batches/branches.
     * Each row is {@code [productID (Integer), totalStock (Long)]}. Used for the Owner (all-branch) view.
     */
    @Query("""
           select b.productID.productID, coalesce(sum(b.storageQuantity), 0)
           from Batch b
           where b.productID is not null
           group by b.productID.productID
           """)
    List<Object[]> sumStorageGroupedByProduct();

    @Query("""
           select count(b) > 0
           from Batch b
           where b.purchaseDetailID.id = :purchaseDetailId
           """)
    boolean existsByPurchaseDetailId(@Param("purchaseDetailId") Integer purchaseDetailId);

    @Query("""
           select b
           from Batch b
           left join fetch b.purchaseDetailID
           left join fetch b.importUnitID
           where b.purchaseDetailID.id in :purchaseDetailIds
           """)
    List<Batch> findByPurchaseDetailIds(@Param("purchaseDetailIds") List<Integer> purchaseDetailIds);

    @Query("""
   select b
   from Batch b
   left join fetch b.productID
   left join fetch b.importUnitID
   where b.status = true
   and b.storageQuantity > 0
   order by b.expirationDate asc
   """)
    List<Batch> findAvailableBatchesForDestroy();
    /** Total on-hand stock of one product across all its batches (single store — no branch breakdown). */
    @Query("""
   select coalesce(sum(b.storageQuantity), 0)
   from Batch b
   where b.productID.productID = :productId
   """)
    long sumStorageByProduct(@Param("productId") Integer productId);

    /** In-stock (storageQuantity &gt; 0, active) batches of one product, soonest-expiry first. */
    @Query("""
   select b
   from Batch b
   left join fetch b.importUnitID
   where b.productID.productID = :productId
     and b.storageQuantity > 0
     and b.status = true
   order by b.expirationDate asc
   """)
    List<Batch> findInStockBatchesByProduct(@Param("productId") Integer productId);

    /** Most recent import (batch) events of one product, for the history preview. */
    @Query("""
   select b
   from Batch b
   where b.productID.productID = :productId
   order by b.importDate desc
   """)
    List<Batch> findRecentImportsByProduct(@Param("productId") Integer productId,
                                           Pageable pageable);

    /**
     * Every batch of one product regardless of remaining stock (unlike
     * {@link #findInStockBatchesByProduct}, which only returns {@code storageQuantity > 0}) — used
     * to let the "Nhập vào lô" screen suggest reusing an already-known lot/expiry, including ones
     * fully depleted.
     */
    @Query("""
   select b
   from Batch b
   where b.productID.productID = :productId
   order by b.expirationDate asc
   """)
    List<Batch> findAllByProduct(@Param("productId") Integer productId);

    /**
     * Distinct IDs of products carrying at least one in-stock batch expiring within
     * {@code [today, threshold]} — backs the Product List "Sắp hết hạn" filter.
     */
    @Query("""
   select distinct b.productID.productID
   from Batch b
   where b.status = true
     and b.storageQuantity > 0
     and b.expirationDate is not null
     and b.expirationDate between :today and :threshold
   """)
    List<Integer> findProductIdsNearExpiry(@Param("today") LocalDate today,
                                           @Param("threshold") LocalDate threshold);

    /**
     * Tổng tồn của từng sản phẩm,
     * chỉ tính các lô đang hoạt động.
     *
     * Mỗi Object[] gồm:
     * [0] = productID
     * [1] = tổng storageQuantity
     */
    @Query("""
       select b.productID.productID,
              coalesce(sum(b.storageQuantity), 0)
       from Batch b
       where b.productID is not null
         and b.status = true
       group by b.productID.productID
       """)
    List<Object[]> sumActiveStorageGroupedByProduct();

    /**
     * Lấy toàn bộ lô cùng thông tin sản phẩm
     * để kiểm tra hạn sử dụng.
     */
    @Query("""
       select b
       from Batch b
       left join fetch b.productID p
       where b.productID is not null
       order by b.expirationDate asc, b.id asc
       """)
    List<Batch> findAllWithProductForNotifications();
}