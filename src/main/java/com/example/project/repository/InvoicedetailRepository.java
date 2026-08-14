package com.example.project.repository;

import com.example.project.entity.Invoicedetail;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Truy vấn chi tiết dòng hàng trên hóa đơn bán ({@link Invoicedetail}).
 */
public interface InvoicedetailRepository extends JpaRepository<Invoicedetail, Integer> {

    /** Các dòng bán gần nhất của một sản phẩm — lịch sử trên màn chi tiết hàng hóa. */
    @Query("""
       select d
       from Invoicedetail d
       left join fetch d.invoiceID
       left join fetch d.batchID
       where d.productID.productID = :productId
         and (d.invoiceID.invoiceType is null or d.invoiceID.invoiceType <> 'Thay thế')
       order by d.invoiceID.date desc
       """)
    List<Invoicedetail> findRecentSalesByProduct(@Param("productId") Integer productId,
                                                 Pageable pageable);

    /** Các dòng hàng kèm sản phẩm, đơn vị bán và lô — chi tiết/in hóa đơn. */
    @Query("""
       select d
       from Invoicedetail d
       left join fetch d.productID
       left join fetch d.productUnitID
       left join fetch d.batchID b
       left join fetch b.importUnitID
       where d.invoiceID.id = :invoiceId
       order by d.id
       """)
    List<Invoicedetail> findByInvoiceIdWithRelations(@Param("invoiceId") Integer invoiceId);

    /** Tổng số lượng bán và đã trả theo từng hóa đơn — badge trạng thái trả hàng trên danh sách. */
    @Query("""
       select d.invoiceID.id, sum(d.quantity), sum(coalesce(d.returnedQty, 0))
       from Invoicedetail d
       group by d.invoiceID.id
       """)
    List<Object[]> sumQuantitiesGroupedByInvoice();

    /**
     * Số hóa đơn bán ra khỏi lô {@code batchId} SAU mốc {@code after}. Dùng để chặn việc hủy (đảo
     * ngược) một phiếu điều chỉnh kho khi lô đã bị giao dịch khác động vào kể từ lúc phiếu đó được
     * áp dụng — {@code Dac_ta_Income_StockAdjustment.xlsx} sheet 05.
     *
     * <p>{@code Invoice.date} là giờ tường VN dạng {@code LocalDateTime}, nên bên gọi phải quy mốc
     * so sánh về cùng múi giờ trước khi truyền vào.</p>
     */
    @Query("""
       select count(d)
       from Invoicedetail d
       where d.batchID.id = :batchId
         and d.invoiceID.date > :after
       """)
    long countSalesFromBatchAfter(@Param("batchId") Integer batchId,
                                  @Param("after") LocalDateTime after);

    /**
     * Giá vốn hàng bán trong {@code [from, to)}, tính theo giá nhập thực tế từng lô:
     * {@code baseQtyDeducted × Batch.importPricePerBase}. Phục vụ thuế TNCN theo lợi nhuận
     * (nhóm 3 luôn; nhóm 2 khi chọn phương pháp lợi nhuận).
     *
     * <p>Mốc thời gian là {@code LocalDateTime} giờ tường VN vì lọc theo {@code Invoice.date} —
     * xem helper biên trong {@code TaxperiodsnapshotService}.</p>
     *
     * <p>Chỉ cộng dòng thuộc hóa đơn <strong>còn hiệu lực</strong> — cùng điều kiện với
     * {@code InvoiceRepository.findValidInPeriod}, lặp lại ở đây (JPQL không chia sẻ fragment)
     * vì cần join xuống {@code Invoicedetail}/{@code Batch} gom một query thay vì duyệt dòng trong
     * Java. Không lọc sẽ cộng trùng dòng hóa đơn gốc và hóa đơn thay thế, kép giá vốn giống lỗi
     * cộng trùng doanh thu trước đây.</p>
     *
     * <p><strong>Sửa 2026-08-06 — đồng bộ với {@code InvoiceRepository.findValidInPeriod}:</strong>
     * bỏ nhánh {@code invoiceType = 'Điều chỉnh'} (không còn code ghi giá trị này) và bỏ điều kiện
     * {@code status <> 'Đã ký'} khi loại hóa đơn trả toàn bộ không tạo thay thế (di sản tách
     * ký/chưa ký trước 04/08/2026; {@code ReturnService} giờ mọi phiếu trả đều qua
     * {@code createReplacementInvoice()}). Trước sửa, hóa đơn gốc đã ký, trả toàn bộ không có thay
     * thế vẫn bị tính còn hiệu lực, cộng trùng giá vốn đã vô hiệu.</p>
     */
    @Query("""
       select coalesce(sum(d.baseQtyDeducted * b.importPricePerBase), 0)
       from Invoicedetail d
       join d.batchID b
       join d.invoiceID i
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
       """)
    BigDecimal sumCostOfGoodsSoldInPeriod(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);
}
