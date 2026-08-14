package com.example.project.repository;

import com.example.project.entity.Procurementplandetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Truy vấn chi tiết dòng sản phẩm trên phiếu dự trù ({@link Procurementplandetail}).
 */
public interface ProcurementplandetailRepository extends JpaRepository<Procurementplandetail, Integer> {

    /** Chi tiết theo id phiếu dự trù — form cập nhật. */
    List<Procurementplandetail> findByProcurementID_Id(Integer procurementId);

    /** Chi tiết kèm hàng hóa và nhà cung cấp — màn in phiếu dự trù. */
    @Query("""
            SELECT d FROM Procurementplandetail d
            JOIN FETCH d.productID
            LEFT JOIN FETCH d.supplierID
            WHERE d.procurementID.id = :procurementId
            """)
    List<Procurementplandetail> findByProcurementID_IdWithRelations(@Param("procurementId") Integer procurementId);

    /** Xóa toàn bộ dòng chi tiết khi cập nhật hoặc xóa phiếu dự trù. */
    void deleteByProcurementID_Id(Integer procurementId);
}
