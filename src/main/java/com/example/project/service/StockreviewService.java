package com.example.project.service;

import com.example.project.constant.StockReviewCondition;
import com.example.project.constant.StockReviewStatus;
import com.example.project.constant.StockReviewType;
import com.example.project.dto.request.StockReviewCreateRequest;
import com.example.project.dto.request.StockReviewItemRequest;
import com.example.project.dto.response.StockReviewBatchCandidateResponse;
import com.example.project.dto.response.StockReviewDetailItemResponse;
import com.example.project.dto.response.StockReviewDetailPageResponse;
import com.example.project.dto.response.StockReviewListItemResponse;
import com.example.project.dto.response.StockReviewPrintLineResponse;
import com.example.project.dto.response.StockReviewPrintPageResponse;
import com.example.project.dto.response.StockReviewStatsResponse;
import com.example.project.dto.response.StockReviewVoucherPrintLineResponse;
import com.example.project.dto.response.StockReviewVoucherPrintPageResponse;
import com.example.project.entity.Account;
import com.example.project.entity.Batch;
import com.example.project.entity.Product;
import com.example.project.entity.Stockreview;
import com.example.project.entity.Stockreviewdetail;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.BatchRepository;
import com.example.project.repository.StockreviewRepository;
import com.example.project.repository.StockreviewdetailRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StockreviewService {

    private final StockreviewRepository stockreviewRepository;
    private final StockreviewdetailRepository stockreviewdetailRepository;
    private final BatchRepository batchRepository;
    private final AccountRepository accountRepository;
    private final WorkflowNotificationService workflowNotificationService;

    public StockreviewService(
            StockreviewRepository stockreviewRepository,
            StockreviewdetailRepository stockreviewdetailRepository,
            BatchRepository batchRepository,
            AccountRepository accountRepository,
            WorkflowNotificationService workflowNotificationService
    ) {
        this.stockreviewRepository = stockreviewRepository;
        this.stockreviewdetailRepository = stockreviewdetailRepository;
        this.batchRepository = batchRepository;
        this.accountRepository = accountRepository;
        this.workflowNotificationService = workflowNotificationService;
    }

    @Transactional(readOnly = true)
    public Page<StockReviewListItemResponse> search(
            String keyword,
            String fromDate,
            String toDate,
            String type,
            String status,
            Pageable pageable
    ) {
        String normalizedKeyword = normalize(keyword);
        LocalDate from = parseDate(fromDate);
        LocalDate to = parseDate(toDate);

        List<Stockreview> reviews =
                stockreviewRepository.findAllWithRelations();

        Map<Integer, List<Stockreviewdetail>> detailMap =
                stockreviewdetailRepository.findAllWithRelations()
                        .stream()
                        .filter(detail ->
                                detail.getStockReviewID() != null
                                        && detail.getStockReviewID().getId() != null
                        )
                        .collect(Collectors.groupingBy(
                                detail ->
                                        detail.getStockReviewID().getId()
                        ));

        List<StockReviewListItemResponse> rows =
                reviews.stream()
                        .filter(review -> matchesKeyword(
                                review,
                                detailMap.getOrDefault(
                                        review.getId(),
                                        List.of()
                                ),
                                normalizedKeyword
                        ))
                        .filter(review -> matchesDate(
                                review,
                                from,
                                to
                        ))
                        .filter(review ->
                                type == null
                                        || type.isBlank()
                                        || StockReviewType.normalize(type)
                                        .equals(
                                                StockReviewType.normalize(
                                                        review.getType()
                                                )
                                        )
                        )
                        .filter(review ->
                                status == null
                                        || status.isBlank()
                                        || isStatus(
                                        review.getStatus(),
                                        status
                                )
                        )
                        .map(review -> toListItem(
                                review,
                                detailMap.getOrDefault(
                                        review.getId(),
                                        List.of()
                                )
                        ))
                        .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(
                start + pageable.getPageSize(),
                rows.size()
        );

        List<StockReviewListItemResponse> content =
                start >= rows.size()
                        ? List.of()
                        : rows.subList(start, end);

        return new PageImpl<>(
                content,
                pageable,
                rows.size()
        );
    }

    @Transactional(readOnly = true)
    public StockReviewStatsResponse getStats() {
        List<Stockreview> reviews =
                stockreviewRepository.findAllWithRelations();

        return new StockReviewStatsResponse(
                reviews.size(),
                countByType(reviews, StockReviewType.COUNT),
                countByType(reviews, StockReviewType.DATE),
                countByType(reviews, StockReviewType.CONDITION),
                countByStatus(reviews, StockReviewStatus.DRAFT),
                countByStatus(reviews, StockReviewStatus.PENDING),
                countByStatus(reviews, StockReviewStatus.APPROVED),
                countByStatus(reviews, StockReviewStatus.ADJUSTED)
        );
    }

    public List<String> listStatuses() {
        return StockReviewStatus.ALL;
    }

    public Map<String, String> typeLabels() {
        return StockReviewType.labels();
    }

    public Map<String, String> conditionLabels() {
        return StockReviewCondition.labels();
    }

    @Transactional(readOnly = true)
    public StockReviewCreateRequest buildDefaultForm() {
        StockReviewCreateRequest form =
                new StockReviewCreateRequest();

        form.setType(StockReviewType.COUNT);

        for (StockReviewBatchCandidateResponse batch
                : listReviewableBatches(null)) {

            StockReviewItemRequest item =
                    new StockReviewItemRequest();

            item.setBatchId(batch.getBatchId());
            item.setActualQty(batch.getSystemQty());
            item.setActualExpirationDate(
                    batch.getExpirationDate()
            );
            item.setConditionStatus(
                    StockReviewCondition.GOOD
            );

            form.getItems().add(item);
        }

        return form;
    }

    @Transactional(readOnly = true)
    public List<StockReviewBatchCandidateResponse>
    listReviewableBatches(String keyword) {

        String normalizedKeyword = normalize(keyword);

        return batchRepository.findAvailableBatchesForDestroy()
                .stream()
                .filter(batch -> matchesBatchKeyword(
                        batch,
                        normalizedKeyword
                ))
                .map(this::toBatchCandidate)
                .toList();
    }

    /**
     * Lưu một trong ba loại Stock Review mà không thay đổi entity
     * hoặc cấu trúc database hiện có.
     *
     * Với DATE và CONDITION, systemQty và actualQty vẫn được lưu
     * bằng nhau để đáp ứng ràng buộc NOT NULL của database.
     */
    @Transactional
    public Integer create(
            StockReviewCreateRequest request,
            Integer currentAccountId,
            boolean isOwner,
            boolean asDraft
    ) {
        String reviewType =
                validateCreateRequest(request);

        Account creator = accountRepository
                .findById(currentAccountId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy tài khoản hiện tại"
                        )
                );

        Map<Integer, StockReviewItemRequest> itemMap =
                new LinkedHashMap<>();

        for (StockReviewItemRequest item
                : request.getItems()) {

            if (itemMap.putIfAbsent(
                    item.getBatchId(),
                    item
            ) != null) {
                throw new IllegalArgumentException(
                        "Một lô hàng không được xuất hiện nhiều lần "
                                + "trong phiếu rà soát"
                );
            }
        }

        Map<Integer, Batch> batchMap =
                batchRepository
                        .findAllById(itemMap.keySet())
                        .stream()
                        .collect(Collectors.toMap(
                                Batch::getId,
                                Function.identity()
                        ));

        if (batchMap.size() != itemMap.size()) {
            throw new IllegalArgumentException(
                    "Một số lô hàng không tồn tại"
            );
        }

        String status = resolveCreateStatus(
                isOwner,
                asDraft
        );

        boolean approvedNow = isStatus(
                status,
                StockReviewStatus.APPROVED
        );

        Stockreview review = new Stockreview();

        /*
         * Entity vẫn giữ getter/setter tên stockCountCode.
         * Không sửa entity vì đây là phần core hiện có.
         *
         * Mã tạm bảo đảm UNIQUE trong lần INSERT đầu tiên.
         * Sau khi database cấp ID, mã được đổi thành SR-{ID}.
         */
        review.setStockCountCode(temporaryCode());
        review.setType(reviewType);
        review.setReviewDate(Instant.now());
        review.setCreatedBy(creator);
        review.setStatus(status);
        review.setNote(trimToNull(request.getNote()));

        if (approvedNow) {
            review.setApprovedBy(creator);
            review.setApprovedAt(Instant.now());
        }

        Stockreview saved =
                stockreviewRepository.save(review);

        saved.setStockCountCode(
                formatReviewCode(saved.getId())
        );

        for (Map.Entry<Integer, StockReviewItemRequest> entry
                : itemMap.entrySet()) {

            Batch batch =
                    batchMap.get(entry.getKey());

            StockReviewItemRequest item =
                    entry.getValue();

            int systemQty =
                    safe(batch.getStorageQuantity());

            Stockreviewdetail detail =
                    new Stockreviewdetail();

            detail.setStockReviewID(saved);
            detail.setProductID(batch.getProductID());
            detail.setBatchID(batch);
            detail.setSystemQty(systemQty);
            detail.setNote(
                    trimToNull(item.getNote())
            );

            if (StockReviewType.COUNT.equals(reviewType)) {
                int actualQty = item.getActualQty();

                detail.setActualQty(actualQty);
                detail.setDiscrepancy(
                        actualQty - systemQty
                );
            } else {
                /*
                 * Giữ đúng ràng buộc NOT NULL của database
                 * đối với DATE và CONDITION.
                 */
                detail.setActualQty(systemQty);
                detail.setDiscrepancy(0);
            }

            if (StockReviewType.DATE.equals(reviewType)) {
                detail.setRecordedExpirationDate(
                        batch.getExpirationDate()
                );
                detail.setActualExpirationDate(
                        item.getActualExpirationDate()
                );
            }

            if (StockReviewType.CONDITION.equals(reviewType)) {
                detail.setConditionStatus(
                        StockReviewCondition.normalize(
                                item.getConditionStatus()
                        )
                );
            }

            stockreviewdetailRepository.save(detail);
        }

        if (isStatus(
                saved.getStatus(),
                StockReviewStatus.PENDING
        )) {
            workflowNotificationService
                    .stockReviewPending(saved);
        }

        return saved.getId();
    }

    private String resolveCreateStatus(
            boolean isOwner,
            boolean asDraft
    ) {
        if (asDraft) {
            return StockReviewStatus.DRAFT;
        }

        return isOwner
                ? StockReviewStatus.APPROVED
                : StockReviewStatus.PENDING;
    }

    @Transactional(readOnly = true)
    public StockReviewDetailPageResponse getDetail(
            Integer stockReviewId
    ) {
        Stockreview review = stockreviewRepository
                .findByIdWithRelations(stockReviewId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy phiếu rà soát kho"
                        )
                );

        String type =
                StockReviewType.normalize(review.getType());

        List<Stockreviewdetail> details =
                stockreviewdetailRepository
                        .findByStockReviewIdWithRelations(
                                stockReviewId
                        );

        List<StockReviewDetailItemResponse> items =
                details.stream()
                        .map(detail ->
                                toDetailItem(detail, type)
                        )
                        .toList();

        long issueItems = details.stream()
                .filter(detail ->
                        isIssue(detail, type)
                )
                .count();

        int totalSystemQty = details.stream()
                .map(Stockreviewdetail::getSystemQty)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        int totalActualQty = details.stream()
                .map(Stockreviewdetail::getActualQty)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        return new StockReviewDetailPageResponse(
                review.getId(),
                review.getStockCountCode(),
                review.getReviewDate(),
                formatInstant(review.getReviewDate()),
                type,
                StockReviewType.label(type),
                review.getCreatedBy() != null
                        ? review.getCreatedBy().getName()
                        : "Không rõ",
                review.getApprovedBy() != null
                        ? review.getApprovedBy().getName()
                        : "Chưa có",
                formatInstant(review.getApprovedAt()),
                review.getStatus(),
                statusCssClass(review.getStatus()),
                review.getNote(),
                details.size(),
                details.size() - issueItems,
                issueItems,
                totalSystemQty,
                totalActualQty,
                totalActualQty - totalSystemQty,
                items
        );
    }

    @Transactional
    public void submit(
            Integer stockReviewId,
            Integer currentAccountId,
            boolean isOwner
    ) {
        Stockreview review = stockreviewRepository
                .findByIdWithRelations(stockReviewId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy phiếu rà soát kho"
                        )
                );

        if (!isStatus(
                review.getStatus(),
                StockReviewStatus.DRAFT
        )) {
            throw new IllegalArgumentException(
                    "Chỉ có thể gửi phiếu rà soát kho "
                            + "đang ở trạng thái nháp"
            );
        }

        Account actor = accountRepository
                .findById(currentAccountId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy tài khoản hiện tại"
                        )
                );

        if (isOwner) {
            review.setStatus(
                    StockReviewStatus.APPROVED
            );
            review.setApprovedBy(actor);
            review.setApprovedAt(Instant.now());
        } else {
            review.setStatus(
                    StockReviewStatus.PENDING
            );
        }

        stockreviewRepository.save(review);

        if (!isOwner) {
            workflowNotificationService
                    .stockReviewPending(review);
        }
    }

    @Transactional
    public void approve(
            Integer stockReviewId,
            Integer ownerAccountId
    ) {
        Stockreview review = stockreviewRepository
                .findByIdWithRelations(stockReviewId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy phiếu rà soát kho"
                        )
                );

        if (!isStatus(
                review.getStatus(),
                StockReviewStatus.PENDING
        )) {
            throw new IllegalArgumentException(
                    "Chỉ có thể duyệt phiếu rà soát kho "
                            + "đang chờ duyệt"
            );
        }

        Account owner = accountRepository
                .findById(ownerAccountId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy tài khoản hiện tại"
                        )
                );

        review.setStatus(StockReviewStatus.APPROVED);
        review.setApprovedBy(owner);
        review.setApprovedAt(Instant.now());

        stockreviewRepository.save(review);

        workflowNotificationService
                .stockReviewApproved(review);
    }

    @Transactional
    public void reject(
            Integer stockReviewId,
            Integer ownerAccountId
    ) {
        Stockreview review = stockreviewRepository
                .findByIdWithRelations(stockReviewId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy phiếu rà soát kho"
                        )
                );

        if (!isStatus(
                review.getStatus(),
                StockReviewStatus.PENDING
        )) {
            throw new IllegalArgumentException(
                    "Chỉ có thể từ chối phiếu rà soát kho "
                            + "đang chờ duyệt"
            );
        }

        Account owner = accountRepository
                .findById(ownerAccountId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy tài khoản hiện tại"
                        )
                );

        review.setStatus(StockReviewStatus.REJECTED);
        review.setApprovedBy(owner);
        review.setApprovedAt(Instant.now());

        stockreviewRepository.save(review);

        workflowNotificationService
                .stockReviewRejected(review);
    }

    @Transactional(readOnly = true)
    public StockReviewPrintPageResponse getPrintPage(
            String printedByName,
            String requestedType
    ) {
        String type =
                StockReviewType.normalize(requestedType);

        if (!StockReviewType.isValid(type)) {
            throw new IllegalArgumentException(
                    "Loại rà soát kho không hợp lệ"
            );
        }

        List<StockReviewPrintLineResponse> lines =
                batchRepository
                        .findAvailableBatchesForDestroy()
                        .stream()
                        .map(batch -> {
                            Product product =
                                    batch.getProductID();

                            return new StockReviewPrintLineResponse(
                                    product != null
                                            ? product.getProductID()
                                            : null,
                                    product != null
                                            ? product.getCode()
                                            : "",
                                    product != null
                                            ? product.getName()
                                            : "Không rõ",
                                    batch.getLotNumber(),
                                    formatLocalDate(
                                            batch.getExpirationDate()
                                    ),
                                    batch.getStorageQuantity()
                            );
                        })
                        .toList();

        return new StockReviewPrintPageResponse(
                LocalDate.now().format(
                        DateTimeFormatter.ofPattern(
                                "dd/MM/yyyy"
                        )
                ),
                printedByName,
                type,
                StockReviewType.label(type),
                lines.size(),
                lines
        );
    }

    @Transactional(readOnly = true)
    public StockReviewVoucherPrintPageResponse
    getVoucherPrintPage(Integer stockReviewId) {

        Stockreview review = stockreviewRepository
                .findByIdWithRelations(stockReviewId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy phiếu rà soát kho"
                        )
                );

        String type =
                StockReviewType.normalize(review.getType());

        List<Stockreviewdetail> details =
                stockreviewdetailRepository
                        .findByStockReviewIdWithRelations(
                                stockReviewId
                        );

        List<StockReviewVoucherPrintLineResponse> items =
                details.stream()
                        .map(detail ->
                                toVoucherPrintLine(
                                        detail,
                                        type
                                )
                        )
                        .toList();

        int totalSystemQty = details.stream()
                .map(Stockreviewdetail::getSystemQty)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        int totalActualQty = details.stream()
                .map(Stockreviewdetail::getActualQty)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        int totalExcessQty = items.stream()
                .map(
                        StockReviewVoucherPrintLineResponse
                                ::getDiscrepancy
                )
                .filter(Objects::nonNull)
                .filter(value -> value > 0)
                .mapToInt(Integer::intValue)
                .sum();

        int totalShortageQty = items.stream()
                .map(
                        StockReviewVoucherPrintLineResponse
                                ::getDiscrepancy
                )
                .filter(Objects::nonNull)
                .filter(value -> value < 0)
                .mapToInt(value -> Math.abs(value))
                .sum();

        BigDecimal totalDiscrepancyValue =
                items.stream()
                        .map(
                                StockReviewVoucherPrintLineResponse
                                        ::getDiscrepancyValue
                        )
                        .filter(Objects::nonNull)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        long issueItems = details.stream()
                .filter(detail ->
                        isIssue(detail, type)
                )
                .count();

        return new StockReviewVoucherPrintPageResponse(
                review.getId(),
                review.getStockCountCode(),
                formatInstant(review.getReviewDate()),
                type,
                StockReviewType.label(type),
                review.getStatus(),
                review.getCreatedBy() != null
                        ? review.getCreatedBy().getName()
                        : "Không rõ",
                review.getApprovedBy() != null
                        ? review.getApprovedBy().getName()
                        : "Chưa có",
                review.getApprovedAt() != null
                        ? formatInstant(review.getApprovedAt())
                        : "Chưa có",
                review.getNote(),
                items.size(),
                issueItems,
                totalSystemQty,
                totalActualQty,
                totalExcessQty,
                totalShortageQty,
                totalActualQty - totalSystemQty,
                totalDiscrepancyValue,
                items
        );
    }

    private StockReviewListItemResponse toListItem(
            Stockreview review,
            List<Stockreviewdetail> details
    ) {
        String type =
                StockReviewType.normalize(review.getType());

        long issueItems = details.stream()
                .filter(detail ->
                        isIssue(detail, type)
                )
                .count();

        return new StockReviewListItemResponse(
                review.getId(),
                review.getStockCountCode(),
                review.getReviewDate(),
                formatInstant(review.getReviewDate()),
                type,
                StockReviewType.label(type),
                review.getCreatedBy() != null
                        ? review.getCreatedBy().getName()
                        : "Không rõ",
                review.getApprovedBy() != null
                        ? review.getApprovedBy().getName()
                        : "Chưa có",
                formatInstant(review.getApprovedAt()),
                details.size(),
                issueItems,
                review.getStatus(),
                statusCssClass(review.getStatus()),
                review.getNote()
        );
    }

    private StockReviewDetailItemResponse toDetailItem(
            Stockreviewdetail detail,
            String type
    ) {
        Product product = detail.getProductID();
        Batch batch = detail.getBatchID();

        Integer discrepancy =
                resolvedDiscrepancy(detail);

        /*
         * recordedExpirationDate là snapshot tại lúc rà soát.
         * Không đọc lại hạn dùng hiện tại từ Batch.
         */
        LocalDate recordedExpirationDate =
                detail.getRecordedExpirationDate();

        String condition =
                StockReviewCondition.normalize(
                        detail.getConditionStatus()
                );

        boolean issue =
                isIssue(detail, type);

        return new StockReviewDetailItemResponse(
                product != null
                        ? product.getProductID()
                        : null,
                product != null
                        ? product.getCode()
                        : "",
                product != null
                        ? product.getName()
                        : "Không rõ",
                batch != null
                        ? batch.getId()
                        : null,
                batch != null
                        ? batch.getLotNumber()
                        : "",
                recordedExpirationDate,
                formatLocalDate(
                        recordedExpirationDate
                ),
                detail.getActualExpirationDate(),
                formatLocalDate(
                        detail.getActualExpirationDate()
                ),
                detail.getSystemQty(),
                detail.getActualQty(),
                discrepancy,
                discrepancyCssClass(discrepancy),
                condition,
                StockReviewCondition.label(condition),
                conditionCssClass(condition),
                issue,
                detail.getNote()
        );
    }

    private StockReviewVoucherPrintLineResponse
    toVoucherPrintLine(
            Stockreviewdetail detail,
            String type
    ) {
        Product product = detail.getProductID();
        Batch batch = detail.getBatchID();

        int systemQty =
                safe(detail.getSystemQty());

        int actualQty =
                safe(detail.getActualQty());

        int discrepancy =
                StockReviewType.COUNT.equals(type)
                        ? resolvedDiscrepancy(detail)
                        : 0;

        BigDecimal unitCost =
                batch != null
                        && batch.getImportPricePerBase() != null
                        ? batch.getImportPricePerBase()
                        : BigDecimal.ZERO;

        BigDecimal discrepancyValue =
                StockReviewType.COUNT.equals(type)
                        ? unitCost.multiply(
                        BigDecimal.valueOf(discrepancy)
                )
                        : BigDecimal.ZERO;

        LocalDate recordedExpirationDate =
                detail.getRecordedExpirationDate();

        String condition =
                StockReviewCondition.normalize(
                        detail.getConditionStatus()
                );

        return new StockReviewVoucherPrintLineResponse(
                product != null
                        ? product.getProductID()
                        : null,
                product != null
                        ? product.getCode()
                        : "",
                product != null
                        ? product.getName()
                        : "Không rõ",
                batch != null
                        ? batch.getLotNumber()
                        : "",
                batch != null
                        ? formatLocalDate(
                        batch.getExpirationDate()
                )
                        : "",
                recordedExpirationDate,
                formatLocalDate(
                        recordedExpirationDate
                ),
                detail.getActualExpirationDate(),
                formatLocalDate(
                        detail.getActualExpirationDate()
                ),
                systemQty,
                actualQty,
                discrepancy,
                discrepancyValue,
                condition,
                StockReviewCondition.label(condition),
                isIssue(detail, type),
                detail.getNote()
        );
    }

    private StockReviewBatchCandidateResponse toBatchCandidate(
            Batch batch
    ) {
        Product product = batch.getProductID();

        return new StockReviewBatchCandidateResponse(
                batch.getId(),
                product != null
                        ? product.getProductID()
                        : null,
                product != null
                        ? product.getCode()
                        : "",
                product != null
                        ? product.getName()
                        : "Không rõ",
                batch.getLotNumber(),
                batch.getExpirationDate(),
                formatLocalDate(
                        batch.getExpirationDate()
                ),
                batch.getStorageQuantity()
        );
    }

    private String validateCreateRequest(
            StockReviewCreateRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Dữ liệu phiếu rà soát kho không hợp lệ"
            );
        }

        String type =
                StockReviewType.normalize(request.getType());

        if (!StockReviewType.isValid(type)) {
            throw new IllegalArgumentException(
                    "Loại rà soát kho không hợp lệ"
            );
        }

        if (request.getItems() == null
                || request.getItems().isEmpty()) {
            throw new IllegalArgumentException(
                    "Vui lòng chọn ít nhất một lô hàng "
                            + "để rà soát"
            );
        }

        Set<Integer> seenBatchIds =
                new java.util.HashSet<>();

        for (StockReviewItemRequest item
                : request.getItems()) {

            if (item == null
                    || item.getBatchId() == null) {
                throw new IllegalArgumentException(
                        "Dữ liệu lô hàng không hợp lệ"
                );
            }

            if (!seenBatchIds.add(
                    item.getBatchId()
            )) {
                throw new IllegalArgumentException(
                        "Một lô hàng không được xuất hiện "
                                + "nhiều lần trong phiếu rà soát"
                );
            }

            if (StockReviewType.COUNT.equals(type)
                    && (
                    item.getActualQty() == null
                            || item.getActualQty() < 0
            )) {
                throw new IllegalArgumentException(
                        "Số lượng thực tế không được để trống "
                                + "và không được âm"
                );
            }

            if (StockReviewType.DATE.equals(type)
                    && item.getActualExpirationDate() == null) {
                throw new IllegalArgumentException(
                        "Hạn dùng thực tế không được để trống"
                );
            }

            if (StockReviewType.CONDITION.equals(type)
                    && !StockReviewCondition.isValid(
                    item.getConditionStatus()
            )) {
                throw new IllegalArgumentException(
                        "Tình trạng thực tế của lô hàng "
                                + "không hợp lệ"
                );
            }

            if (StockReviewType.CONDITION.equals(type)
                    && StockReviewCondition.OTHER.equals(
                    StockReviewCondition.normalize(
                            item.getConditionStatus()
                    )
            )
                    && (
                    item.getNote() == null
                            || item.getNote().isBlank()
            )) {
                throw new IllegalArgumentException(
                        "Vui lòng nhập ghi chú khi tình trạng "
                                + "lô hàng là Khác"
                );
            }
        }

        return type;
    }

    private boolean isIssue(
            Stockreviewdetail detail,
            String type
    ) {
        return switch (
                StockReviewType.normalize(type)
                ) {
            case StockReviewType.COUNT ->
                    resolvedDiscrepancy(detail) != 0;

            case StockReviewType.DATE ->
                    !Objects.equals(
                            detail.getRecordedExpirationDate(),
                            detail.getActualExpirationDate()
                    );

            case StockReviewType.CONDITION ->
                    !StockReviewCondition.GOOD.equals(
                            StockReviewCondition.normalize(
                                    detail.getConditionStatus()
                            )
                    );

            default -> false;
        };
    }

    private int resolvedDiscrepancy(
            Stockreviewdetail detail
    ) {
        return detail.getDiscrepancy() == null
                ? safe(detail.getActualQty())
                - safe(detail.getSystemQty())
                : detail.getDiscrepancy();
    }

    private boolean matchesKeyword(
            Stockreview review,
            List<Stockreviewdetail> details,
            String keyword
    ) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }

        if (containsNormalized(
                review.getStockCountCode(),
                keyword
        )
                || containsNormalized(
                review.getType(),
                keyword
        )
                || containsNormalized(
                StockReviewType.label(
                        review.getType()
                ),
                keyword
        )
                || containsNormalized(
                review.getStatus(),
                keyword
        )
                || containsNormalized(
                review.getNote(),
                keyword
        )
                || containsNormalized(
                review.getCreatedBy() != null
                        ? review.getCreatedBy().getName()
                        : null,
                keyword
        )) {
            return true;
        }

        return details.stream()
                .anyMatch(detail -> {
                    Product product =
                            detail.getProductID();

                    Batch batch =
                            detail.getBatchID();

                    return product != null
                            && (
                            containsNormalized(
                                    String.valueOf(
                                            product.getProductID()
                                    ),
                                    keyword
                            )
                                    || containsNormalized(
                                    product.getCode(),
                                    keyword
                            )
                                    || containsNormalized(
                                    product.getName(),
                                    keyword
                            )
                                    || containsNormalized(
                                    product.getBarcode(),
                                    keyword
                            )
                    )
                            || batch != null
                            && containsNormalized(
                            batch.getLotNumber(),
                            keyword
                    );
                });
    }

    private boolean matchesBatchKeyword(
            Batch batch,
            String keyword
    ) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }

        Product product = batch.getProductID();

        return containsNormalized(
                batch.getLotNumber(),
                keyword
        )
                || product != null
                && (
                containsNormalized(
                        String.valueOf(
                                product.getProductID()
                        ),
                        keyword
                )
                        || containsNormalized(
                        product.getCode(),
                        keyword
                )
                        || containsNormalized(
                        product.getName(),
                        keyword
                )
                        || containsNormalized(
                        product.getBarcode(),
                        keyword
                )
        );
    }

    private boolean matchesDate(
            Stockreview review,
            LocalDate from,
            LocalDate to
    ) {
        if (from == null && to == null) {
            return true;
        }

        if (review.getReviewDate() == null) {
            return false;
        }

        LocalDate reviewDate =
                toLocalDate(review.getReviewDate());

        if (from != null
                && reviewDate.isBefore(from)) {
            return false;
        }

        return to == null
                || !reviewDate.isAfter(to);
    }

    private long countByStatus(
            List<Stockreview> reviews,
            String status
    ) {
        return reviews.stream()
                .filter(review ->
                        isStatus(
                                review.getStatus(),
                                status
                        )
                )
                .count();
    }

    private long countByType(
            List<Stockreview> reviews,
            String type
    ) {
        return reviews.stream()
                .filter(review ->
                        StockReviewType
                                .normalize(review.getType())
                                .equals(type)
                )
                .count();
    }

    private String statusCssClass(String status) {
        if (isStatus(
                status,
                StockReviewStatus.APPROVED
        )) {
            return "status-approved";
        }

        if (isStatus(
                status,
                StockReviewStatus.ADJUSTED
        )) {
            return "status-adjusted";
        }

        if (isStatus(
                status,
                StockReviewStatus.PENDING
        )) {
            return "status-pending";
        }

        if (isStatus(
                status,
                StockReviewStatus.REJECTED
        )) {
            return "status-rejected";
        }

        if (isStatus(
                status,
                StockReviewStatus.DRAFT
        )) {
            return "status-draft";
        }

        return "status-default";
    }

    private String discrepancyCssClass(
            Integer discrepancy
    ) {
        if (discrepancy == null
                || discrepancy == 0) {
            return "text-secondary";
        }

        return discrepancy > 0
                ? "text-success"
                : "text-danger";
    }

    private String conditionCssClass(
            String condition
    ) {
        return switch (
                StockReviewCondition.normalize(condition)
                ) {
            case StockReviewCondition.GOOD ->
                    "text-success";

            case StockReviewCondition.DAMAGED,
                 StockReviewCondition.SPOILED ->
                    "text-danger";

            case StockReviewCondition.OTHER ->
                    "text-warning";

            default ->
                    "text-secondary";
        };
    }

    private String temporaryCode() {
        return "TMP-SR-" + UUID.randomUUID();
    }

    private String formatReviewCode(Integer id) {
        return "SR-" + String.format(
                "%06d",
                id == null ? 0 : id
        );
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalDate toLocalDate(Instant instant) {
        return instant.atZone(
                ZoneId.systemDefault()
        ).toLocalDate();
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }

        return DateTimeFormatter
                .ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    private String formatLocalDate(LocalDate date) {
        return date == null
                ? ""
                : date.format(
                DateTimeFormatter.ofPattern(
                        "dd/MM/yyyy"
                )
        );
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private boolean isStatus(
            String actual,
            String expected
    ) {
        return normalize(actual)
                .equals(normalize(expected));
    }

    private boolean containsNormalized(
            String value,
            String keyword
    ) {
        return value != null
                && normalize(value).contains(keyword);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        String normalized = Normalizer.normalize(
                value,
                Normalizer.Form.NFD
        );

        normalized = normalized.replaceAll(
                "\\p{M}",
                ""
        );

        normalized = normalized
                .replace("Đ", "D")
                .replace("đ", "d");

        return normalized
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}