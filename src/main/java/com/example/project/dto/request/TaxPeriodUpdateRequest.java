package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Điều chỉnh kỳ thuế mới nhất đã chốt — dùng khi chốt sai số, thay vì hủy chốt (xóa snapshot).
 *
 * <p>Đây là điều chỉnh một bản khai đã nộp, không phải tính lại — các số GTGT/TNCN được lưu đúng như
 * người dùng nhập. Muốn xem sổ sách hiện tại ra số bao nhiêu thì dùng màn xem trước.</p>
 *
 * <p>{@code vatCarryforwardOut} không có trong form — được suy ra từ 3 trường bên dưới để "còn phải
 * nộp" và "chuyển kỳ sau" luôn là hai vế của cùng một phép trừ.</p>
 */
@Getter
@Setter
public class TaxPeriodUpdateRequest {

    private BigDecimal vatOutput;

    private BigDecimal vatInput;

    private BigDecimal vatCarryforwardIn;

    /** Thuế TNCN của kỳ. */
    private BigDecimal incomeTax;

    /** Chỉ mang tính tham chiếu, không dùng để tính thuế. */
    private BigDecimal cashBalanceAtPeriodEnd;

    private String note;
}
