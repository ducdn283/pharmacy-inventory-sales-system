package com.example.project.repository;

import com.example.project.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Truy vấn dữ liệu hóa đơn bán hàng ({@link Invoice}).
 */
public interface InvoiceRepository extends JpaRepository<Invoice, Integer> {

    /** Danh sách hóa đơn kèm nhân viên bán và khách hàng — màn danh sách. */
    @Query("""
       select i
       from Invoice i
       left join fetch i.employeeID
       left join fetch i.customerID
       """)
    List<Invoice> findAllWithRelations();

    /** Một hóa đơn kèm nhân viên, khách hàng và hóa đơn gốc (nếu là hóa đơn thay thế) — chi tiết/in. */
    @Query("""
       select i
       from Invoice i
       left join fetch i.employeeID
       left join fetch i.customerID
       left join fetch i.originalInvoiceID
       where i.id = :invoiceId
       """)
    Optional<Invoice> findByIdWithRelations(@Param("invoiceId") Integer invoiceId);

    /**
     * Hóa đơn bán trong khoảng nửa mở {@code [from, to)} còn <strong>hiệu lực</strong> để cộng
     * doanh thu/thuế — sửa lỗi cộng trùng khi vừa có hóa đơn gốc bị thay thế vừa có hóa đơn thay thế.
     * {@code Invoice.date} là {@code LocalDateTime} giờ tường VN (ghi bởi {@code InvoiceService} qua
     * {@code LocalDateTime.now(VN_ZONE)}), nên bên gọi phải truyền mốc giờ VN — xem helper biên
     * trong {@code TaxperiodsnapshotService}. Biên trên loại trừ để giao dịch lúc 23:59 ngày cuối
     * quý vẫn nằm trong kỳ.
     *
     * <p>Hóa đơn được tính khi:
     * <ul>
     *   <li>là hóa đơn <strong>"Thay thế"</strong> — luôn tính vì đã mang số tiền ròng sau trả hàng
     *       ({@code total}); hoặc</li>
     *   <li>là bán hàng thường ("Bán hàng"/legacy "normal"/null) <strong>chưa</strong> bị hóa đơn
     *       "Thay thế" con thay thế ({@code ReturnService.isInvalidatedByReplacement}, thực hiện bằng
     *       query thay vì nạp toàn bộ hóa đơn vào bộ nhớ), <strong>và</strong> không thuộc trường hợp
     *       đặc biệt: trả toàn bộ ({@code returnStatus = 'FULL'}) với {@code appliedRefundRate = 100%}
     *       khiến không còn dòng hàng, {@code ReturnService.createReplacementInvoice} thoát sớm và không
     *       tạo hóa đơn con — khi đó phải coi hóa đơn gốc là vô hiệu thủ công.</li>
     * </ul>
     *
     * <p><strong>Sửa 2026-08-06:</strong> loại trừ "không tạo hóa đơn con" trước đây chỉ áp dụng hóa
     * đơn gốc <em>chưa ký</em> ({@code status <> 'Đã ký'}) — di sản tách TH1/TH2 trước 04/08/2026,
     * khi hóa đơn gốc <em>đã ký</em> với {@code returnStatus = 'FULL'} vẫn được giữ vì cho rằng dòng
     * "Điều chỉnh" đã bù về 0. Hai giả định đó không còn: {@code ReturnService} không phân nhánh
     * ký/chưa ký nữa, và không còn code ghi {@code invoiceType = "Điều chỉnh"}. Hóa đơn gốc đã ký,
     * trả toàn bộ nhưng không có hóa đơn thay thế từng bị tính nhầm còn hiệu lực, cộng trùng doanh
     * thu/giá vốn đã vô hiệu. Loại trừ giờ áp dụng mọi hóa đơn gốc bất kể trạng thái ký; nhánh
     * "Điều chỉnh" đã bỏ — giá trị đó chỉ còn trong dữ liệu cũ.</p>
     *
     * <p><strong>Hạn chế đã biết:</strong> chưa xử lý riêng loại legacy "Hóa đơn GTGT" (nhóm 3 trước
     * khi chuyển phương pháp trực tiếp) — dòng đó được coi như "Bán hàng" thường. Hóa đơn GTGT cũ
     * khi tính lại kỳ sẽ hành xử giống "Bán hàng", chưa được mô tả rõ trong đặc tả.</p>
     */
    @Query("""
       select i
       from Invoice i
       where i.date >= :from
         and i.date < :to
         and (
           i.invoiceType = 'Thay thế'
           or (
             (i.invoiceType is null or i.invoiceType = 'Bán hàng' or i.invoiceType = 'normal')
             and not exists (
               select 1 from Invoice r
               where r.invoiceType = 'Thay thế'
                 and r.originalInvoiceID = i
             )
             and (i.returnStatus is null or i.returnStatus <> 'FULL')
           )
         )
       order by i.date asc
       """)
    List<Invoice> findValidInPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
