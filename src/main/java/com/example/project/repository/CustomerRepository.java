package com.example.project.repository;

import com.example.project.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Integer> {

    /**
     * Bảng {@code customer} có UNIQUE index trên {@code phoneNumber} và {@code taxCode} (kiểm bằng
     * {@code information_schema.STATISTICS} ngày 30/07/2026) — đó là lưới chặn thật. Service vẫn hỏi
     * trước bằng các hàm này để cho được câu thông báo gắn đúng ô nhập, thay vì để người dùng nhận
     * một câu lỗi kỹ thuật chung của DB.
     *
     * <p>Biến thể {@code ...AndIdNot} dành cho màn sửa: bản ghi đang sửa luôn "trùng với chính nó".</p>
     */
    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumberAndIdNot(String phoneNumber, Integer id);

    /** {@code taxCode} = MST với khách doanh nghiệp, số CCCD/CMND với khách cá nhân. */
    boolean existsByTaxCode(String taxCode);

    boolean existsByTaxCodeAndIdNot(String taxCode, Integer id);
}