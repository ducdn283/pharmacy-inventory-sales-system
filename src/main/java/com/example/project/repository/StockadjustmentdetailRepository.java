package com.example.project.repository;

import com.example.project.entity.Stockadjustmentdetail;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
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
     * Cost of surplus stock-review lines whose batch was created as unknown-origin, in {@code [from, to)}
     * — feeds the taxable-income-only revenue add-on (mục D.2).
     *
     * <p>Nhận CẢ loại phiếu {@code COUNT} (bản gộp, từ 04/08/2026) lẫn {@code COUNT_INCREASE} (dữ liệu
     * cũ trước khi gộp). Vì phiếu {@code COUNT} chứa cả dòng thừa lẫn dòng thiếu nên phải lọc thêm
     * {@code direction = 'IN'} — không có điều kiện này thì dòng THIẾU cũng bị cộng vào thu nhập.</p>
     *
     * <p>No dedicated flag exists on {@code Stockadjustmentdetail}/{@code Batch} for "unknown
     * origin", so this relies on the one structural signal {@code StockadjustmentService
     * .createSurplusBatch} leaves behind: the batch code is prefixed {@code "RS-"} <strong>only</strong>
     * when a new batch was created for unknown-origin surplus (a known-origin surplus line reuses the
     * counted batch as-is, never creating one), and {@code purchaseDetailID} is
     * always {@code null} on it (no real purchase behind it). Verified 2026-08 that no other flow in
     * the codebase produces such a prefix ({@code PurchaseinvoiceService} uses
     * {@code "BATCH-"}, {@code ReturnService.cloneReturnBatch} uses {@code "RT-"}).</p>
     *
     * <p><strong>Nhận cả {@code "KK-"}</strong> — tiền tố cũ trước 13/08/2026 (kiểm kê), đổi thành
     * {@code "RS-"} (rà soát) cho khớp cách gọi hiện tại. Lô cũ vẫn phải được tính vào thu nhập chịu
     * thuế, bỏ đi là số thuế của kỳ cũ tự nhiên hụt.</p>
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
             and (b.batchCode like 'RS-%' or b.batchCode like 'KK-%')
             and b.purchaseDetailID is null
           """)
    BigDecimal sumUnknownOriginIncreaseCostInPeriod(@Param("status") String status,
                                                     @Param("from") Instant from,
                                                     @Param("to") Instant to);
}