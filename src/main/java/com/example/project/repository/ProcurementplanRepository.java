package com.example.project.repository;

import com.example.project.entity.Procurementplan;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Truy vấn dữ liệu phiếu dự trù mua hàng ({@link Procurementplan}).
 */
public interface ProcurementplanRepository extends JpaRepository<Procurementplan, Integer> {

    /** Đếm số phiếu dự trù theo trạng thái — thẻ thống kê màn danh sách. */
    long countByStatus(String status);
}
