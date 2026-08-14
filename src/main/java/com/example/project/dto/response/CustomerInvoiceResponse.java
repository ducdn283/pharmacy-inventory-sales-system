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
    /** Mã hóa đơn hiển thị — dạng {@code HD-xxxxxx}. */
    private String invoiceCode;
    /** Ngày giờ lập hóa đơn hiển thị. */
    private String date;
    /** Tổng tiền hóa đơn. */
    private BigDecimal total;

    /** Ánh xạ {@link Invoice} sang dòng lịch sử mua hàng. */
    public static CustomerInvoiceResponse from(Invoice invoice) {
        CustomerInvoiceResponse r = new CustomerInvoiceResponse();
        r.id = invoice.getId();
        r.invoiceCode = "HD-" + String.format("%06d", invoice.getId());
        r.date = invoice.getDate() == null ? "—" : DATE_FMT.format(invoice.getDate());
        r.total = invoice.getTotal();
        return r;
    }
}
