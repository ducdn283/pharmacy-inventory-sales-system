package com.example.project.repository;

import com.example.project.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Integer> {

    /**
     * Trùng lặp phải chặn ở TẦNG SERVICE: bảng {@code customer} không có ràng buộc UNIQUE nào ngoài
     * khoá chính (kiểm bằng {@code information_schema.STATISTICS} ngày 28/07/2026), nên DB sẽ nhận
     * bừa hai khách cùng số điện thoại / cùng CCCD nếu service không hỏi trước.
     *
     * <p>Biến thể {@code ...AndIdNot} dành cho màn sửa: bản ghi đang sửa luôn "trùng với chính nó".</p>
     */
    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumberAndIdNot(String phoneNumber, Integer id);

    /** {@code taxCode} = MST với khách doanh nghiệp, số CCCD/CMND với khách cá nhân. */
    boolean existsByTaxCode(String taxCode);

    boolean existsByTaxCodeAndIdNot(String taxCode, Integer id);
}