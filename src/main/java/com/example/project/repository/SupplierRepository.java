package com.example.project.repository;

import com.example.project.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Integer> {

    boolean existsByPhone(String phone);

    boolean existsByPhoneAndIdNot(String phone, Integer id);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Integer id);

    /**
     * Mã số thuế phải là DUY NHẤT — nó là căn cứ đối chiếu hóa đơn GTGT đầu vào với cơ quan thuế,
     * hai nhà cung cấp cùng MST thì không phân định được hóa đơn thuộc về ai. Cột này có UNIQUE index
     * ở DB; hàm này để service hỏi trước và cho câu thông báo gắn đúng ô nhập.
     */
    boolean existsByTaxCode(String taxCode);

    boolean existsByTaxCodeAndIdNot(String taxCode, Integer id);
}
