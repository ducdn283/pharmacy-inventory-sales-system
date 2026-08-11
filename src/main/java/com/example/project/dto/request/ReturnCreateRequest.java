package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Form backing the "Create customer return" screen. The user picks one completed sale invoice, then
 * chooses how many units of each of its lines to return, plus a reason.
 *
 * <p>No money is posted from here: the refund is computed server-side from the
 * original invoice-line sell prices and only recorded on the slip. How the customer is actually paid
 * back belongs to the Expense module.</p>
 */
@Getter
@Setter
public class ReturnCreateRequest {

    /** The original sale invoice being returned against. */
    private Integer invoiceId;

    /** Reason for the return (required). */
    private String reason;

    /** Optional free-text note. */
    private String note;

    /**
     * Tỷ lệ % giá trị hàng trả được hoàn cho khách — <b>SỐ NGUYÊN</b> {@code 0 < rate ≤ 100}, áp cho MỌI
     * dòng của phiếu. Bỏ trống ⇒ lấy mặc định {@code Financialsetting.returnProductOnInvoiceValueRate}.
     * Người lập được chỉnh tay từng phiếu (đặc tả bổ sung 27/07 mục 1.1); muốn mỗi mặt hàng một tỷ lệ
     * khác nhau thì phải lập nhiều phiếu (giới hạn ghi ở mục 1.4).
     *
     * <p>Kiểu vẫn là {@code BigDecimal} chứ không phải {@code Integer}: gõ số lẻ vào ô {@code Integer}
     * làm Spring ném lỗi bind ⇒ người dùng nhận trang lỗi 400 thay vì câu tiếng Việt ngay dưới ô. Việc
     * chặn số lẻ nằm ở {@code ReturnService.resolveRefundRate}.</p>
     */
    private BigDecimal refundRate;

    /** One entry per candidate invoice line; blank/zero rows are dropped server-side. */
    private List<ReturnLineRequest> items = new ArrayList<>();
}
