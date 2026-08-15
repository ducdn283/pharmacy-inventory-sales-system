package com.example.project.repository;

import com.example.project.entity.Supplierproduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SupplierproductRepository extends JpaRepository<Supplierproduct, Integer> {

    long countBySupplierID_Id(Integer supplierId);

    List<Supplierproduct> findBySupplierID_Id(Integer supplierId);

    Optional<Supplierproduct> findBySupplierID_IdAndProductID_ProductID(Integer supplierId, Integer productId);

    /** Mọi nhà cung cấp đang gắn với một sản phẩm — dùng để giữ "mỗi sản phẩm chỉ MỘT NCC ưu tiên". */
    List<Supplierproduct> findByProductID_ProductID(Integer productId);

    List<Supplierproduct> findByIsPreferredTrue();

    @Query("SELECT COUNT(DISTINCT sp.supplierID.id) FROM Supplierproduct sp")
    long countDistinctSuppliers();
}
