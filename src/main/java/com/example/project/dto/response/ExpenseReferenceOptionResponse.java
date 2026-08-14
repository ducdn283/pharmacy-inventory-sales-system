package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Một chứng từ tham chiếu chọn được trên màn tạo phiếu chi (phiếu trả hàng của khách chờ hoàn
 * tiền, hoặc phiếu nhập chờ thanh toán). Toàn bộ field là kiểu vô hướng và {@code dateDisplay} đã
 * được định dạng sẵn, nên an toàn khi nhúng vào block {@code th:inline}.
 */
@Getter
@AllArgsConstructor
public class ExpenseReferenceOptionResponse {

    private Integer id;
    private String code;
    private String dateDisplay;

    /** Phần còn có thể chi/nhận cho chứng từ này — không phải tổng nghĩa vụ ban đầu. */
    private BigDecimal amount;

    private String detail;
}
