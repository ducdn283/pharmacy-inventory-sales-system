package com.example.project.repository;

import com.example.project.entity.Producer;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Truy vấn dữ liệu nhà sản xuất ({@link Producer}) cho màn quản lý Owner.
 * <p>Danh sách và tìm kiếm hiện lọc trong {@link com.example.project.service.ProducerService}.</p>
 */
public interface ProducerRepository extends JpaRepository<Producer, Integer> {

}
