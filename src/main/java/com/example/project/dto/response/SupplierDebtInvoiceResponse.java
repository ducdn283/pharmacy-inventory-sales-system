package com.example.project.dto.response;

import com.example.project.entity.Purchaseinvoice;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Một dòng công nợ phải trả nhà cung cấp trên màn chi tiết NCC — mỗi dòng là một phiếu nhập còn nợ.
 * Chỉ phục vụ đọc.
 */
@Getter
@Setter
@NoArgsConstructor
public class SupplierDebtInvoiceResponse {

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Id phiếu nhập — dựng link sang màn chi tiết phiếu nhập. */
    private Integer id;
    /** Mã phiếu nhập hiển thị. */
    private String code;
    /** Ngày lập phiếu nhập hiển thị. */
    private String date;
    /** Tổng tiền phiếu nhập. */
    private BigDecimal totalAmount;
    /** Đã thanh toán cho nhà cung cấp. */
    private BigDecimal paid;
    /** Còn phải trả. */
    private BigDecimal remaining;
    /** Trạng thái phiếu nhập (Nợ, Nợ một phần, Nháp, …). */
    private String status;
    /** Hạn thanh toán hiển thị, rỗng nếu phiếu không đặt hạn. */
    private String dueDate;
    /** Quá hạn thanh toán mà vẫn còn nợ. */
    private boolean overdue;

    /**
     * @param remaining số còn nợ do {@code PurchaseinvoiceService.remainingDebt} tính — truyền vào
     *                  thay vì tự tính lại, để màn này và màn Công nợ không bao giờ ra hai con số
     *                  khác nhau cho cùng một phiếu.
     */
    public static SupplierDebtInvoiceResponse from(Purchaseinvoice invoice, BigDecimal remaining) {
        SupplierDebtInvoiceResponse r = new SupplierDebtInvoiceResponse();
        r.id = invoice.getId();
        r.code = invoice.getPurchaseInvoiceCode();
        // date là Instant thật (không phải giờ VN gắn nhãn UTC) nên đổi về múi giờ máy chủ như các
        // màn phiếu nhập khác.
        r.date = invoice.getDate() == null ? "—"
                : DATE_TIME_FMT.format(invoice.getDate().atZone(ZoneId.systemDefault()));
        r.totalAmount = invoice.getTotalAmount();
        r.paid = invoice.getPaid();
        r.remaining = remaining;
        r.status = invoice.getStatus();
        r.dueDate = invoice.getDueDate() == null ? null : DATE_FMT.format(invoice.getDueDate());
        r.overdue = invoice.getDueDate() != null && invoice.getDueDate().isBefore(LocalDate.now());
        return r;
    }
}
