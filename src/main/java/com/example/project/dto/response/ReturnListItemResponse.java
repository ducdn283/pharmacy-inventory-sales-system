package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/** One row in the customer-return list. */
@Getter
@AllArgsConstructor
public class ReturnListItemResponse {

    private Integer id;
    private String code;

    private Instant date;
    private String dateDisplay;

    private String invoiceCode;
    private String customerName;

    private String createdByName;

    private long totalItems;

    /** Tiền hoàn ĐÃ áp tỷ lệ hoàn (Σ lineRefund), không phải giá trị gốc 100%. */
    private BigDecimal totalRefund;

    /** Phần tiền hoàn đã cấn trừ vào công nợ của chính hóa đơn gốc; chốt lúc duyệt. */
    private BigDecimal offsetDebtAmount;

    /** {@code totalRefund − offsetDebtAmount} — tiền thật còn phải trả khách, phần phiếu chi chi ra. */
    private BigDecimal cashRefundDue;

    private String returnType;
    private String returnTypeDisplay;

    private String statusName;
    private String statusCssClass;

    /**
     * Phiếu đã được duyệt hay chưa ("Nợ" hoặc "Hoàn thành"). Hai cột bù trừ chỉ có số thật khi cờ này
     * bật — trước lúc duyệt chúng mới là ước tính.
     *
     * <p>Có cờ riêng để template khỏi phải so chuỗi tiếng Việt: gate cũ viết
     * {@code statusName != 'Nợ'} nên khi thêm trạng thái "Hoàn thành" thì hai cột tiền của phiếu đã
     * tất toán lập tức biến thành "—".</p>
     */
    private boolean approved;

    /**
     * Phiếu đã tất toán, nhà thuốc không còn nợ khách đồng nào ("Hoàn thành").
     *
     * <p>Cần tách khỏi {@link #cashRefundDue}: cột đó là số tiền phiếu chi <em>phải</em> chi ra và
     * KHÔNG giảm dần khi chi, nên một phiếu đã chi xong vẫn mang số dương. Hiện thẳng số đó dưới tiêu
     * đề "Còn phải trả khách" thì đọc thành vẫn đang nợ khách.</p>
     */
    private boolean settled;
}
