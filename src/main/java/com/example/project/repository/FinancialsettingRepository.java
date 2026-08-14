package com.example.project.repository;

import com.example.project.entity.Financialsetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FinancialsettingRepository extends JpaRepository<Financialsetting, Integer> {
    // Lấy bản ghi cấu hình tài chính duy nhất của hệ thống (id nhỏ nhất, thực chất chỉ có 1 dòng).
    Optional<Financialsetting> findFirstByOrderByIdAsc();
}