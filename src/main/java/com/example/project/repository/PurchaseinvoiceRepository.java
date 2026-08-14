package com.example.project.repository;

import com.example.project.entity.Purchaseinvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PurchaseinvoiceRepository extends JpaRepository<Purchaseinvoice, Integer> {

    // Lấy toàn bộ phiếu nhập kèm nhà cung cấp/nhân viên/dự trù mua hàng, mới nhất trước — dùng cho màn danh sách.
    @Query("""
           select p
           from Purchaseinvoice p
           left join fetch p.supplierID
           left join fetch p.employeeID
           left join fetch p.procurementID
           order by p.date desc
           """)
    List<Purchaseinvoice> findAllWithRelations();

    // Lấy 1 phiếu nhập theo id kèm đầy đủ quan hệ — dùng cho màn chi tiết/in phiếu.
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
     * Lấy phiếu nhập trong khoảng [from, to) để tổng hợp thuế. date là Instant thật nên cận là
     * thời điểm quy đổi từ giờ VN. Không lọc điều kiện khấu trừ ở đây — việc đó tính lại ở Java
     * (PurchaseinvoiceService.isDeductible), không tin cột isValidForDeduction lưu sẵn.
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