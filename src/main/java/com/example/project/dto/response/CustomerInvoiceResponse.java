package com.example.project.dto.response;

import com.example.project.entity.Invoice;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * Dòng lịch sử mua hàng hiển thị trên màn hình chi tiết khách hàng.
 * Chỉ phục vụ đọc — suy ra từ {@link Invoice} có FK tới khách hàng.
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerInvoiceResponse {

    /** Invoice.date lưu giờ tường VN dạng LocalDateTime — format trực tiếp, không chuyển múi giờ. */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Id hóa đơn. */
    private Integer id;
    /** Mã hóa đơn hiển thị — số hóa đơn, đúng cột "Mã hóa đơn" của màn Danh sách hóa đơn. */
    private String invoiceCode;
    /** Ngày giờ lập hóa đơn hiển thị. */
    private String date;
    /** Tổng tiền hóa đơn. */
    private BigDecimal total;
    /** Đã thanh toán = tiền mặt + chuyển khoản. */
    private BigDecimal paid;
    /** Còn nợ trên chính hóa đơn này. */
    private BigDecimal debtAmount;
    /** Trạng thái hóa đơn (Hoàn thành, Còn nợ, …) — lấy nguyên chuỗi đang lưu. */
    private String status;

    /** Ánh xạ {@link Invoice} sang dòng lịch sử mua hàng. */
    public static CustomerInvoiceResponse from(Invoice invoice) {
        CustomerInvoiceResponse r = new CustomerInvoiceResponse();
        r.id = invoice.getId();
        r.invoiceCode = invoiceCode(invoice);
        r.date = invoice.getDate() == null ? "—" : DATE_FMT.format(invoice.getDate());
        r.total = invoice.getTotal();
        r.paid = safe(invoice.getPaidByCash()).add(safe(invoice.getPaidByBanking()));
        r.debtAmount = safe(invoice.getDebtAmount());
        r.status = invoice.getStatus();
        return r;
    }

    /** Còn nợ hay không — quyết định có hiện cặp cột "Đã trả / Còn nợ" cho dòng này không. */
    public boolean isHasDebt() {
        return debtAmount != null && debtAmount.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Ưu tiên số hóa đơn thật để người dùng đối chiếu được với màn Danh sách hóa đơn; hóa đơn cũ
     * chưa có số thì lùi về mã suy từ id để dòng vẫn có một định danh nhìn thấy được.
     */
    private static String invoiceCode(Invoice invoice) {
        String number = invoice.getInvoiceNumber() == null ? "" : invoice.getInvoiceNumber().trim();
        return number.isEmpty() ? "HD-" + String.format("%06d", invoice.getId()) : number;
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
