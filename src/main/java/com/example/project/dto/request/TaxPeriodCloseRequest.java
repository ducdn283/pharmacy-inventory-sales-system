package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * "Chốt kỳ thuế" — thao tác ghi duy nhất trên một kỳ thuế.
 *
 * <p>Số liệu thuế GTGT/TNCN không nằm trong form này — server tự tính lại từ chứng từ trong kỳ khi
 * chốt, không dùng số người dùng nhập. Nhóm áp dụng cho kỳ sau cũng được suy ra tự động. Người dùng
 * chỉ nhập số dư quỹ tham chiếu và ghi chú.</p>
 */
@Getter
@Setter
public class TaxPeriodCloseRequest {

    /** Kỳ mà form được render cho — so sánh với kỳ thực sự cần chốt để tránh chốt nhầm quý. */
    private String periodLabel;

    /** Chỉ mang tính tham chiếu, không dùng để tính thuế. */
    private BigDecimal cashBalanceAtPeriodEnd;

    private String note;
}
