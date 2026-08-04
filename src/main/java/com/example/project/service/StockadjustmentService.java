package com.example.project.service;

import com.example.project.constant.StockAdjustmentStatus;
import com.example.project.constant.StockReviewType;
import com.example.project.dto.request.StockAdjustmentCreateRequest;
import com.example.project.dto.request.StockAdjustmentItemRequest;
import com.example.project.dto.response.*;
import com.example.project.entity.*;
import com.example.project.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Single service for the Stock Adjustment feature (formerly "stock out"): listing/searching,
 * detail, creation, and the approve/reject workflow that actually moves stock.
 *
 * <p><b>7 loại phiếu</b> (Pharmacy Database Description, bảng {@code stockadjustment}, 04/08/2026):
 * {@code DESTROY / DESTROY_EMPLOYEE_FAULT / INTERNAL_USE / SAMPLE / GIFT} (thủ công, giảm kho),
 * {@code COUNT} (từ phiếu rà soát kho {@code StockReview.type = COUNT}, chứa CẢ dòng tăng lẫn giảm) và
 * {@code DATE_ADJUSTMENT} (từ {@code StockReview.type = DATE}, sửa hạn dùng, KHÔNG đụng tồn kho).
 * Không có {@code INTERNAL_TRANSFER} — hệ thống một cửa hàng.</p>
 */
@Service
public class StockadjustmentService {

    private static final String DIRECTION_IN = "IN";
    private static final String DIRECTION_OUT = "OUT";
    /**
     * Dòng KHÔNG làm đổi tồn kho — chỉ dùng cho {@link #TYPE_DATE_ADJUSTMENT}. Cột {@code direction}
     * là {@code NOT NULL} nên phải có một giá trị; ghi {@code NONE} để không ai đọc nhầm thành tăng/giảm.
     */
    private static final String DIRECTION_NONE = "NONE";

    /**
     * Điều chỉnh theo rà soát kho — GỘP tăng và giảm vào MỘT loại (04/08/2026). Trước đây tách
     * {@code COUNT_INCREASE}/{@code COUNT_DECREASE} thành 2 phiếu riêng; nay một phiếu chứa cả dòng
     * {@code IN} lẫn {@code OUT}, chiều nằm ở từng dòng chi tiết. Một lần đếm kho là MỘT sự kiện —
     * tách đôi làm mất liên hệ giữa phần thừa và phần thiếu của cùng lần đếm đó.
     */
    private static final String TYPE_COUNT = "COUNT";
    /** Sửa hạn dùng ghi sai lúc nhập — từ phiếu rà soát kho loại {@code DATE}. KHÔNG làm đổi tồn kho. */
    private static final String TYPE_DATE_ADJUSTMENT = "DATE_ADJUSTMENT";
    private static final String TYPE_DESTROY = "DESTROY";
    private static final String TYPE_DESTROY_EMPLOYEE_FAULT = "DESTROY_EMPLOYEE_FAULT";

    /** Giá trị CŨ trong DB (trước 04/08/2026) — chỉ để ĐỌC dữ liệu cũ, không bao giờ ghi mới. */
    private static final String TYPE_COUNT_INCREASE_LEGACY = "COUNT_INCREASE";
    private static final String TYPE_COUNT_DECREASE_LEGACY = "COUNT_DECREASE";

    /** Ba loại phiếu rà soát kho ({@code StockReview.type}) — nguồn của 2 loại phiếu điều chỉnh tự sinh. */
    private static final String REVIEW_TYPE_COUNT = StockReviewType.COUNT;
    private static final String REVIEW_TYPE_DATE = StockReviewType.DATE;
    private static final String REVIEW_TYPE_CONDITION = StockReviewType.CONDITION;

    /**
     * Loại phiếu mà giá trị hàng mất được phép đòi nhân viên đền bù — nguồn của phiếu thu
     * "Thu tiền nhân viên đền bù". Không được tính chi phí hợp lý khi có người bồi thường.
     *
     * <p>{@code COUNT} chỉ tính đền bù cho các dòng {@code OUT} (phần THIẾU): một phiếu rà soát kho có thể
     * vừa thừa vừa thiếu, phần thừa không phải thất thoát nên không đòi ai được — xem
     * {@link #isReimbursableLine}.</p>
     */
    private static final Set<String> EMPLOYEE_LIABLE_TYPES =
            Set.of(TYPE_DESTROY_EMPLOYEE_FAULT, TYPE_COUNT, TYPE_COUNT_DECREASE_LEGACY);

    /**
     * Stock-review status strings we read/write. The Stock Review screen (another teammate) owns the
     * canonical spelling; we mirror only the two we need and match them accent-insensitively, so a
     * spelling difference is a one-line fix here.
     */
    private static final String REVIEW_STATUS_APPROVED = "Đã duyệt";
    private static final String REVIEW_STATUS_ADJUSTED = "Đã điều chỉnh";

    /**
     * Loại phiếu người dùng tự chọn khi lập tay. {@code COUNT} và {@code DATE_ADJUSTMENT} KHÔNG nằm ở
     * đây — hai loại đó luôn suy ra từ {@code StockReview.type} của phiếu rà soát kho được chọn, không cho
     * gõ tay (số liệu phải là số đã đếm/đã kiểm, không phải số người lập tự nhập).
     */
    private static final List<String> CREATABLE_TYPES =
            List.of(TYPE_DESTROY, TYPE_DESTROY_EMPLOYEE_FAULT, "INTERNAL_USE", "SAMPLE", "GIFT");

    /**
     * Loại phiếu hủy hàng — được phép (không bắt buộc) tham chiếu một phiếu rà soát kho
     * {@code StockReview.type = CONDITION}.
     *
     * <p>Bắt buộc tham chiếu khi hủy vì <b>hỏng hóc</b> (phải có biên bản xác định tình trạng); hủy vì
     * <b>hết hạn</b> thì KHÔNG cần — hạn dùng đã nằm sẵn trên lô, không cần ai đi kiểm để xác nhận.
     * Hệ thống không đoán được lý do nên để người lập tự gắn; xem {@link #assertReviewTypeMatches}.</p>
     */
    private static final Set<String> CONDITION_REVIEW_TYPES =
            Set.of(TYPE_DESTROY, TYPE_DESTROY_EMPLOYEE_FAULT);

    /**
     * Loại phiếu cần snapshot GIÁ BÁN tại thời điểm ghi nhận ({@code refSellPrice}) — hàng rời kho mà
     * không qua hóa đơn bán, nên giá bán là căn cứ duy nhất để định giá về sau.
     *
     * <p><b>04/08/2026 — bỏ hẳn phần thuế:</b> trước đây 3 loại này còn tính GTGT đầu ra
     * ({@code vatRate/preTaxAmount/vatAmount}) theo giá bán. Hộ kinh doanh nay tính GTGT bằng
     * {@code doanh thu × tỷ lệ %} cho MỌI nhóm, không có khấu trừ đầu ra/đầu vào ⇒ 3 cột đó đã bị bỏ
     * khỏi bảng {@code stockadjustmentdetail}. Chỉ còn {@code refSellPrice}.</p>
     */
    private static final Set<String> REF_SELL_PRICE_TYPES = Set.of("INTERNAL_USE", "GIFT", "SAMPLE");

    /**
     * Loại phiếu đưa hàng tới tay người dùng thật ⇒ cấm hàng quá hạn. Cố tình TÁCH KHỎI
     * {@link #REF_SELL_PRICE_TYPES} dù trùng danh sách: một cái là định giá, một cái là an toàn dược,
     * sau này đổi cái này không được kéo theo cái kia.
     */
    private static final Set<String> NO_EXPIRED_GOODS_TYPES = Set.of("INTERNAL_USE", "GIFT", "SAMPLE");

    /** Ngưỡng "cận hạn" cho badge cảnh báo — cùng mốc 90 ngày với cảnh báo hết hạn F-08 của hệ thống. */
    private static final int NEAR_EXPIRY_DAYS = 90;

    private final StockadjustmentRepository stockadjustmentRepository;
    private final StockadjustmentdetailRepository stockadjustmentdetailRepository;
    private final BatchRepository batchRepository;
    private final ProductunitRepository productunitRepository;
    // Stock Count is owned by another module — we consume it read-only via these bare repositories
    // (findAll / findById / save) and never add query methods to their files.
    private final StockreviewRepository stockreviewRepository;
    private final StockreviewdetailRepository stockreviewdetailRepository;
    // Income (module Thu/Chi của teammate) và Invoicedetail (module Bán hàng) — chỉ ĐỌC.
    private final IncomeRepository incomeRepository;
    private final InvoicedetailRepository invoicedetailRepository;

    public StockadjustmentService(StockadjustmentRepository stockadjustmentRepository,
                                  StockadjustmentdetailRepository stockadjustmentdetailRepository,
                                  BatchRepository batchRepository,
                                  ProductunitRepository productunitRepository,
                                  StockreviewRepository stockreviewRepository,
                                  StockreviewdetailRepository stockreviewdetailRepository,
                                  IncomeRepository incomeRepository,
                                  InvoicedetailRepository invoicedetailRepository) {
        this.stockadjustmentRepository = stockadjustmentRepository;
        this.stockadjustmentdetailRepository = stockadjustmentdetailRepository;
        this.batchRepository = batchRepository;
        this.productunitRepository = productunitRepository;
        this.stockreviewRepository = stockreviewRepository;
        this.stockreviewdetailRepository = stockreviewdetailRepository;
        this.incomeRepository = incomeRepository;
        this.invoicedetailRepository = invoicedetailRepository;
    }

    // ------------------------------------------------------------------ list / search

    @Transactional(readOnly = true)
    public Page<StockAdjustmentListItemResponse> search(String keyword,
                                                        String fromDate,
                                                        String toDate,
                                                        String adjustmentType,
                                                        String status,
                                                        Pageable pageable) {
        final String normalizedKeyword = normalize(keyword);
        final LocalDate from = parseDate(fromDate);
        final LocalDate to = parseDate(toDate);

        List<Stockadjustment> adjustments = stockadjustmentRepository.findAllWithRelations();
        List<Stockadjustmentdetail> allDetails = stockadjustmentdetailRepository.findAllWithRelations();

        Map<Integer, List<Stockadjustmentdetail>> detailMap = allDetails.stream()
                .filter(detail -> detail.getStockAdjustmentID() != null)
                .collect(Collectors.groupingBy(detail -> detail.getStockAdjustmentID().getId()));

        List<StockAdjustmentListItemResponse> filtered = adjustments.stream()
                .filter(adj -> matchesKeyword(adj, detailMap.getOrDefault(adj.getId(), List.of()), normalizedKeyword))
                .filter(adj -> matchesDate(adj, from, to))
                .filter(adj -> adjustmentType == null || adjustmentType.isBlank()
                        || adjustmentType.equals(adj.getAdjustmentType()))
                .filter(adj -> status == null || status.isBlank() || isStatus(getStatusName(adj), status))
                .map(adj -> toListItem(adj, detailMap.getOrDefault(adj.getId(), List.of())))
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());

        List<StockAdjustmentListItemResponse> content = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public StockAdjustmentStatsResponse getStats() {
        List<Stockadjustment> adjustments = stockadjustmentRepository.findAllWithRelations();

        YearMonth currentMonth = YearMonth.now();

        long monthlyCount = adjustments.stream()
                .filter(adj -> adj.getDate() != null)
                .filter(adj -> YearMonth.from(toLocalDate(adj.getDate())).equals(currentMonth))
                .count();

        long draftCount = countByStatusName(adjustments, StockAdjustmentStatus.DRAFT);
        long completedCount = countByStatusName(adjustments, StockAdjustmentStatus.COMPLETED);
        long cancelledCount = countByStatusName(adjustments, StockAdjustmentStatus.CANCELLED);

        return new StockAdjustmentStatsResponse(monthlyCount, draftCount, completedCount, cancelledCount);
    }

    /** Adjustment types a user may create by hand, as an ordered code → Vietnamese-label map. */
    public Map<String, String> creatableTypeLabels() {
        Map<String, String> all = adjustmentTypeLabels();
        Map<String, String> labels = new LinkedHashMap<>();
        for (String type : CREATABLE_TYPES) {
            labels.put(type, all.get(type));
        }
        return labels;
    }

    /** The fixed set of statuses, in workflow order, for the filter dropdown. */
    @Transactional(readOnly = true)
    public List<String> listStatuses() {
        return StockAdjustmentStatus.ALL;
    }

    public Map<String, String> adjustmentTypeLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(TYPE_DESTROY, "Hủy hàng");
        labels.put(TYPE_DESTROY_EMPLOYEE_FAULT, "Hủy hàng (lỗi nhân viên)");
        labels.put("INTERNAL_USE", "Sử dụng nội bộ");
        labels.put("SAMPLE", "Hàng mẫu");
        labels.put("GIFT", "Quà tặng");
        labels.put(TYPE_COUNT, "Điều chỉnh theo rà soát kho");
        labels.put(TYPE_DATE_ADJUSTMENT, "Điều chỉnh hạn dùng");
        // Dữ liệu cũ (trước khi gộp COUNT) vẫn phải hiện đúng tên thay vì mã thô.
        labels.put(TYPE_COUNT_INCREASE_LEGACY, "Tăng theo rà soát kho");
        labels.put(TYPE_COUNT_DECREASE_LEGACY, "Giảm theo rà soát kho");
        return labels;
    }

    /** {@code true} nếu phiếu là điều chỉnh theo rà soát, kể cả 2 giá trị cũ trước khi gộp. */
    private boolean isCountType(String adjustmentType) {
        return TYPE_COUNT.equals(adjustmentType)
                || TYPE_COUNT_INCREASE_LEGACY.equals(adjustmentType)
                || TYPE_COUNT_DECREASE_LEGACY.equals(adjustmentType);
    }

    /**
     * Dòng có được tính vào giá trị đền bù của nhân viên hay không. Chỉ dòng làm GIẢM kho — với phiếu
     * {@code COUNT} vừa thừa vừa thiếu, phần thừa ({@code IN}) không phải thất thoát.
     */
    private boolean isReimbursableLine(Stockadjustmentdetail detail) {
        return !DIRECTION_IN.equals(detail.getDirection()) && !DIRECTION_NONE.equals(detail.getDirection());
    }

    // ------------------------------------------------------------------ detail

    @Transactional(readOnly = true)
    public StockAdjustmentDetailPageResponse getDetail(Integer adjustmentId) {
        Stockadjustment adjustment = stockadjustmentRepository.findByIdWithRelations(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu điều chỉnh kho"));

        List<Stockadjustmentdetail> details =
                stockadjustmentdetailRepository.findByStockOutIdWithRelations(adjustmentId);

        // Đúng loại phiếu THÌ CHƯA ĐỦ: phiếu COUNT chỉ toàn dòng THỪA thì không có gì thất thoát để
        // đòi đền bù — hiện khối "Đền bù thất thoát 0đ" chỉ làm người xem tưởng có người phải trả tiền.
        boolean employeeLiable = EMPLOYEE_LIABLE_TYPES.contains(adjustment.getAdjustmentType())
                && details.stream().anyMatch(this::isReimbursableLine);

        List<StockAdjustmentDetailItemResponse> itemResponses = details.stream()
                .map(detail -> toDetailItem(detail, employeeLiable))
                .toList();

        long totalItems = details.size();

        int totalQuantity = details.stream()
                .map(Stockadjustmentdetail::getQuantity)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        BigDecimal estimatedValue = details.stream()
                .map(Stockadjustmentdetail::getLineCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Giá trị đền bù = tổng theo GIÁ BÁN của các dòng làm GIẢM kho. Chỉ tính cho 2 loại có thể
        // đòi nhân viên đền bù; loại khác để 0 và màn chi tiết ẩn hẳn khối này.
        BigDecimal totalReimbursementValue = employeeLiable
                ? itemResponses.stream()
                        .map(StockAdjustmentDetailItemResponse::getReimbursementValue)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : BigDecimal.ZERO;

        Income linkedIncome = employeeLiable ? findLinkedIncome(adjustment.getId()) : null;

        String statusName = getStatusName(adjustment);

        return new StockAdjustmentDetailPageResponse(
                adjustment.getId(),
                formatCode(adjustment.getId()),
                adjustment.getDate(),
                formatInstant(adjustment.getDate()),
                adjustment.getAdjustmentType(),
                formatAdjustmentType(adjustment.getAdjustmentType()),
                adjustment.getReason(),
                adjustment.getNote(),
                statusName,
                statusCssClass(statusName),
                totalItems,
                totalQuantity,
                estimatedValue,
                costImpactDisplay(adjustment),
                itemResponses,
                TYPE_DATE_ADJUSTMENT.equals(adjustment.getAdjustmentType()),
                adjustment.getStockReviewID() != null
                        ? adjustment.getStockReviewID().getStockCountCode() : null,
                adjustment.getStockReviewID() != null
                        ? reviewTypeLabel(reviewTypeOf(adjustment.getStockReviewID())) : null,
                employeeLiable,
                totalReimbursementValue,
                linkedIncome != null ? linkedIncome.getId() : null,
                linkedIncome != null ? linkedIncome.getIncomeCode() : null
        );
    }

    /**
     * Phiếu thu đã gắn vào phiếu điều chỉnh này, nếu có. Chỉ ĐỌC module Thu/Chi qua {@code findAll()} —
     * không thêm query method vào repository của teammate.
     */
    private Income findLinkedIncome(Integer adjustmentId) {
        if (adjustmentId == null) {
            return null;
        }
        return incomeRepository.findAll().stream()
                .filter(income -> income.getStockAdjustmentID() != null
                        && adjustmentId.equals(income.getStockAdjustmentID().getId()))
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------ hoàn thành / hủy

    /**
     * Commits the slip's stock movement to each batch: {@code IN} adds the quantity, {@code OUT}
     * subtracts it (blocking negative stock). Called only when a slip reaches
     * {@link StockAdjustmentStatus#COMPLETED} — the single point at which stock actually changes.
     *
     * <p>{@link #TYPE_DATE_ADJUSTMENT} đi nhánh riêng: nó sửa {@code batch.expirationDate} chứ KHÔNG
     * đụng {@code storageQuantity}.</p>
     */
    private void applyStockEffect(Stockadjustment adjustment, List<Stockadjustmentdetail> details) {
        if (TYPE_DATE_ADJUSTMENT.equals(adjustment.getAdjustmentType())) {
            applyExpiryEffect(details, false);
            return;
        }
        for (Stockadjustmentdetail detail : details) {
            Batch batch = detail.getBatchID();
            if (batch == null) {
                continue;
            }
            int qty = detail.getBaseQtyDeducted() != null ? detail.getBaseQtyDeducted() : 0;
            int current = batch.getStorageQuantity() != null ? batch.getStorageQuantity() : 0;

            if (DIRECTION_IN.equals(detail.getDirection())) {
                batch.setStorageQuantity(current + qty);
            } else {
                if (current < qty) {
                    throw new IllegalArgumentException("Tồn kho của lô " + displayBatch(batch)
                            + " không đủ để điều chỉnh giảm (" + current + " < " + qty + ")");
                }
                batch.setStorageQuantity(current - qty);
            }
            saveBatchGuardingConcurrentEdit(batch);
        }
    }

    /**
     * Áp (hoặc đảo lại) việc sửa hạn dùng của phiếu {@link #TYPE_DATE_ADJUSTMENT}: ghi
     * {@code batch.expirationDate} = {@code newExpirationDate} khi thực hiện, = {@code oldExpirationDate}
     * khi hủy phiếu.
     *
     * <p>Khi ĐẢO, chỉ trả hạn về giá trị cũ nếu lô vẫn đang mang đúng hạn mà phiếu này đã ghi — nếu một
     * phiếu khác đã sửa tiếp thì ghi đè sẽ xóa mất thay đổi mới hơn. Ca đó bị {@code
     * assertBatchesUntouchedSince} chặn từ trước, đây là lớp phòng thủ thứ hai.</p>
     */
    private void applyExpiryEffect(List<Stockadjustmentdetail> details, boolean reverse) {
        for (Stockadjustmentdetail detail : details) {
            Batch batch = detail.getBatchID();
            if (batch == null) {
                continue;
            }
            LocalDate target = reverse ? detail.getOldExpirationDate() : detail.getNewExpirationDate();
            if (target == null) {
                continue;
            }
            if (reverse && !Objects.equals(batch.getExpirationDate(), detail.getNewExpirationDate())) {
                throw new IllegalArgumentException("Không thể hủy phiếu: hạn dùng của lô " + displayBatch(batch)
                        + " đã được thay đổi sau khi phiếu này được thực hiện."
                        + " Hãy lập một phiếu điều chỉnh hạn dùng mới thay vì hủy phiếu cũ.");
            }
            batch.setExpirationDate(target);
            saveBatchGuardingConcurrentEdit(batch);
        }
    }

    /**
     * Ghi tồn kho một lô, dịch lỗi khoá lạc quan thành câu người dùng đọc được.
     *
     * <p>{@code Batch} có {@code @Version} nên Hibernate đưa version vào {@code WHERE}: ai đọc phải số
     * cũ thì update khớp 0 dòng và bị ném lỗi, thay vì âm thầm ghi đè số của người ghi trước.
     *
     * <p>Phải {@code saveAndFlush}: {@code save} chỉ đưa vào session, UPDATE thật chạy lúc commit —
     * tức là sau khi đã ra khỏi khối {@code try} này. Ném {@link IllegalArgumentException} vì
     * controller chỉ bắt loại đó, loại khác là người dùng nhận trang 500 thô.
     */
    private void saveBatchGuardingConcurrentEdit(Batch batch) {
        try {
            batchRepository.saveAndFlush(batch);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new IllegalArgumentException("Lô " + displayBatch(batch)
                    + " vừa được người khác cập nhật (bán hàng, nhập hàng hoặc phiếu điều chỉnh khác)."
                    + " Vui lòng tải lại trang để xem tồn kho mới nhất rồi thực hiện lại.", exception);
        }
    }

    /**
     * Hủy phiếu lập sai: phiếu {@code Nháp} chưa đụng tồn kho nên chỉ đổi trạng thái; phiếu
     * {@code Hoàn thành} thì đảo ngược đúng phần đã cộng/trừ (IN trừ lại, OUT cộng lại).
     * Lý do hủy nối vào {@code note} — DB không có cột riêng.
     */
    @Transactional
    public void cancel(Integer adjustmentId, String reason) {
        Stockadjustment adjustment = stockadjustmentRepository.findByIdWithRelations(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu điều chỉnh kho"));

        String statusName = getStatusName(adjustment);
        if (isStatus(statusName, StockAdjustmentStatus.CANCELLED)) {
            throw new IllegalArgumentException("Phiếu điều chỉnh kho này đã bị hủy trước đó");
        }

        if (isStatus(statusName, StockAdjustmentStatus.COMPLETED)) {
            List<Stockadjustmentdetail> details =
                    stockadjustmentdetailRepository.findByStockOutIdWithRelations(adjustmentId);
            if (TYPE_DATE_ADJUSTMENT.equals(adjustment.getAdjustmentType())) {
                // Phiếu sửa hạn dùng không đụng tồn kho ⇒ đảo lại hạn, không đảo số lượng. Cũng không
                // chạy assertBatchesUntouchedSince: luật đó đo tồn kho, còn ở đây thứ bị đổi là hạn dùng
                // (applyExpiryEffect tự kiểm hạn hiện tại có còn đúng của phiếu này không).
                applyExpiryEffect(details, true);
            } else {
                assertBatchesUntouchedSince(adjustment, details);
                reverseStockEffect(details);
            }
            // Trả phiếu rà soát kho về "Đã duyệt" để có thể lập lại phiếu điều chỉnh khác cho nó.
            revertStockReviewAdjusted(adjustment.getStockReviewID());
        }

        adjustment.setStatus(StockAdjustmentStatus.CANCELLED);
        adjustment.setNote(appendNote(adjustment.getNote(),
                "Đã hủy" + (trimToNull(reason) != null ? ": " + reason.trim() : "")));

        stockadjustmentRepository.save(adjustment);
    }

    /**
     * Luật đảo ngược DUY NHẤT (sheet 05): chỉ hủy được phiếu {@code Hoàn thành} khi mọi lô của nó vẫn
     * y hệt như ngay sau khi phiếu được áp dụng. Kiểm 2 nguồn duy nhất làm đổi tồn: hóa đơn bán và
     * phiếu điều chỉnh khác (chỉ tính phiếu đang {@code Hoàn thành} — phiếu đã hủy có tác động ròng 0).
     *
     * <p>"Lô hết hạn không đảo ngược được" chỉ là hệ quả của luật này, không phải luật riêng.
     */
    private void assertBatchesUntouchedSince(Stockadjustment adjustment, List<Stockadjustmentdetail> details) {
        if (adjustment.getDate() == null) {
            return;
        }
        // Invoice.date là giờ tường VN (LocalDateTime); Stockadjustment.date là Instant — quy về cùng múi.
        LocalDateTime appliedAt = LocalDateTime.ofInstant(adjustment.getDate(), ZoneId.systemDefault());

        Set<Integer> batchIds = details.stream()
                .map(Stockadjustmentdetail::getBatchID)
                .filter(Objects::nonNull)
                .map(Batch::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (Integer batchId : batchIds) {
            if (invoicedetailRepository.countSalesFromBatchAfter(batchId, appliedAt) > 0) {
                throw new IllegalArgumentException("Không thể hủy phiếu: lô "
                        + displayBatch(batchOf(details, batchId))
                        + " đã được bán ra sau khi phiếu này được thực hiện. "
                        + "Hãy lập một phiếu điều chỉnh mới thay vì hủy phiếu cũ.");
            }
            Stockadjustment blocker = laterAdjustmentOn(batchId, adjustment.getId(), adjustment.getDate());
            if (blocker != null) {
                throw new IllegalArgumentException("Không thể hủy phiếu: lô "
                        + displayBatch(batchOf(details, batchId))
                        + " đã bị phiếu " + formatCode(blocker.getId())
                        + " (" + formatAdjustmentType(blocker.getAdjustmentType()) + ") điều chỉnh sau đó. "
                        + "Hãy lập một phiếu điều chỉnh mới thay vì hủy phiếu cũ.");
            }
        }
    }

    /** Phiếu điều chỉnh {@code Hoàn thành} KHÁC đã động vào lô sau mốc {@code after}, nếu có. */
    private Stockadjustment laterAdjustmentOn(Integer batchId, Integer excludeAdjustmentId, Instant after) {
        return stockadjustmentdetailRepository.findAllWithRelations().stream()
                .filter(detail -> detail.getBatchID() != null && batchId.equals(detail.getBatchID().getId()))
                .map(Stockadjustmentdetail::getStockAdjustmentID)
                .filter(Objects::nonNull)
                .filter(other -> !Objects.equals(other.getId(), excludeAdjustmentId))
                .filter(other -> isStatus(getStatusName(other), StockAdjustmentStatus.COMPLETED))
                .filter(other -> other.getDate() != null && other.getDate().isAfter(after))
                .findFirst()
                .orElse(null);
    }

    private Batch batchOf(List<Stockadjustmentdetail> details, Integer batchId) {
        return details.stream()
                .map(Stockadjustmentdetail::getBatchID)
                .filter(batch -> batch != null && batchId.equals(batch.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lô hàng của phiếu"));
    }

    /** Đảo ngược {@link #applyStockEffect}: IN trừ lại, OUT cộng lại. Chặn nếu trừ lại làm tồn âm. */
    private void reverseStockEffect(List<Stockadjustmentdetail> details) {
        for (Stockadjustmentdetail detail : details) {
            Batch batch = detail.getBatchID();
            if (batch == null) {
                continue;
            }
            int qty = detail.getBaseQtyDeducted() != null ? detail.getBaseQtyDeducted() : 0;
            int current = batch.getStorageQuantity() != null ? batch.getStorageQuantity() : 0;

            if (DIRECTION_IN.equals(detail.getDirection())) {
                if (current < qty) {
                    throw new IllegalArgumentException("Không thể hủy phiếu: lô " + displayBatch(batch)
                            + " chỉ còn " + current + " trong kho, không đủ để đảo lại " + qty
                            + " đã cộng vào (hàng đã được bán/xuất bớt). Hãy lập phiếu điều chỉnh mới thay vì hủy.");
                }
                batch.setStorageQuantity(current - qty);
            } else {
                batch.setStorageQuantity(current + qty);
            }
            saveBatchGuardingConcurrentEdit(batch);
        }
    }

    // ------------------------------------------------------------------ create

    @Transactional(readOnly = true)
    public List<StockAdjustmentBatchCandidateResponse> listAvailableBatches(String keyword) {
        String normalizedKeyword = normalize(keyword);

        return batchRepository.findAvailableBatchesForDestroy()
                .stream()
                .filter(batch -> matchesBatchKeyword(batch, normalizedKeyword))
                .map(this::toCandidateResponse)
                .toList();
    }

    // ------------------------------------------------------------------ stock count source (read-only)

    /**
     * Phiếu rà soát kho "Đã duyệt" còn dùng được để lập phiếu điều chỉnh: còn ít nhất một dòng đáng điều
     * chỉnh và chưa bị phiếu điều chỉnh nào (khác "đã hủy") dùng mất. Read-only — consumes the Stock
     * Review module through its bare repositories.
     *
     * <p>Trả về CẢ 3 loại; "đáng điều chỉnh" được đo theo đúng loại: {@code COUNT} đếm dòng lệch số
     * lượng, {@code DATE} đếm lô lệch hạn dùng, {@code CONDITION} đếm lô có ghi nhận tình trạng. Màn
     * hình tự lọc theo loại phiếu đang lập — xem {@link #listApprovedStockReviews(String)}.</p>
     */
    @Transactional(readOnly = true)
    public List<StockAdjustmentReviewOptionResponse> listApprovedStockReviews() {
        Set<Integer> consumedCountIds = stockadjustmentRepository.findAllWithRelations().stream()
                .filter(adj -> adj.getStockReviewID() != null && adj.getStockReviewID().getId() != null)
                .filter(adj -> !isStatus(getStatusName(adj), StockAdjustmentStatus.CANCELLED))
                .map(adj -> adj.getStockReviewID().getId())
                .collect(Collectors.toSet());

        Map<Integer, List<Stockreviewdetail>> detailsByCount = stockreviewdetailRepository.findAll().stream()
                .filter(detail -> detail.getStockReviewID() != null && detail.getStockReviewID().getId() != null)
                .collect(Collectors.groupingBy(detail -> detail.getStockReviewID().getId()));

        return stockreviewRepository.findAll().stream()
                .filter(count -> isStatus(count.getStatus(), REVIEW_STATUS_APPROVED))
                .filter(count -> !consumedCountIds.contains(count.getId()))
                .map(count -> {
                    String reviewType = reviewTypeOf(count);
                    List<Stockreviewdetail> details = detailsByCount.getOrDefault(count.getId(), List.of());
                    return new StockAdjustmentReviewOptionResponse(
                            count.getId(),
                            count.getStockCountCode(),
                            reviewType,
                            reviewTypeLabel(reviewType),
                            formatInstant(count.getReviewDate()),
                            (int) details.stream().filter(d -> isAdjustableDetail(d, reviewType)).count(),
                            count.getNote());
                })
                .filter(option -> option.getDiscrepancyLineCount() > 0)
                .sorted(Comparator.comparing(StockAdjustmentReviewOptionResponse::getStockReviewId,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** Chỉ những phiếu rà soát kho thuộc {@code reviewType} — dùng cho từng ngữ cảnh của màn tạo. */
    @Transactional(readOnly = true)
    public List<StockAdjustmentReviewOptionResponse> listApprovedStockReviews(String reviewType) {
        return listApprovedStockReviews().stream()
                .filter(option -> option.getReviewType().equals(reviewType))
                .toList();
    }

    /** "Đáng điều chỉnh" đo theo đúng loại phiếu rà soát kho. */
    private boolean isAdjustableDetail(Stockreviewdetail detail, String reviewType) {
        return switch (reviewType) {
            case REVIEW_TYPE_DATE -> isAdjustableDateDetail(detail);
            case REVIEW_TYPE_CONDITION -> detail.getConditionStatus() != null
                    && !detail.getConditionStatus().isBlank();
            default -> isAdjustableCountDetail(detail);
        };
    }

    /**
     * The prospective adjustment lines for one approved stock count: one per detail whose actual
     * quantity differs from the system quantity (and has a batch). Thừa → {@code IN}, thiếu →
     * {@code OUT}; cả hai đều thuộc cùng MỘT phiếu loại {@link #TYPE_COUNT}. Read-only preview;
     * the create flow rebuilds these server-side.
     */
    @Transactional(readOnly = true)
    public List<StockAdjustmentReviewLineResponse> loadStockReviewLines(Integer stockReviewId) {
        Stockreview count = stockreviewRepository.findById(stockReviewId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu rà soát kho"));
        if (!isStatus(count.getStatus(), REVIEW_STATUS_APPROVED)) {
            throw new IllegalArgumentException("Chỉ chọn được phiếu rà soát kho đã duyệt");
        }
        String reviewType = reviewTypeOf(count);
        if (REVIEW_TYPE_CONDITION.equals(reviewType)) {
            throw new IllegalArgumentException("Phiếu rà soát tình trạng không sinh dòng điều chỉnh nào");
        }
        boolean dateSource = REVIEW_TYPE_DATE.equals(reviewType);

        return stockreviewdetailRepository.findAll().stream()
                .filter(detail -> detail.getStockReviewID() != null
                        && stockReviewId.equals(detail.getStockReviewID().getId()))
                .filter(detail -> isAdjustableDetail(detail, reviewType))
                .map(detail -> dateSource ? toDateLine(detail) : toCountLine(detail))
                .toList();
    }

    /** Dòng xem trước của phiếu sửa hạn dùng: không đụng tồn, chỉ nêu hạn cũ → hạn mới. */
    private StockAdjustmentReviewLineResponse toDateLine(Stockreviewdetail detail) {
        Batch batch = detail.getBatchID();
        Product product = batch.getProductID() != null ? batch.getProductID() : detail.getProductID();
        Productunit unit = resolveCandidateUnit(batch, product);

        int affectedQty = batch.getStorageQuantity() != null ? batch.getStorageQuantity() : 0;
        BigDecimal unitCost = resolveUnitCost(batch);

        return new StockAdjustmentReviewLineResponse(
                batch.getId(),
                product != null ? product.getProductID() : null,
                product != null ? product.getName() : "Không rõ",
                batch.getLotNumber(),
                batch.getExpirationDate(),
                formatLocalDate(batch.getExpirationDate()),
                unit != null ? unit.getUnitName() : "Đơn vị",
                affectedQty,
                affectedQty,
                0,
                TYPE_DATE_ADJUSTMENT,
                DIRECTION_NONE,
                unitCost,
                unitCost.multiply(BigDecimal.valueOf(affectedQty)),
                formatLocalDate(batch.getExpirationDate()),
                formatLocalDate(detail.getActualExpirationDate()));
    }

    /** A detail is adjustable when it has a batch and a non-zero, non-null discrepancy. */
    private boolean isAdjustableCountDetail(Stockreviewdetail detail) {
        if (detail.getBatchID() == null || detail.getBatchID().getId() == null) {
            return false;
        }
        Integer systemQty = detail.getSystemQty();
        Integer actualQty = detail.getActualQty();
        return systemQty != null && actualQty != null && !systemQty.equals(actualQty);
    }

    private StockAdjustmentReviewLineResponse toCountLine(Stockreviewdetail detail) {
        Batch batch = detail.getBatchID();
        Product product = batch.getProductID() != null ? batch.getProductID() : detail.getProductID();
        Productunit unit = resolveCandidateUnit(batch, product);

        int discrepancy = detail.getActualQty() - detail.getSystemQty();
        boolean surplus = discrepancy > 0;
        int quantity = Math.abs(discrepancy);

        BigDecimal unitCost = resolveUnitCost(batch);
        BigDecimal lineCost = unitCost.multiply(BigDecimal.valueOf(quantity));

        return new StockAdjustmentReviewLineResponse(
                batch.getId(),
                product != null ? product.getProductID() : null,
                product != null ? product.getName() : "Không rõ",
                batch.getLotNumber(),
                batch.getExpirationDate(),
                formatLocalDate(batch.getExpirationDate()),
                unit != null ? unit.getUnitName() : "Đơn vị",
                detail.getSystemQty(),
                detail.getActualQty(),
                quantity,
                // Loại phiếu nay là COUNT cho CẢ hai chiều; thừa/thiếu phân biệt bằng direction của dòng.
                TYPE_COUNT,
                surplus ? DIRECTION_IN : DIRECTION_OUT,
                unitCost,
                lineCost,
                null,
                null);
    }

    /**
     * Creates an adjustment slip. Two sources:
     * <ul>
     *   <li><b>MANUAL</b> — one slip of a {@link #CREATABLE_TYPES} type from the batches the user picked.
     *       Phiếu hủy hàng có thể gắn kèm một phiếu rà soát tình trạng ({@code StockReview.type =
     *       CONDITION}) làm căn cứ — bắt buộc khi hủy vì hỏng hóc, không cần khi hủy vì hết hạn.</li>
     *   <li><b>STOCK_REVIEW</b> — MỘT phiếu dựng lại từ phiếu rà soát kho đã duyệt, loại suy ra từ
     *       {@code StockReview.type} ({@code COUNT} hoặc {@code DATE_ADJUSTMENT}).</li>
     * </ul>
     *
     * <p>{@code asDraft} → {@link StockAdjustmentStatus#DRAFT}, ngược lại → {@code COMPLETED} và tồn
     * kho cập nhật ngay. Không có nhánh "chờ duyệt": chỉ Owner tạo được phiếu này nên không có ai để
     * duyệt chéo. Trả về id phiếu để controller redirect.</p>
     *
     * <p><strong>Không ghi lại người thao tác</strong> .
     * Mốc duy nhất còn lại là {@code date} (lúc lập phiếu).</p>
     */
    @Transactional
    public Integer createAdjustment(StockAdjustmentCreateRequest request, boolean asDraft) {
        if (isStockReviewSource(request)) {
            return createFromStockReview(request, asDraft);
        }

        validateRequest(request);

        String adjustmentType = resolveCreatableType(request.getAdjustmentType());

        Map<Integer, StockAdjustmentItemRequest> itemMap = request.getItems().stream()
                .collect(Collectors.toMap(
                        StockAdjustmentItemRequest::getBatchId,
                        item -> item,
                        (first, second) -> {
                            first.setQuantity(first.getQuantity() + second.getQuantity());

                            String firstReason = first.getReason() == null ? "" : first.getReason();
                            String secondReason = second.getReason() == null ? "" : second.getReason();

                            if (!secondReason.isBlank() && !firstReason.contains(secondReason)) {
                                first.setReason(firstReason.isBlank() ? secondReason : firstReason + ", " + secondReason);
                            }

                            return first;
                        }
                ));

        List<Batch> selectedBatches = batchRepository.findAllById(itemMap.keySet());
        if (selectedBatches.size() != itemMap.size()) {
            throw new IllegalArgumentException("Một số lô hàng không tồn tại");
        }
        validateSelectedBatches(selectedBatches, itemMap, adjustmentType);

        String status = asDraft ? StockAdjustmentStatus.DRAFT : StockAdjustmentStatus.COMPLETED;
        boolean approvedNow = StockAdjustmentStatus.COMPLETED.equals(status);

        Stockadjustment adjustment = new Stockadjustment();
        adjustment.setStockAdjustmentCode(temporaryCode());
        adjustment.setAdjustmentType(adjustmentType);
        adjustment.setDate(Instant.now());
        adjustment.setReason(request.getReason().trim());
        adjustment.setStockReviewID(resolveConditionReview(request.getStockReviewId(), adjustmentType));
        adjustment.setStatus(status);
        adjustment.setNote(trimToNull(request.getNote()));

        Stockadjustment savedAdjustment = assignCode(stockadjustmentRepository.save(adjustment));

        List<Stockadjustmentdetail> savedDetails = new ArrayList<>();
        for (Batch batch : selectedBatches) {
            StockAdjustmentItemRequest item = itemMap.get(batch.getId());

            Product product = batch.getProductID();
            Productunit unit = resolveUnit(batch, product);

            BigDecimal unitCost = resolveUnitCost(batch);
            BigDecimal lineCost = unitCost.multiply(BigDecimal.valueOf(item.getQuantity()));

            Stockadjustmentdetail detail = new Stockadjustmentdetail();
            detail.setStockAdjustmentID(savedAdjustment);
            detail.setProductID(product);
            detail.setProductUnitID(unit);
            detail.setBatchID(batch);
            detail.setDirection(DIRECTION_OUT);
            detail.setQuantity(item.getQuantity());
            detail.setBaseQtyDeducted(item.getQuantity());
            detail.setUnitCostPrice(unitCost);
            detail.setLineCost(lineCost);
            detail.setNote(trimToNull(item.getReason()));
            // Snapshot giá bán — chỉ INTERNAL_USE/GIFT/SAMPLE; loại khác để null.
            applyReferenceSellPrice(detail, adjustmentType, unit);

            savedDetails.add(stockadjustmentdetailRepository.save(detail));
        }

        if (approvedNow) {
            applyStockEffect(savedAdjustment, savedDetails);
        }

        return savedAdjustment.getId();
    }

    private boolean isStockReviewSource(StockAdjustmentCreateRequest request) {
        return "STOCK_REVIEW".equalsIgnoreCase(request.getSourceMode());
    }

    /**
     * Phiếu rà soát tình trạng gắn kèm một phiếu HỦY HÀNG lập tay — TÙY CHỌN, trả {@code null} khi
     * người lập không chọn (hủy vì hết hạn thì hạn nằm sẵn trên lô, không cần biên bản kiểm tra).
     *
     * <p>Chỉ 2 loại hủy hàng được gắn: gắn phiếu rà soát tình trạng vào phiếu quà tặng / hàng mẫu /
     * dùng nội bộ là vô nghĩa (hàng còn tốt mới đem tặng). Và phiếu được gắn phải đúng loại
     * {@code CONDITION} + đã duyệt — nếu không thì căn cứ hủy hàng lại là một lần đếm số lượng.</p>
     */
    private Stockreview resolveConditionReview(Integer reviewId, String adjustmentType) {
        if (reviewId == null) {
            return null;
        }
        if (!CONDITION_REVIEW_TYPES.contains(adjustmentType)) {
            throw new IllegalArgumentException(
                    "Chỉ phiếu Hủy hàng mới gắn được phiếu rà soát tình trạng");
        }
        Stockreview review = stockreviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu rà soát tình trạng"));
        assertReviewTypeMatches(review, REVIEW_TYPE_CONDITION);
        if (!isStatus(review.getStatus(), REVIEW_STATUS_APPROVED)) {
            throw new IllegalArgumentException("Phiếu rà soát tình trạng chưa được duyệt");
        }
        return review;
    }

    /** Phiếu rà soát kho được chọn phải đúng loại yêu cầu — sai loại là sai căn cứ nghiệp vụ. */
    private void assertReviewTypeMatches(Stockreview review, String expectedType) {
        if (!expectedType.equals(reviewTypeOf(review))) {
            throw new IllegalArgumentException("Phiếu rà soát kho " + (review.getStockCountCode() != null
                    ? review.getStockCountCode() : "") + " không phải loại "
                    + reviewTypeLabel(expectedType));
        }
    }

    /** Nhãn tiếng Việt của {@code StockReview.type} — dùng trong câu thông báo và trên màn hình. */
    public String reviewTypeLabel(String reviewType) {
        return StockReviewType.label(reviewType);
    }

    /**
     * Nguồn STOCK_REVIEW: dựng lại các dòng từ phiếu rà soát kho đã duyệt được chọn. Loại phiếu điều chỉnh
     * SUY RA từ {@code StockReview.type}, người lập không chọn được:
     * <ul>
     *   <li>{@code type = COUNT} → <b>MỘT</b> phiếu {@link #TYPE_COUNT} chứa cả dòng thừa ({@code IN})
     *       lẫn dòng thiếu ({@code OUT}). Trước 04/08/2026 chỗ này tách thành 2 phiếu riêng.</li>
     *   <li>{@code type = DATE} → phiếu {@link #TYPE_DATE_ADJUSTMENT}, sửa hạn dùng, không đụng tồn.</li>
     *   <li>{@code type = CONDITION} → KHÔNG tự sinh phiếu: kiểm tình trạng chỉ ghi nhận hàng hỏng, còn
     *       hủy hay không là quyết định riêng ⇒ người lập tự chọn loại hủy và gắn phiếu này vào.</li>
     * </ul>
     *
     * <p>Dòng do client gửi lên bị BỎ QUA — số lượng chỉ tin từ phiếu rà soát kho. Khi phiếu được lập thẳng
     * ở trạng thái {@code Hoàn thành}, phiếu rà soát kho được lật sang {@code Đã điều chỉnh} ngay tại đây.</p>
     */
    private Integer createFromStockReview(StockAdjustmentCreateRequest request, boolean asDraft) {
        if (request.getStockReviewId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn phiếu rà soát kho");
        }

        Stockreview count = stockreviewRepository.findById(request.getStockReviewId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu rà soát kho"));
        if (!isStatus(count.getStatus(), REVIEW_STATUS_APPROVED)) {
            throw new IllegalArgumentException("Phiếu rà soát kho không ở trạng thái Đã duyệt");
        }

        String reviewType = reviewTypeOf(count);
        if (REVIEW_TYPE_CONDITION.equals(reviewType)) {
            throw new IllegalArgumentException("Phiếu rà soát tình trạng không tự sinh phiếu điều chỉnh — "
                    + "hãy chọn loại Hủy hàng rồi gắn phiếu kiểm tra này vào.");
        }
        boolean dateSource = REVIEW_TYPE_DATE.equals(reviewType);
        String adjustmentType = dateSource ? TYPE_DATE_ADJUSTMENT : TYPE_COUNT;

        // Reason is optional for a review-sourced slip: auto-fill it from the review when left blank.
        String reviewCode = count.getStockCountCode() != null ? count.getStockCountCode() : "";
        String reason = (request.getReason() != null && !request.getReason().isBlank())
                ? request.getReason().trim()
                : (dateSource
                        ? "Điều chỉnh hạn dùng theo phiếu kiểm tra hạn dùng " + reviewCode
                        : "Điều chỉnh tồn kho theo chênh lệch rà soát kho " + reviewCode);

        boolean alreadyConsumed = stockadjustmentRepository.findAllWithRelations().stream()
                .anyMatch(adj -> adj.getStockReviewID() != null
                        && request.getStockReviewId().equals(adj.getStockReviewID().getId())
                        && !isStatus(getStatusName(adj), StockAdjustmentStatus.CANCELLED));
        if (alreadyConsumed) {
            throw new IllegalArgumentException("Phiếu rà soát kho này đã có phiếu điều chỉnh");
        }

        if (dateSource) {
            return persistDateSlip(count, asDraft ? StockAdjustmentStatus.DRAFT : StockAdjustmentStatus.COMPLETED,
                    reason, request);
        }

        List<StockAdjustmentReviewLineResponse> lines = loadStockReviewLines(request.getStockReviewId());
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Phiếu rà soát kho không có dòng chênh lệch để điều chỉnh");
        }

        String status = asDraft ? StockAdjustmentStatus.DRAFT : StockAdjustmentStatus.COMPLETED;
        return persistCountSlip(adjustmentType, lines, count, status, reason, request);
    }

    /** {@code StockReview.type} chuẩn hóa về chữ hoa; mặc định {@code COUNT} cho dữ liệu cũ chưa có type. */
    private String reviewTypeOf(Stockreview review) {
        return StockReviewType.normalize(review == null ? null : review.getType());
    }

    /**
     * Phiếu {@link #TYPE_DATE_ADJUSTMENT}: sửa hạn dùng của các lô mà phiếu kiểm tra ghi nhận hạn thực tế
     * KHÁC hạn đang lưu trên hệ thống (thường do nhập sai lúc nhận hàng).
     *
     * <p><b>Không đụng tồn kho.</b> Mỗi dòng lưu {@code oldExpirationDate}/{@code newExpirationDate} để
     * truy vết được đã sửa từ đâu sang đâu — đây cũng là căn cứ đảo ngược khi hủy phiếu.
     * {@code quantity} ghi tồn hiện có của lô để biết bao nhiêu hàng chịu ảnh hưởng, chiều là
     * {@link #DIRECTION_NONE}.</p>
     */
    private Integer persistDateSlip(Stockreview review, String status, String reason,
                                    StockAdjustmentCreateRequest request) {
        List<Stockreviewdetail> dateLines = stockreviewdetailRepository.findAll().stream()
                .filter(detail -> detail.getStockReviewID() != null
                        && review.getId().equals(detail.getStockReviewID().getId()))
                .filter(this::isAdjustableDateDetail)
                .toList();
        if (dateLines.isEmpty()) {
            throw new IllegalArgumentException("Phiếu kiểm tra hạn dùng không có lô nào lệch hạn để điều chỉnh");
        }

        Stockadjustment adjustment = new Stockadjustment();
        adjustment.setStockAdjustmentCode(temporaryCode());
        adjustment.setAdjustmentType(TYPE_DATE_ADJUSTMENT);
        adjustment.setDate(Instant.now());
        adjustment.setReason(reason);
        adjustment.setStockReviewID(review);
        adjustment.setStatus(status);
        adjustment.setNote(trimToNull(request.getNote()));

        Stockadjustment savedAdjustment = assignCode(stockadjustmentRepository.save(adjustment));

        List<Stockadjustmentdetail> savedDetails = new ArrayList<>();
        for (Stockreviewdetail line : dateLines) {
            Batch batch = line.getBatchID();
            Product product = batch.getProductID() != null ? batch.getProductID() : line.getProductID();
            Productunit unit = resolveUnit(batch, product);

            int affectedQty = batch.getStorageQuantity() != null ? batch.getStorageQuantity() : 0;
            BigDecimal unitCost = resolveUnitCost(batch);

            Stockadjustmentdetail detail = new Stockadjustmentdetail();
            detail.setStockAdjustmentID(savedAdjustment);
            detail.setProductID(product);
            detail.setProductUnitID(unit);
            detail.setBatchID(batch);
            detail.setDirection(DIRECTION_NONE);
            detail.setQuantity(affectedQty);
            detail.setBaseQtyDeducted(0);
            detail.setUnitCostPrice(unitCost);
            detail.setLineCost(unitCost.multiply(BigDecimal.valueOf(affectedQty)));
            // Hạn CŨ chụp từ chính lô (nguồn đúng lúc áp phiếu), không lấy recordedExpirationDate của
            // phiếu kiểm tra: giữa lúc kiểm và lúc lập phiếu có thể đã có phiếu khác sửa hạn lô này.
            detail.setOldExpirationDate(batch.getExpirationDate());
            detail.setNewExpirationDate(line.getActualExpirationDate());
            detail.setNote("Sửa hạn dùng theo phiếu kiểm tra "
                    + (review.getStockCountCode() != null ? review.getStockCountCode() : ""));

            savedDetails.add(stockadjustmentdetailRepository.save(detail));
        }

        if (StockAdjustmentStatus.COMPLETED.equals(status)) {
            applyStockEffect(savedAdjustment, savedDetails);
        }
        return savedAdjustment.getId();
    }

    /** Dòng kiểm tra hạn dùng đáng điều chỉnh: có lô, có hạn thực tế, và hạn đó KHÁC hạn đang lưu. */
    private boolean isAdjustableDateDetail(Stockreviewdetail detail) {
        if (detail.getBatchID() == null || detail.getBatchID().getId() == null) {
            return false;
        }
        LocalDate actual = detail.getActualExpirationDate();
        return actual != null && !actual.equals(detail.getBatchID().getExpirationDate());
    }

    private Integer persistCountSlip(String adjustmentType,
                                     List<StockAdjustmentReviewLineResponse> lines,
                                     Stockreview count,
                                     String status,
                                     String reason,
                                     StockAdjustmentCreateRequest request) {
        boolean approvedNow = StockAdjustmentStatus.COMPLETED.equals(status);

        Stockadjustment adjustment = new Stockadjustment();
        adjustment.setStockAdjustmentCode(temporaryCode());
        adjustment.setAdjustmentType(adjustmentType);
        adjustment.setDate(Instant.now());
        adjustment.setReason(reason);
        adjustment.setStockReviewID(count);
        adjustment.setStatus(status);
        adjustment.setNote(trimToNull(request.getNote()));

        Stockadjustment savedAdjustment = assignCode(stockadjustmentRepository.save(adjustment));

        List<Stockadjustmentdetail> savedDetails = new ArrayList<>();
        Set<Integer> unknownOrigin = request.getUnknownOriginBatchIds() == null
                ? Set.of()
                : new HashSet<>(request.getUnknownOriginBatchIds());

        for (StockAdjustmentReviewLineResponse line : lines) {
            Batch sourceBatch = batchRepository.findById(line.getBatchId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lô hàng của phiếu rà soát kho"));
            Product product = sourceBatch.getProductID();
            Productunit unit = resolveUnit(sourceBatch, product);

            // Hàng thừa không rõ nguồn gốc → lô MỚI, không có hóa đơn mua thật (sheet 06 mục 1b).
            // Chỉ áp cho dòng THỪA: một phiếu COUNT nay chứa cả 2 chiều nên phải xét theo DÒNG,
            // không xét theo loại phiếu như hồi còn tách COUNT_INCREASE riêng.
            boolean createNewBatch = DIRECTION_IN.equals(line.getDirection())
                    && unknownOrigin.contains(line.getBatchId());
            Batch batch = createNewBatch
                    ? createSurplusBatch(sourceBatch, savedAdjustment, count)
                    : sourceBatch;

            // Lô mới không có giá vốn thật → dùng giá ƯỚC TÍNH lấy từ lô nhập gần nhất của cùng
            // sản phẩm, nên giá vốn dòng phải khớp lô vừa tạo chứ không phải lô đếm được.
            BigDecimal unitCost = createNewBatch ? batch.getImportPricePerBase() : line.getUnitCostPrice();
            BigDecimal lineCost = unitCost.multiply(BigDecimal.valueOf(line.getQuantity()));

            Stockadjustmentdetail detail = new Stockadjustmentdetail();
            detail.setStockAdjustmentID(savedAdjustment);
            detail.setProductID(product);
            detail.setProductUnitID(unit);
            detail.setBatchID(batch);
            detail.setDirection(line.getDirection());
            detail.setQuantity(line.getQuantity());
            detail.setBaseQtyDeducted(line.getQuantity());
            detail.setUnitCostPrice(unitCost);
            detail.setLineCost(lineCost);
            detail.setNote(createNewBatch
                    ? "Hàng thừa không rõ nguồn gốc — lập lô mới " + batch.getBatchCode()
                        + " (giá vốn ước tính, không có hóa đơn mua)"
                    : null);

            savedDetails.add(stockadjustmentdetailRepository.save(detail));
        }

        if (approvedNow) {
            applyStockEffect(savedAdjustment, savedDetails);
        }
        return savedAdjustment.getId();
    }

    /**
     * Lô MỚI cho hàng thừa không rõ nguồn gốc (sheet 06 mục 1b). Bắt buộc {@code purchaseDetailID =
     * NULL} — không có hóa đơn mua thật ⇒ không có GTGT đầu vào để khấu trừ cho lô này.
     *
     * <p>Số lô / hạn dùng chép từ lô được đếm: hai thông tin này ảnh hưởng FEFO lẫn an toàn dược nên
     * không được để trống. Tồn khởi tạo = 0 vì tồn chỉ cộng vào lúc phiếu {@code Hoàn thành}.</p>
     */
    private Batch createSurplusBatch(Batch sourceBatch, Stockadjustment adjustment, Stockreview count) {
        BigDecimal estimatedCost = estimateImportPricePerBase(sourceBatch);

        Batch batch = new Batch();
        // KK-{id phiếu điều chỉnh}-L{id lô được đếm} — nhìn mã là truy ngược được cả phiếu lẫn lô gốc.
        batch.setBatchCode(truncate("KK-" + String.format("%06d", adjustment.getId())
                + "-L" + (sourceBatch.getId() != null ? sourceBatch.getId() : 0), 50));
        batch.setBatchName(truncate("Hàng thừa rà soát kho "
                + (sourceBatch.getBatchName() != null ? sourceBatch.getBatchName() : ""), 50));
        batch.setProductID(sourceBatch.getProductID());
        batch.setPurchaseDetailID(null);
        batch.setStorageQuantity(0);
        batch.setImportUnitID(sourceBatch.getImportUnitID());
        batch.setImportQtyInUnit(sourceBatch.getImportQtyInUnit());
        batch.setImportPrice(estimatedCost);
        batch.setImportPricePerBase(estimatedCost);
        batch.setImportDate(Instant.now());
        batch.setProductionDate(sourceBatch.getProductionDate());
        batch.setExpirationDate(sourceBatch.getExpirationDate());
        batch.setLotNumber(sourceBatch.getLotNumber());
        batch.setStatus(true);
        batch.setNote("Hàng thừa không rõ nguồn gốc theo phiếu rà soát kho "
                + (count != null && count.getStockCountCode() != null ? count.getStockCountCode() : "")
                + " — giá vốn ước tính, không có hóa đơn mua.");
        return batchRepository.save(batch);
    }

    /**
     * Giá vốn ước tính cho lô hàng thừa: lấy từ lô nhập <em>gần nhất</em> của cùng sản phẩm mà
     * {@code purchaseDetailID} khác null — tức lô có hóa đơn mua thật. Không tìm được thì rơi về giá
     * của chính lô được đếm, cuối cùng là 0.
     */
    private BigDecimal estimateImportPricePerBase(Batch sourceBatch) {
        Product product = sourceBatch.getProductID();
        if (product != null && product.getProductID() != null) {
            Optional<BigDecimal> fromRealImport = batchRepository.findAllByProduct(product.getProductID()).stream()
                    .filter(candidate -> candidate.getPurchaseDetailID() != null)
                    .filter(candidate -> candidate.getImportPricePerBase() != null)
                    .max(Comparator.comparing(Batch::getImportDate,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .map(Batch::getImportPricePerBase);
            if (fromRealImport.isPresent()) {
                return fromRealImport.get();
            }
        }
        return resolveUnitCost(sourceBatch);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    /** Flips a linked count {@code Đã duyệt → Đã điều chỉnh}. No-op if it is not currently approved. */
    private void markStockReviewAdjusted(Stockreview count) {
        if (count == null) {
            return;
        }
        if (isStatus(count.getStatus(), REVIEW_STATUS_APPROVED)) {
            count.setStatus(REVIEW_STATUS_ADJUSTED);
            stockreviewRepository.save(count);
        }
    }

    /** Đảo lại {@link #markStockReviewAdjusted}: {@code Đã điều chỉnh → Đã duyệt} khi hủy phiếu. */
    private void revertStockReviewAdjusted(Stockreview count) {
        if (count == null) {
            return;
        }
        if (isStatus(count.getStatus(), REVIEW_STATUS_ADJUSTED)) {
            count.setStatus(REVIEW_STATUS_APPROVED);
            stockreviewRepository.save(count);
        }
    }

    /**
     * Thực hiện một phiếu {@link StockAdjustmentStatus#DRAFT}: chuyển sang
     * {@link StockAdjustmentStatus#COMPLETED} và áp tồn kho ngay. Không còn nhánh "gửi chờ duyệt" —
     * chỉ Owner tạo/thực hiện phiếu điều chỉnh kho.
     */
    @Transactional
    public void complete(Integer adjustmentId) {
        Stockadjustment adjustment = stockadjustmentRepository.findByIdWithRelations(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu điều chỉnh kho"));

        if (!isStatus(getStatusName(adjustment), StockAdjustmentStatus.DRAFT)) {
            throw new IllegalArgumentException("Chỉ có thể thực hiện phiếu đang ở trạng thái nháp");
        }

        adjustment.setStatus(StockAdjustmentStatus.COMPLETED);
        applyStockEffect(adjustment,
                stockadjustmentdetailRepository.findByStockOutIdWithRelations(adjustmentId));
        markStockReviewAdjusted(adjustment.getStockReviewID());
        // TODO(finance): auto-create an Expense (and link expenseID) for DESTROY with lineCost total > 0.
        //   Deferred — the Expense entity/vocabulary is owned by the finance module.

        stockadjustmentRepository.save(adjustment);
    }

    private String resolveCreatableType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return TYPE_DESTROY;
        }
        String type = rawType.trim().toUpperCase(Locale.ROOT);
        if (!CREATABLE_TYPES.contains(type)) {
            throw new IllegalArgumentException("Loại điều chỉnh không hợp lệ");
        }
        return type;
    }

    /**
     * Validate cho phiếu THỦ CÔNG (DESTROY/INTERNAL_USE/SAMPLE/GIFT). Lý do là BẮT BUỘC ở đây —
     * chỉ nguồn "theo phiếu rà soát kho" ({@code createFromStockReview}) mới được để trống và tự điền theo
     * mã phiếu rà soát kho.
     */
    private void validateRequest(StockAdjustmentCreateRequest request) {
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập lý do điều chỉnh");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một hàng hóa cần điều chỉnh");
        }
        for (StockAdjustmentItemRequest item : request.getItems()) {
            if (item.getBatchId() == null) {
                throw new IllegalArgumentException("Dữ liệu lô hàng không hợp lệ");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new IllegalArgumentException("Số lượng hủy phải lớn hơn 0");
            }
        }
    }

    private void validateSelectedBatches(List<Batch> selectedBatches,
                                         Map<Integer, StockAdjustmentItemRequest> itemMap,
                                         String adjustmentType) {
        for (Batch batch : selectedBatches) {
            StockAdjustmentItemRequest item = itemMap.get(batch.getId());

            if (!Boolean.TRUE.equals(batch.getStatus())) {
                throw new IllegalArgumentException("Lô hàng " + displayBatch(batch) + " không còn hoạt động");
            }
            // Hàng quá hạn chỉ được đi đường TIÊU HỦY. Với dùng nội bộ / hàng mẫu / quà tặng thì thuốc
            // vẫn tới tay người dùng thật nên chặn hẳn (DESTROY thì ngược lại: quá hạn chính là lý do lập phiếu).
            if (NO_EXPIRED_GOODS_TYPES.contains(adjustmentType) && isExpired(batch)) {
                throw new IllegalArgumentException("Lô hàng " + displayBatch(batch) + " đã hết hạn ngày "
                        + formatLocalDate(batch.getExpirationDate())
                        + " — chỉ được lập phiếu Hủy hàng cho lô này, không dùng nội bộ / làm mẫu / biếu tặng.");
            }
            if (batch.getStorageQuantity() == null || batch.getStorageQuantity() <= 0) {
                throw new IllegalArgumentException("Lô hàng " + displayBatch(batch) + " đã hết tồn kho");
            }
            if (item.getQuantity() > batch.getStorageQuantity()) {
                throw new IllegalArgumentException(
                        "Số lượng hủy của lô " + displayBatch(batch)
                                + " không được vượt quá tồn hiện tại: " + batch.getStorageQuantity());
            }
        }
    }

    // ------------------------------------------------------------------ mapping helpers

    private StockAdjustmentListItemResponse toListItem(Stockadjustment adjustment, List<Stockadjustmentdetail> details) {
        BigDecimal estimatedValue = details.stream()
                .map(Stockadjustmentdetail::getLineCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String statusName = getStatusName(adjustment);

        return new StockAdjustmentListItemResponse(
                adjustment.getId(),
                formatCode(adjustment.getId()),
                adjustment.getDate(),
                formatInstant(adjustment.getDate()),
                adjustment.getAdjustmentType(),
                formatAdjustmentType(adjustment.getAdjustmentType()),
                details.size(),
                estimatedValue,
                statusName,
                statusCssClass(statusName)
        );
    }

    private StockAdjustmentDetailItemResponse toDetailItem(Stockadjustmentdetail detail, boolean employeeLiable) {
        Product product = detail.getProductID();
        Productunit unit = detail.getProductUnitID();
        Batch batch = detail.getBatchID();

        // Đền bù tính theo GIÁ BÁN hiện hành của đơn vị đã xuất, KHÔNG phải giá vốn (sheet 01).
        // Đọc live từ productunit vì phiếu điều chỉnh không snapshot giá bán cho 2 loại thất thoát —
        // refSellPrice chỉ được ghi cho INTERNAL_USE/GIFT/SAMPLE nên 2 loại thất thoát không có sẵn.
        BigDecimal reimbursementUnitPrice = null;
        BigDecimal reimbursementValue = null;
        if (employeeLiable && isReimbursableLine(detail)) {
            reimbursementUnitPrice = unit != null && unit.getSellPrice() != null
                    ? unit.getSellPrice()
                    : BigDecimal.ZERO;
            int qty = detail.getQuantity() != null ? detail.getQuantity() : 0;
            reimbursementValue = reimbursementUnitPrice.multiply(BigDecimal.valueOf(qty));
        }

        return new StockAdjustmentDetailItemResponse(
                product != null ? product.getProductID() : null,
                product != null ? product.getName() : "Không rõ",
                batch != null ? batch.getLotNumber() : "",
                batch != null ? batch.getExpirationDate() : null,
                batch != null && batch.getExpirationDate() != null
                        ? batch.getExpirationDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                        : "",
                unit != null ? unit.getUnitName() : "",
                batch != null ? batch.getStorageQuantity() : null,
                detail.getQuantity(),
                detail.getDirection(),
                DIRECTION_IN.equals(detail.getDirection()) ? "Tăng" : "Giảm",
                detail.getUnitCostPrice(),
                detail.getLineCost(),
                detail.getNote(),
                detail.getRefSellPrice(),
                formatLocalDate(detail.getOldExpirationDate()),
                formatLocalDate(detail.getNewExpirationDate()),
                reimbursementUnitPrice,
                reimbursementValue
        );
    }

    private StockAdjustmentBatchCandidateResponse toCandidateResponse(Batch batch) {
        Product product = batch.getProductID();
        Productunit unit = resolveCandidateUnit(batch, product);

        return new StockAdjustmentBatchCandidateResponse(
                batch.getId(),
                product != null ? product.getProductID() : null,
                product != null ? product.getName() : "Không rõ",
                batch.getLotNumber(),
                batch.getExpirationDate(),
                formatLocalDate(batch.getExpirationDate()),
                isExpired(batch),
                isNearExpiry(batch),
                batch.getStorageQuantity(),
                unit != null ? unit.getId() : null,
                unit != null ? unit.getUnitName() : "Đơn vị",
                resolveUnitCost(batch),
                unit != null && unit.getSellPrice() != null ? unit.getSellPrice() : BigDecimal.ZERO
        );
    }

    /**
     * Snapshot GIÁ BÁN niêm yết của đơn vị đã xuất vào {@code refSellPrice} — chỉ INTERNAL_USE/GIFT/SAMPLE
     * (xem {@link #REF_SELL_PRICE_TYPES}); loại khác để null.
     *
     * <p>Phải là SNAPSHOT chứ không đọc live từ {@code Productunit.sellPrice}: hàng đã rời kho rồi thì
     * giá niêm yết đổi về sau không được làm đổi giá trị đã ghi nhận của phiếu.</p>
     */
    private void applyReferenceSellPrice(Stockadjustmentdetail detail, String adjustmentType, Productunit unit) {
        if (!REF_SELL_PRICE_TYPES.contains(adjustmentType)) {
            return;
        }
        detail.setRefSellPrice(unit != null && unit.getSellPrice() != null ? unit.getSellPrice() : BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------ unit / cost resolution

    private Productunit resolveUnit(Batch batch, Product product) {
        Productunit baseUnit = findBaseUnit(product).orElse(null);
        if (baseUnit != null) {
            return baseUnit;
        }
        if (batch.getImportUnitID() != null) {
            return batch.getImportUnitID();
        }
        Productunit preferredUnit = findPreferredUnit(product).orElse(null);
        if (preferredUnit != null) {
            return preferredUnit;
        }
        throw new IllegalArgumentException("Lô hàng " + displayBatch(batch) + " chưa có đơn vị tính");
    }

    private Productunit resolveCandidateUnit(Batch batch, Product product) {
        Productunit baseUnit = findBaseUnit(product).orElse(null);
        if (baseUnit != null) {
            return baseUnit;
        }
        if (batch.getImportUnitID() != null) {
            return batch.getImportUnitID();
        }
        return findPreferredUnit(product).orElse(null);
    }

    private BigDecimal resolveUnitCost(Batch batch) {
        if (batch.getImportPricePerBase() != null) {
            return batch.getImportPricePerBase();
        }
        if (batch.getImportPrice() != null) {
            return batch.getImportPrice();
        }
        return BigDecimal.ZERO;
    }

    private Optional<Productunit> findBaseUnit(Product product) {
        if (product == null || product.getProductID() == null) {
            return Optional.empty();
        }
        return productunitRepository.findByProductId(product.getProductID())
                .stream()
                .filter(unit -> !Boolean.FALSE.equals(unit.getIsActive()))
                .filter(unit -> Boolean.TRUE.equals(unit.getIsBaseUnit()))
                .findFirst();
    }

    private Optional<Productunit> findPreferredUnit(Product product) {
        if (product == null || product.getProductID() == null) {
            return Optional.empty();
        }
        return productunitRepository.findByProductId(product.getProductID())
                .stream()
                .filter(unit -> !Boolean.FALSE.equals(unit.getIsActive()))
                .sorted(Comparator
                        .comparingInt(this::unitPriority)
                        .thenComparing(unit -> unit.getId() == null ? Integer.MAX_VALUE : unit.getId()))
                .findFirst();
    }

    private int unitPriority(Productunit unit) {
        if (Boolean.TRUE.equals(unit.getIsBaseUnit())) {
            return 0;
        }
        if (Boolean.TRUE.equals(unit.getIsDefault())) {
            return 1;
        }
        return 2;
    }

    // ------------------------------------------------------------------ filtering / formatting

    private boolean matchesKeyword(Stockadjustment adjustment,
                                   List<Stockadjustmentdetail> details,
                                   String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        if (containsNormalized(formatCode(adjustment.getId()), normalizedKeyword)
                || containsNormalized(adjustment.getReason(), normalizedKeyword)
                || containsNormalized(formatAdjustmentType(adjustment.getAdjustmentType()), normalizedKeyword)
                || containsNormalized(getStatusName(adjustment), normalizedKeyword)) {
            return true;
        }
        return details.stream().anyMatch(detail -> {
            Product product = detail.getProductID();
            return product != null
                    && (containsNormalized(String.valueOf(product.getProductID()), normalizedKeyword)
                    || containsNormalized(product.getName(), normalizedKeyword)
                    || containsNormalized(product.getCode(), normalizedKeyword)
                    || containsNormalized(product.getBarcode(), normalizedKeyword));
        });
    }

    private boolean matchesBatchKeyword(Batch batch, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        Product product = batch.getProductID();
        return containsNormalized(batch.getLotNumber(), keyword)
                || product != null && (
                containsNormalized(String.valueOf(product.getProductID()), keyword)
                        || containsNormalized(product.getName(), keyword)
                        || containsNormalized(product.getCode(), keyword)
                        || containsNormalized(product.getBarcode(), keyword));
    }

    private boolean matchesDate(Stockadjustment adjustment, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        if (adjustment.getDate() == null) {
            return false;
        }
        LocalDate date = toLocalDate(adjustment.getDate());
        if (from != null && date.isBefore(from)) {
            return false;
        }
        return to == null || !date.isAfter(to);
    }

    private long countByStatusName(List<Stockadjustment> adjustments, String statusName) {
        return adjustments.stream()
                .filter(adj -> isStatus(getStatusName(adj), statusName))
                .count();
    }

    private String getStatusName(Stockadjustment adjustment) {
        return adjustment.getStatus() != null ? adjustment.getStatus() : "Không rõ";
    }

    private boolean isStatus(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    private String statusCssClass(String statusName) {
        // Dùng lại đúng bộ class có sẵn của màn (không thêm CSS mới): hoàn thành = xanh, hủy = đỏ.
        if (isStatus(statusName, StockAdjustmentStatus.COMPLETED)) {
            return "status-approved";
        }
        if (isStatus(statusName, StockAdjustmentStatus.CANCELLED)) {
            return "status-rejected";
        }
        if (isStatus(statusName, StockAdjustmentStatus.DRAFT)) {
            return "status-draft";
        }
        return "status-default";
    }

    /** Nối thêm 1 mẩu ghi chú vào {@code note} sẵn có (dùng cho lý do hủy — DB không có cột riêng). */
    private String appendNote(String existing, String addition) {
        if (addition == null || addition.isBlank()) {
            return existing;
        }
        return trimToNull(existing) == null ? addition.trim() : existing.trim() + " | " + addition.trim();
    }

    /** Trạng thái ghi nhận chi phí hợp lý — liên kết Expense chưa triển khai (TODO ở {@link #complete}). */
    private String costImpactDisplay(Stockadjustment adjustment) {
        return "Chưa ghi nhận chi phí";
    }

    private String formatAdjustmentType(String type) {
        if (type == null) {
            return "Không rõ";
        }
        return switch (type) {
            case TYPE_DESTROY -> "Hủy hàng (nguyên nhân khách quan)";
            case TYPE_DESTROY_EMPLOYEE_FAULT -> "Hủy hàng (lỗi nhân viên)";
            case "INTERNAL_USE" -> "Sử dụng nội bộ";
            case "SAMPLE" -> "Hàng mẫu";
            case "GIFT" -> "Quà tặng";
            case "COUNT_INCREASE" -> "Tăng theo rà soát kho";
            case "COUNT_DECREASE" -> "Giảm theo rà soát kho";
            default -> type;
        };
    }

    /** Lô đã quá hạn dùng tính tới hôm nay. Không có HSD (nullable) thì coi như chưa quá hạn. */
    private boolean isExpired(Batch batch) {
        LocalDate expiry = batch.getExpirationDate();
        return expiry != null && expiry.isBefore(LocalDate.now());
    }

    /** Còn hạn nhưng sắp hết — trong {@link #NEAR_EXPIRY_DAYS} ngày tới. */
    private boolean isNearExpiry(Batch batch) {
        LocalDate expiry = batch.getExpirationDate();
        if (expiry == null || expiry.isBefore(LocalDate.now())) {
            return false;
        }
        return !expiry.isAfter(LocalDate.now().plusDays(NEAR_EXPIRY_DAYS));
    }

    private String formatCode(Integer id) {
        if (id == null) {
            return "PDC-000000";
        }
        return "PDC-" + String.format("%06d", id);
    }

    /**
     * Mã tạm chỉ để qua ràng buộc {@code NOT NULL UNIQUE} lúc INSERT (chưa biết id nên chưa dựng được
     * mã thật). Không bao giờ commit ra ngoài: nó và {@link #assignCode} cùng một transaction.
     */
    private String temporaryCode() {
        return "TMP-" + UUID.randomUUID();
    }

    /**
     * Mã thật = {@code PDC-} + id do DB cấp. Sinh TỪ id chứ không phải {@code max(id)+1} vì cách cũ là
     * đọc-rồi-ghi: hai người tạo cùng lúc nhận cùng một số, người thứ hai ăn lỗi UNIQUE. Cách này còn
     * đảm bảo mã lưu trong DB luôn khớp {@link #formatCode(Integer)} mà màn hình hiển thị.
     */
    private Stockadjustment assignCode(Stockadjustment saved) {
        saved.setStockAdjustmentCode(formatCode(saved.getId()));
        return saved;
    }

    /**
     * Mã <em>dự kiến</em> cho màn tạo, CHỈ ĐỂ XEM — không giữ chỗ mã nào. Có thể lệch với mã thật nếu
     * có phiếu khác lưu chen vào lúc đang soạn, hoặc khi nguồn rà soát sinh 2 phiếu (hiện mã phiếu đầu).
     */
    @Transactional(readOnly = true)
    public String previewNextCode() {
        int nextId = stockadjustmentRepository.findAll().stream()
                .map(Stockadjustment::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        return formatCode(nextId);
    }

    private String displayBatch(Batch batch) {
        Product product = batch.getProductID();
        String productName = product != null ? product.getName() : "Không rõ sản phẩm";
        String lotNumber = batch.getLotNumber() != null ? batch.getLotNumber() : "Không có số lô";
        return productName + " - " + lotNumber;
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    private String formatLocalDate(LocalDate date) {
        if (date == null) {
            return "";
        }
        return date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private LocalDate toLocalDate(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value);
    }

    private boolean containsNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).contains(normalizedKeyword);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replace("Đ", "D").replace("đ", "d");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }
}
