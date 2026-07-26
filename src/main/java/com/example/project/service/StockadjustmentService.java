package com.example.project.service;

import com.example.project.constant.StockAdjustmentStatus;
import com.example.project.dto.request.StockAdjustmentCreateRequest;
import com.example.project.dto.request.StockAdjustmentItemRequest;
import com.example.project.dto.response.*;
import com.example.project.entity.*;
import com.example.project.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
 * <p>Adjustment types: {@code DESTROY / INTERNAL_USE / SAMPLE / GIFT} (outbound) plus
 * {@code COUNT_INCREASE / COUNT_DECREASE} which originate from a Stock Count (a later phase).
 * There is no {@code INTERNAL_TRANSFER} — the system is single-store.</p>
 */
@Service
public class StockadjustmentService {

    private static final String DIRECTION_IN = "IN";
    private static final String DIRECTION_OUT = "OUT";

    private static final String TYPE_COUNT_INCREASE = "COUNT_INCREASE";
    private static final String TYPE_COUNT_DECREASE = "COUNT_DECREASE";

    /**
     * Stock-count status strings we read/write. The Stock Count screen (another teammate) owns the
     * canonical spelling; we mirror only the two we need and match them accent-insensitively, so a
     * spelling difference is a one-line fix here.
     */
    private static final String COUNT_STATUS_APPROVED = "Đã duyệt";
    private static final String COUNT_STATUS_ADJUSTED = "Đã điều chỉnh";

    /** Adjustment types a user may pick when creating a slip by hand (COUNT_* comes from stock count). */
    private static final List<String> CREATABLE_TYPES = List.of("DESTROY", "INTERNAL_USE", "SAMPLE", "GIFT");

    /**
     * Chỉ 3/6 loại phải tính thuế GTGT ĐẦU RA theo giá bán: dùng nội bộ / biếu tặng / hàng mẫu.
     * DESTROY và COUNT_INCREASE/COUNT_DECREASE không phát sinh GTGT đầu ra → để 4 field thuế null.
     */
    private static final Set<String> VAT_OUTPUT_TYPES = Set.of("INTERNAL_USE", "GIFT", "SAMPLE");

    private final StockadjustmentRepository stockadjustmentRepository;
    private final StockadjustmentdetailRepository stockadjustmentdetailRepository;
    private final AccountRepository accountRepository;
    private final BatchRepository batchRepository;
    private final ProductunitRepository productunitRepository;
    // Stock Count is owned by another module — we consume it read-only via these bare repositories
    // (findAll / findById / save) and never add query methods to their files.
    private final StockcountRepository stockcountRepository;
    private final StockcountdetailRepository stockcountdetailRepository;

    public StockadjustmentService(StockadjustmentRepository stockadjustmentRepository,
                                  StockadjustmentdetailRepository stockadjustmentdetailRepository,
                                  AccountRepository accountRepository,
                                  BatchRepository batchRepository,
                                  ProductunitRepository productunitRepository,
                                  StockcountRepository stockcountRepository,
                                  StockcountdetailRepository stockcountdetailRepository) {
        this.stockadjustmentRepository = stockadjustmentRepository;
        this.stockadjustmentdetailRepository = stockadjustmentdetailRepository;
        this.accountRepository = accountRepository;
        this.batchRepository = batchRepository;
        this.productunitRepository = productunitRepository;
        this.stockcountRepository = stockcountRepository;
        this.stockcountdetailRepository = stockcountdetailRepository;
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
        long pendingCount = countByStatusName(adjustments, StockAdjustmentStatus.PENDING);
        long approvedCount = countByStatusName(adjustments, StockAdjustmentStatus.APPROVED);
        long rejectedCount = countByStatusName(adjustments, StockAdjustmentStatus.REJECTED);

        return new StockAdjustmentStatsResponse(monthlyCount, draftCount, pendingCount, approvedCount, rejectedCount);
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
        labels.put("DESTROY", "Hủy hàng");
        labels.put("INTERNAL_USE", "Sử dụng nội bộ");
        labels.put("SAMPLE", "Hàng mẫu");
        labels.put("GIFT", "Quà tặng");
        labels.put("COUNT_INCREASE", "Tăng theo kiểm kê");
        labels.put("COUNT_DECREASE", "Giảm theo kiểm kê");
        return labels;
    }

    // ------------------------------------------------------------------ detail

    @Transactional(readOnly = true)
    public StockAdjustmentDetailPageResponse getDetail(Integer adjustmentId) {
        Stockadjustment adjustment = stockadjustmentRepository.findByIdWithRelations(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu điều chỉnh kho"));

        List<Stockadjustmentdetail> details =
                stockadjustmentdetailRepository.findByStockOutIdWithRelations(adjustmentId);

        List<StockAdjustmentDetailItemResponse> itemResponses = details.stream()
                .map(this::toDetailItem)
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

        // Tổng thuế GTGT đầu ra của phiếu (chỉ INTERNAL_USE/GIFT/SAMPLE có; loại khác vatAmount null → 0).
        BigDecimal totalOutputVat = details.stream()
                .map(Stockadjustmentdetail::getVatAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String statusName = getStatusName(adjustment);

        return new StockAdjustmentDetailPageResponse(
                adjustment.getId(),
                formatCode(adjustment.getId()),
                adjustment.getDate(),
                formatInstant(adjustment.getDate()),
                adjustment.getAdjustmentType(),
                formatAdjustmentType(adjustment.getAdjustmentType()),
                adjustment.getCreatedBy() != null ? adjustment.getCreatedBy().getName() : "Không rõ",
                adjustment.getApprovedBy() != null ? adjustment.getApprovedBy().getName() : "Chưa có",
                formatInstant(adjustment.getApprovedAt()),
                adjustment.getReason(),
                adjustment.getNote(),
                statusName,
                statusCssClass(statusName),
                totalItems,
                totalQuantity,
                estimatedValue,
                costImpactDisplay(adjustment),
                itemResponses,
                VAT_OUTPUT_TYPES.contains(adjustment.getAdjustmentType()),
                totalOutputVat
        );
    }

    // ------------------------------------------------------------------ approve / reject

    /**
     * Owner approves a pending slip: records the approver, marks it {@code Duyệt} and applies the
     * stock movement.
     *
     * <p><strong>Đổi tồn kho.</strong> The stock adjustment is the step that actually moves
     * {@code batch.storageQuantity} — IN adds, OUT subtracts. This is where a post-count correction
     * is committed. (The stock <em>count</em> screen is the read-only step that does not move stock.)</p>
     */
    @Transactional
    public void approve(Integer adjustmentId, Integer approverAccountId) {
        Stockadjustment adjustment = stockadjustmentRepository.findByIdWithRelations(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu điều chỉnh kho"));

        if (!isStatus(getStatusName(adjustment), StockAdjustmentStatus.PENDING)) {
            throw new IllegalArgumentException("Chỉ có thể duyệt phiếu đang ở trạng thái chờ duyệt");
        }

        Account approver = accountRepository.findById(approverAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        adjustment.setStatus(StockAdjustmentStatus.APPROVED);
        adjustment.setApprovedBy(approver);
        adjustment.setApprovedAt(Instant.now());
        applyStockEffect(adjustment,
                stockadjustmentdetailRepository.findByStockOutIdWithRelations(adjustmentId));
        // A slip built from a stock count marks that count "Đã điều chỉnh" once approved.
        markStockCountAdjusted(adjustment.getStockCountID());
        // TODO(finance): auto-create an Expense (and link expenseID) for DESTROY with lineCost total > 0.
        //   Deferred — the Expense entity/vocabulary is owned by the finance module.

        stockadjustmentRepository.save(adjustment);
    }

    /**
     * Commits the slip's stock movement to each batch: {@code IN} adds the quantity, {@code OUT}
     * subtracts it (blocking negative stock). Called only when a slip reaches
     * {@link StockAdjustmentStatus#APPROVED} — the single point at which stock actually changes.
     */
    private void applyStockEffect(Stockadjustment adjustment, List<Stockadjustmentdetail> details) {
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
            batchRepository.save(batch);
        }
    }

    @Transactional
    public void reject(Integer adjustmentId, Integer approverAccountId) {
        Stockadjustment adjustment = stockadjustmentRepository.findByIdWithRelations(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu điều chỉnh kho"));

        if (!isStatus(getStatusName(adjustment), StockAdjustmentStatus.PENDING)) {
            throw new IllegalArgumentException("Chỉ có thể từ chối phiếu đang ở trạng thái chờ duyệt");
        }

        Account approver = accountRepository.findById(approverAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        adjustment.setStatus(StockAdjustmentStatus.REJECTED);
        adjustment.setApprovedBy(approver);
        adjustment.setApprovedAt(Instant.now());

        stockadjustmentRepository.save(adjustment);
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
     * Approved ("Đã duyệt") stock counts that can drive an adjustment: those with at least one
     * adjustable line and not already consumed by a non-rejected adjustment slip. Read-only —
     * consumes the Stock Count module through its bare repositories.
     */
    @Transactional(readOnly = true)
    public List<StockAdjustmentCountOptionResponse> listApprovedStockCounts() {
        Set<Integer> consumedCountIds = stockadjustmentRepository.findAllWithRelations().stream()
                .filter(adj -> adj.getStockCountID() != null && adj.getStockCountID().getId() != null)
                .filter(adj -> !isStatus(getStatusName(adj), StockAdjustmentStatus.REJECTED))
                .map(adj -> adj.getStockCountID().getId())
                .collect(Collectors.toSet());

        Map<Integer, List<Stockcountdetail>> detailsByCount = stockcountdetailRepository.findAll().stream()
                .filter(detail -> detail.getStockCountID() != null && detail.getStockCountID().getId() != null)
                .collect(Collectors.groupingBy(detail -> detail.getStockCountID().getId()));

        return stockcountRepository.findAll().stream()
                .filter(count -> isStatus(count.getStatus(), COUNT_STATUS_APPROVED))
                .filter(count -> !consumedCountIds.contains(count.getId()))
                .map(count -> new StockAdjustmentCountOptionResponse(
                        count.getId(),
                        count.getStockCountCode(),
                        formatInstant(count.getCountDate()),
                        (int) detailsByCount.getOrDefault(count.getId(), List.of()).stream()
                                .filter(this::isAdjustableCountDetail)
                                .count(),
                        count.getNote()))
                .filter(option -> option.getDiscrepancyLineCount() > 0)
                .sorted(Comparator.comparing(StockAdjustmentCountOptionResponse::getStockCountId,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * The prospective adjustment lines for one approved stock count: one per detail whose actual
     * quantity differs from the system quantity (and has a batch). Surplus → COUNT_INCREASE/IN,
     * shortage → COUNT_DECREASE/OUT. Read-only preview; the create flow rebuilds these server-side.
     */
    @Transactional(readOnly = true)
    public List<StockAdjustmentCountLineResponse> loadStockCountLines(Integer stockCountId) {
        Stockcount count = stockcountRepository.findById(stockCountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kiểm kê"));
        if (!isStatus(count.getStatus(), COUNT_STATUS_APPROVED)) {
            throw new IllegalArgumentException("Chỉ chọn được phiếu kiểm kê đã duyệt");
        }

        return stockcountdetailRepository.findAll().stream()
                .filter(detail -> detail.getStockCountID() != null
                        && stockCountId.equals(detail.getStockCountID().getId()))
                .filter(this::isAdjustableCountDetail)
                .map(this::toCountLine)
                .toList();
    }

    /** A detail is adjustable when it has a batch and a non-zero, non-null discrepancy. */
    private boolean isAdjustableCountDetail(Stockcountdetail detail) {
        if (detail.getBatchID() == null || detail.getBatchID().getId() == null) {
            return false;
        }
        Integer systemQty = detail.getSystemQty();
        Integer actualQty = detail.getActualQty();
        return systemQty != null && actualQty != null && !systemQty.equals(actualQty);
    }

    private StockAdjustmentCountLineResponse toCountLine(Stockcountdetail detail) {
        Batch batch = detail.getBatchID();
        Product product = batch.getProductID() != null ? batch.getProductID() : detail.getProductID();
        Productunit unit = resolveCandidateUnit(batch, product);

        int discrepancy = detail.getActualQty() - detail.getSystemQty();
        boolean surplus = discrepancy > 0;
        int quantity = Math.abs(discrepancy);

        BigDecimal unitCost = resolveUnitCost(batch);
        BigDecimal lineCost = unitCost.multiply(BigDecimal.valueOf(quantity));

        return new StockAdjustmentCountLineResponse(
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
                surplus ? TYPE_COUNT_INCREASE : TYPE_COUNT_DECREASE,
                surplus ? DIRECTION_IN : DIRECTION_OUT,
                unitCost,
                lineCost);
    }

    /**
     * Creates an adjustment slip. Two sources:
     * <ul>
     *   <li><b>MANUAL</b> — one slip of a {@link #CREATABLE_TYPES} type from the batches the user picked;</li>
     *   <li><b>STOCK_COUNT</b> — up to two slips ({@code COUNT_INCREASE} for surplus lines,
     *       {@code COUNT_DECREASE} for shortage lines) rebuilt server-side from an approved stock count.</li>
     * </ul>
     *
     * <p>Resulting status (both sources):
     * <ul>
     *   <li>{@code asDraft} → {@link StockAdjustmentStatus#DRAFT};</li>
     *   <li>otherwise the Owner ({@code isOwner}) auto-approves to {@link StockAdjustmentStatus#APPROVED};</li>
     *   <li>otherwise a Pharmacist submits to {@link StockAdjustmentStatus#PENDING} for approval.</li>
     * </ul>
     *
     * <p>No status change ever touches stock — the slip is a confirmation step only. Returns the id of
     * the (first) created slip so the caller can redirect to it.</p>
     */
    @Transactional
    public Integer createAdjustment(StockAdjustmentCreateRequest request,
                                    Integer currentAccountId,
                                    boolean isOwner,
                                    boolean asDraft) {
        if (isStockCountSource(request)) {
            return createFromStockCount(request, currentAccountId, isOwner, asDraft);
        }

        validateRequest(request);

        String adjustmentType = resolveCreatableType(request.getAdjustmentType());

        Account creator = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

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
        validateSelectedBatches(selectedBatches, itemMap);

        String status = asDraft
                ? StockAdjustmentStatus.DRAFT
                : (isOwner ? StockAdjustmentStatus.APPROVED : StockAdjustmentStatus.PENDING);
        boolean approvedNow = StockAdjustmentStatus.APPROVED.equals(status);

        Stockadjustment adjustment = new Stockadjustment();
        adjustment.setStockAdjustmentCode(generateCode());
        adjustment.setAdjustmentType(adjustmentType);
        adjustment.setDate(Instant.now());
        adjustment.setCreatedBy(creator);
        adjustment.setReason(request.getReason().trim());
        adjustment.setExpenseID(null);
        adjustment.setStatus(status);
        adjustment.setNote(trimToNull(request.getNote()));
        if (approvedNow) {
            adjustment.setApprovedBy(creator);
            adjustment.setApprovedAt(Instant.now());
        }

        Stockadjustment savedAdjustment = stockadjustmentRepository.save(adjustment);

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
            // Thuế GTGT đầu ra theo GIÁ BÁN — chỉ INTERNAL_USE/GIFT/SAMPLE; loại khác để null.
            applyOutputVat(detail, adjustmentType, unit, item.getQuantity(), product, item.getVatRate());

            savedDetails.add(stockadjustmentdetailRepository.save(detail));
        }

        if (approvedNow) {
            applyStockEffect(savedAdjustment, savedDetails);
        }

        return savedAdjustment.getId();
    }

    private boolean isStockCountSource(StockAdjustmentCreateRequest request) {
        return "STOCK_COUNT".equalsIgnoreCase(request.getSourceMode());
    }

    /**
     * STOCK_COUNT source: rebuild the COUNT lines from the chosen approved stock count and materialise
     * them into up to two slips — one all-{@code COUNT_INCREASE}, one all-{@code COUNT_DECREASE} — each
     * linked to the count. Client-posted lines are ignored (quantities are trusted only from the count).
     * If the slips are auto-approved (Owner), the count is flipped to {@code Đã điều chỉnh} here.
     */
    private Integer createFromStockCount(StockAdjustmentCreateRequest request,
                                         Integer currentAccountId,
                                         boolean isOwner,
                                         boolean asDraft) {
        if (request.getStockCountId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn phiếu kiểm kê");
        }

        Account creator = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        Stockcount count = stockcountRepository.findById(request.getStockCountId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kiểm kê"));
        if (!isStatus(count.getStatus(), COUNT_STATUS_APPROVED)) {
            throw new IllegalArgumentException("Phiếu kiểm kê không ở trạng thái Đã duyệt");
        }

        // Reason is optional for a count-sourced slip: auto-fill it from the count when left blank.
        String reason = (request.getReason() != null && !request.getReason().isBlank())
                ? request.getReason().trim()
                : "Điều chỉnh tồn kho theo chênh lệch kiểm kê "
                    + (count.getStockCountCode() != null ? count.getStockCountCode() : "");

        boolean alreadyConsumed = stockadjustmentRepository.findAllWithRelations().stream()
                .anyMatch(adj -> adj.getStockCountID() != null
                        && request.getStockCountId().equals(adj.getStockCountID().getId())
                        && !isStatus(getStatusName(adj), StockAdjustmentStatus.REJECTED));
        if (alreadyConsumed) {
            throw new IllegalArgumentException("Phiếu kiểm kê này đã có phiếu điều chỉnh");
        }

        List<StockAdjustmentCountLineResponse> lines = loadStockCountLines(request.getStockCountId());
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Phiếu kiểm kê không có dòng chênh lệch để điều chỉnh");
        }

        List<StockAdjustmentCountLineResponse> increaseLines = lines.stream()
                .filter(line -> TYPE_COUNT_INCREASE.equals(line.getAdjustmentType()))
                .toList();
        List<StockAdjustmentCountLineResponse> decreaseLines = lines.stream()
                .filter(line -> TYPE_COUNT_DECREASE.equals(line.getAdjustmentType()))
                .toList();

        String status = asDraft
                ? StockAdjustmentStatus.DRAFT
                : (isOwner ? StockAdjustmentStatus.APPROVED : StockAdjustmentStatus.PENDING);

        Integer firstId = null;
        if (!increaseLines.isEmpty()) {
            firstId = persistCountSlip(TYPE_COUNT_INCREASE, increaseLines, count, creator, status, reason, request);
        }
        if (!decreaseLines.isEmpty()) {
            Integer id = persistCountSlip(TYPE_COUNT_DECREASE, decreaseLines, count, creator, status, reason, request);
            firstId = firstId != null ? firstId : id;
        }

        if (StockAdjustmentStatus.APPROVED.equals(status)) {
            markStockCountAdjusted(count);
        }
        return firstId;
    }

    private Integer persistCountSlip(String adjustmentType,
                                     List<StockAdjustmentCountLineResponse> lines,
                                     Stockcount count,
                                     Account creator,
                                     String status,
                                     String reason,
                                     StockAdjustmentCreateRequest request) {
        boolean approvedNow = StockAdjustmentStatus.APPROVED.equals(status);

        Stockadjustment adjustment = new Stockadjustment();
        adjustment.setStockAdjustmentCode(generateCode());
        adjustment.setAdjustmentType(adjustmentType);
        adjustment.setDate(Instant.now());
        adjustment.setCreatedBy(creator);
        adjustment.setReason(reason);
        adjustment.setStockCountID(count);
        adjustment.setExpenseID(null);
        adjustment.setStatus(status);
        adjustment.setNote(trimToNull(request.getNote()));
        if (approvedNow) {
            adjustment.setApprovedBy(creator);
            adjustment.setApprovedAt(Instant.now());
        }

        Stockadjustment savedAdjustment = stockadjustmentRepository.save(adjustment);

        List<Stockadjustmentdetail> savedDetails = new ArrayList<>();
        for (StockAdjustmentCountLineResponse line : lines) {
            Batch batch = batchRepository.findById(line.getBatchId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lô hàng của phiếu kiểm kê"));
            Product product = batch.getProductID();
            Productunit unit = resolveUnit(batch, product);

            Stockadjustmentdetail detail = new Stockadjustmentdetail();
            detail.setStockAdjustmentID(savedAdjustment);
            detail.setProductID(product);
            detail.setProductUnitID(unit);
            detail.setBatchID(batch);
            detail.setDirection(line.getDirection());
            detail.setQuantity(line.getQuantity());
            detail.setBaseQtyDeducted(line.getQuantity());
            detail.setUnitCostPrice(line.getUnitCostPrice());
            detail.setLineCost(line.getLineCost());
            detail.setNote(null);

            savedDetails.add(stockadjustmentdetailRepository.save(detail));
        }

        if (approvedNow) {
            applyStockEffect(savedAdjustment, savedDetails);
        }
        return savedAdjustment.getId();
    }

    /** Flips a linked count {@code Đã duyệt → Đã điều chỉnh}. No-op if it is not currently approved. */
    private void markStockCountAdjusted(Stockcount count) {
        if (count == null) {
            return;
        }
        if (isStatus(count.getStatus(), COUNT_STATUS_APPROVED)) {
            count.setStatus(COUNT_STATUS_ADJUSTED);
            stockcountRepository.save(count);
        }
    }

    /**
     * Sends a {@link StockAdjustmentStatus#DRAFT} slip forward: the Owner auto-approves it (which
     * applies the stock movement), a Pharmacist moves it to {@link StockAdjustmentStatus#PENDING}.
     */
    @Transactional
    public void submit(Integer adjustmentId, Integer currentAccountId, boolean isOwner) {
        Stockadjustment adjustment = stockadjustmentRepository.findByIdWithRelations(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu điều chỉnh kho"));

        if (!isStatus(getStatusName(adjustment), StockAdjustmentStatus.DRAFT)) {
            throw new IllegalArgumentException("Chỉ có thể gửi duyệt phiếu đang ở trạng thái nháp");
        }

        Account actor = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        if (isOwner) {
            adjustment.setStatus(StockAdjustmentStatus.APPROVED);
            adjustment.setApprovedBy(actor);
            adjustment.setApprovedAt(Instant.now());
            applyStockEffect(adjustment,
                    stockadjustmentdetailRepository.findByStockOutIdWithRelations(adjustmentId));
            markStockCountAdjusted(adjustment.getStockCountID());
        } else {
            adjustment.setStatus(StockAdjustmentStatus.PENDING);
        }

        stockadjustmentRepository.save(adjustment);
    }

    private String resolveCreatableType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "DESTROY";
        }
        String type = rawType.trim().toUpperCase(Locale.ROOT);
        if (!CREATABLE_TYPES.contains(type)) {
            throw new IllegalArgumentException("Loại điều chỉnh không hợp lệ");
        }
        return type;
    }

    private void validateRequest(StockAdjustmentCreateRequest request) {
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập lý do hủy");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một hàng hóa cần hủy");
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
                                         Map<Integer, StockAdjustmentItemRequest> itemMap) {
        for (Batch batch : selectedBatches) {
            StockAdjustmentItemRequest item = itemMap.get(batch.getId());

            if (!Boolean.TRUE.equals(batch.getStatus())) {
                throw new IllegalArgumentException("Lô hàng " + displayBatch(batch) + " không còn hoạt động");
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
                adjustment.getCreatedBy() != null ? adjustment.getCreatedBy().getName() : "Không rõ",
                details.size(),
                estimatedValue,
                statusName,
                statusCssClass(statusName)
        );
    }

    private StockAdjustmentDetailItemResponse toDetailItem(Stockadjustmentdetail detail) {
        Product product = detail.getProductID();
        Productunit unit = detail.getProductUnitID();
        Batch batch = detail.getBatchID();

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
                detail.getVatRate(),
                detail.getPreTaxAmount(),
                detail.getVatAmount()
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
                batch.getStorageQuantity(),
                unit != null ? unit.getId() : null,
                unit != null ? unit.getUnitName() : "Đơn vị",
                resolveUnitCost(batch),
                unit != null && unit.getSellPrice() != null ? unit.getSellPrice() : BigDecimal.ZERO,
                resolveVatRateSnapshot(product)
        );
    }

    /**
     * Điền thuế GTGT đầu ra cho dòng điều chỉnh — CHỈ áp dụng INTERNAL_USE/GIFT/SAMPLE, tính theo
     * GIÁ BÁN (không phải giá vốn). Người lập tự nhập {@code vatRate} (0 nếu KM đã đăng ký); mặc định
     * = thuế suất thường của sản phẩm. Loại DESTROY/COUNT_* để 4 field null (không phát sinh GTGT đầu ra).
     * refSellPrice = giá bán/đơn vị (đã gồm VAT); tách net/thuế nhất quán với InvoiceDetail.
     */
    private void applyOutputVat(Stockadjustmentdetail detail, String adjustmentType,
                                Productunit unit, int quantity, Product product, BigDecimal requestedVatRate) {
        if (!VAT_OUTPUT_TYPES.contains(adjustmentType)) {
            return;
        }
        BigDecimal sellPrice = unit != null && unit.getSellPrice() != null ? unit.getSellPrice() : BigDecimal.ZERO;
        BigDecimal vatRate = requestedVatRate != null ? requestedVatRate : resolveVatRateSnapshot(product);
        if (vatRate.compareTo(BigDecimal.ZERO) < 0) {
            vatRate = BigDecimal.ZERO;
        }
        BigDecimal grossValue = sellPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal preTax;
        BigDecimal vat;
        if (vatRate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal divisor = BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
            preTax = grossValue.divide(divisor, 2, RoundingMode.HALF_UP);
            vat = grossValue.subtract(preTax);
        } else {
            preTax = grossValue;
            vat = BigDecimal.ZERO;
        }
        detail.setRefSellPrice(sellPrice);
        detail.setVatRate(vatRate);
        detail.setPreTaxAmount(preTax);
        detail.setVatAmount(vat);
    }

    /**
     * Thuế suất GTGT thường của sản phẩm (mirror {@code InvoiceService}): {@code Product.vatRateOverride}
     * nếu có, ngược lại {@code Type.defaultVATRate}, mặc định 0.
     */
    private BigDecimal resolveVatRateSnapshot(Product product) {
        if (product == null) {
            return BigDecimal.ZERO;
        }
        if (product.getVatRateOverride() != null) {
            return product.getVatRateOverride();
        }
        Type type = product.getTypeID();
        if (type != null && type.getDefaultVATRate() != null) {
            return type.getDefaultVATRate();
        }
        return BigDecimal.ZERO;
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
                || containsNormalized(getStatusName(adjustment), normalizedKeyword)
                || containsNormalized(adjustment.getCreatedBy() != null ? adjustment.getCreatedBy().getName() : null, normalizedKeyword)) {
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
        // Exact matches only: "Chờ duyệt" contains "duyệt", so a substring test would misclassify it.
        if (isStatus(statusName, StockAdjustmentStatus.APPROVED)) {
            return "status-approved";
        }
        if (isStatus(statusName, StockAdjustmentStatus.REJECTED)) {
            return "status-rejected";
        }
        if (isStatus(statusName, StockAdjustmentStatus.PENDING)) {
            return "status-pending";
        }
        if (isStatus(statusName, StockAdjustmentStatus.DRAFT)) {
            return "status-draft";
        }
        return "status-default";
    }

    private String costImpactDisplay(Stockadjustment adjustment) {
        if (adjustment.getExpenseID() != null) {
            return "Có ghi nhận chi phí";
        }
        return "Chưa ghi nhận chi phí";
    }

    private String formatAdjustmentType(String type) {
        if (type == null) {
            return "Không rõ";
        }
        return switch (type) {
            case "DESTROY" -> "Hủy hàng";
            case "INTERNAL_USE" -> "Sử dụng nội bộ";
            case "SAMPLE" -> "Hàng mẫu";
            case "GIFT" -> "Quà tặng";
            case "COUNT_INCREASE" -> "Tăng theo kiểm kê";
            case "COUNT_DECREASE" -> "Giảm theo kiểm kê";
            default -> type;
        };
    }

    private String formatCode(Integer id) {
        if (id == null) {
            return "PDC-000000";
        }
        return "PDC-" + String.format("%06d", id);
    }

    private String generateCode() {
        int nextId = stockadjustmentRepository.findAll().stream()
                .map(Stockadjustment::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        return "PDC-" + String.format("%06d", nextId);
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
