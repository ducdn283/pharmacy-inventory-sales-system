package com.example.project.repository;

import com.example.project.entity.Stockadjustmentdetail;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface StockadjustmentdetailRepository extends JpaRepository<Stockadjustmentdetail, Integer> {

    @Query("""
           select d
           from Stockadjustmentdetail d
           left join fetch d.stockAdjustmentID
           left join fetch d.productID
           left join fetch d.productUnitID
           left join fetch d.batchID
           where d.stockAdjustmentID.id = :stockOutId
           order by d.id asc
           """)
    List<Stockadjustmentdetail> findByStockOutIdWithRelations(Integer stockOutId);

    /**
     * Dòng phiếu điều chỉnh kho gần nhất của một sản phẩm, cho khối "Lịch sử tồn kho gần đây" ở màn chi
     * tiết sản phẩm. <b>Chỉ những dòng THẬT SỰ làm đổi tồn kho.</b>
     *
     * <p>Hai điều kiện lọc thêm ngày 11/08/2026 — trước đó câu này lấy MỌI dòng nên bảng lịch sử ghi
     * nhận cả những biến động chưa từng xảy ra:</p>
     * <ul>
     *   <li>{@code status = 'Hoàn thành'} (viết thẳng chuỗi vì JPQL không tham chiếu được hằng
     *       {@code StockAdjustmentStatus.COMPLETED}) — {@code Nháp} chưa hề áp vào tồn, {@code Đã hủy} đã được
     *       {@code StockadjustmentService.cancel} đảo ngược đúng phần đã cộng/trừ. Để chúng trong lịch
     *       sử là cộng dồn ra một con số không bao giờ khớp {@code batch.storageQuantity}.</li>
     *   <li>{@code direction <> 'NONE'} — dòng của phiếu {@code DATE_ADJUSTMENT} chỉ sửa
     *       {@code batch.expirationDate}, {@code baseQtyDeducted} luôn bằng 0 nên nó hiện thành một
     *       dòng biến động "0" vô nghĩa giữa bảng.</li>
     * </ul>
     *
     * <p>Phía tiêu thụ ({@code ProductService.loadRecentHistory}) đã được sửa cùng ngày để khớp: dấu
     * và nhãn "Nhập kho -"/"Xuất kho -" giờ theo đúng {@code direction} của từng dòng thay vì luôn trừ,
     * và mã tham chiếu hiển thị đúng {@code stockAdjustmentCode} thật ({@code PDC-...}) thay vì tiền tố
     * {@code SO-} tự dựng.</p>
     */
    @Query("""
       select d
       from Stockadjustmentdetail d
       left join fetch d.stockAdjustmentID
       left join fetch d.batchID
       where d.productID.productID = :productId
         and d.stockAdjustmentID.status = 'Hoàn thành'
         and d.direction <> 'NONE'
       order by d.stockAdjustmentID.date desc
       """)
    List<Stockadjustmentdetail> findRecentStockOutsByProduct(@Param("productId") Integer productId,
                                                             Pageable pageable);

    @Query("""
           select d
           from Stockadjustmentdetail d
           left join fetch d.stockAdjustmentID
           left join fetch d.productID
           left join fetch d.productUnitID
           left join fetch d.batchID
           """)
    List<Stockadjustmentdetail> findAllWithRelations();

    /**
     * Giá trị (theo GIÁ BÁN) của hàng xuất đi mà không qua bán hàng — {@code GIFT}/{@code SAMPLE}/
     * {@code INTERNAL_USE}, xem {@code StockadjustmentService.REF_SELL_PRICE_TYPES} — trong
     * {@code [from, to)}, feeding tax-period revenue (mục C.2). Chỉ phiếu đã
     * {@code StockAdjustmentStatus.COMPLETED} mới thật sự xảy ra; phiếu nháp hoặc đã hủy không phải
     * doanh thu.
     *
     * <p><b>04/08/2026 — đổi nguồn tính:</b> trước đây cộng {@code preTaxAmount + vatAmount} để dựng
     * lại giá trị gộp. Hai cột đó đã bị BỎ khỏi bảng (hộ kinh doanh không khấu trừ GTGT nên phiếu điều
     * chỉnh kho không còn tách net/thuế), nên câu truy vấn cũ khiến ứng dụng không khởi động được.
     * Nay tính thẳng {@code refSellPrice × quantity} — đúng bằng con số mà hai cột kia từng cộng lại
     * thành, và {@code refSellPrice} vẫn được snapshot y như trước cho đúng 3 loại phiếu này.</p>
     */
    @Query("""
           select coalesce(sum(d.refSellPrice * d.quantity), 0)
           from Stockadjustmentdetail d
           where d.stockAdjustmentID.adjustmentType in :adjustmentTypes
             and d.stockAdjustmentID.status = :status
             and d.stockAdjustmentID.date >= :from
             and d.stockAdjustmentID.date < :to
             and d.refSellPrice is not null
           """)
    BigDecimal sumGrossValueInPeriod(@Param("adjustmentTypes") Collection<String> adjustmentTypes,
                                     @Param("status") String status,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to);

    /**
     * Cost of surplus stock-review lines whose batch was created as unknown-origin, in {@code [from, to)}
     * — feeds the taxable-income-only revenue add-on (mục D.2).
     *
     * <p>Nhận CẢ loại phiếu {@code COUNT} (bản gộp, từ 04/08/2026) lẫn {@code COUNT_INCREASE} (dữ liệu
     * cũ trước khi gộp). Vì phiếu {@code COUNT} chứa cả dòng thừa lẫn dòng thiếu nên phải lọc thêm
     * {@code direction = 'IN'} — không có điều kiện này thì dòng THIẾU cũng bị cộng vào thu nhập.</p>
     *
     * <p>No dedicated flag exists on {@code Stockadjustmentdetail}/{@code Batch} for "unknown
     * origin", so this relies on the one structural signal {@code StockadjustmentService
     * .createSurplusBatch} leaves behind: the batch code is prefixed {@code "KK-"} <strong>only</strong>
     * when a new batch was created for unknown-origin surplus (a known-origin surplus line reuses the
     * counted batch as-is, never creating one), and {@code purchaseDetailID} is
     * always {@code null} on it (no real purchase behind it). Verified 2026-08 that no other flow in
     * the codebase produces a {@code "KK-"}-prefixed batch code ({@code PurchaseinvoiceService} uses
     * {@code "BATCH-"}, {@code ReturnService.cloneReturnBatch} uses {@code "RT-"}).</p>
     */
    @Query("""
           select coalesce(sum(d.lineCost), 0)
           from Stockadjustmentdetail d
           join d.batchID b
           where d.stockAdjustmentID.adjustmentType in ('COUNT', 'COUNT_INCREASE')
             and d.direction = 'IN'
             and d.stockAdjustmentID.status = :status
             and d.stockAdjustmentID.date >= :from
             and d.stockAdjustmentID.date < :to
             and b.batchCode like 'KK-%'
             and b.purchaseDetailID is null
           """)
    BigDecimal sumUnknownOriginIncreaseCostInPeriod(@Param("status") String status,
                                                     @Param("from") Instant from,
                                                     @Param("to") Instant to);
}