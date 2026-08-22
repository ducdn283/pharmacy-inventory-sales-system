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
import com.example.project.entity.Position;
import com.example.project.entity.Product;
import com.example.project.entity.Stockreview;
import com.example.project.entity.Stockreviewdetail;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.BatchRepository;
import com.example.project.repository.PositionRepository;
import com.example.project.repository.StockreviewRepository;
import com.example.project.repository.StockreviewdetailRepository;
import com.example.project.repository.TypeRepository;
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
import java.util.Comparator;
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

    private final StockreviewdetailRepository
            stockreviewdetailRepository;

    private final BatchRepository batchRepository;

    private final AccountRepository accountRepository;

    private final WorkflowNotificationService
            workflowNotificationService;

    private final PositionRepository positionRepository;

    private final TypeRepository typeRepository;

    public StockreviewService(
            StockreviewRepository stockreviewRepository,
            StockreviewdetailRepository
                    stockreviewdetailRepository,
            BatchRepository batchRepository,
            AccountRepository accountRepository,
            WorkflowNotificationService
                    workflowNotificationService,
            PositionRepository positionRepository,
            TypeRepository typeRepository
    ) {
        this.stockreviewRepository =
                stockreviewRepository;

        this.stockreviewdetailRepository =
                stockreviewdetailRepository;

        this.batchRepository =
                batchRepository;

        this.accountRepository =
                accountRepository;

        this.workflowNotificationService =
                workflowNotificationService;

        this.positionRepository =
                positionRepository;

        this.typeRepository =
                typeRepository;
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
        String normalizedKeyword =
                normalize(keyword);

        LocalDate from =
                parseDate(fromDate);

        LocalDate to =
                parseDate(toDate);

        List<Stockreview> reviews =
                stockreviewRepository
                        .findAllWithRelations();

        Map<Integer, List<Stockreviewdetail>> detailMap =
                stockreviewdetailRepository
                        .findAllWithRelations()
                        .stream()
                        .filter(detail ->
                                detail.getStockReviewID() != null
                                        && detail
                                        .getStockReviewID()
                                        .getId() != null
                        )
                        .collect(
                                Collectors.groupingBy(
                                        detail ->
                                                detail
                                                        .getStockReviewID()
                                                        .getId()
                                )
                        );

        List<StockReviewListItemResponse> rows =
                reviews.stream()
                        .filter(review ->
                                matchesKeyword(
                                        review,
                                        detailMap.getOrDefault(
                                                review.getId(),
                                                List.of()
                                        ),
                                        normalizedKeyword
                                )
                        )
                        .filter(review ->
                                matchesDate(
                                        review,
                                        from,
                                        to
                                )
                        )
                        .filter(review ->
                                type == null
                                        || type.isBlank()
                                        || StockReviewType
                                        .normalize(type)
                                        .equals(
                                                StockReviewType
                                                        .normalize(
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
                        .map(review ->
                                toListItem(
                                        review,
                                        detailMap.getOrDefault(
                                                review.getId(),
                                                List.of()
                                        )
                                )
                        )
                        .toList();

        int start =
                (int) pageable.getOffset();

        int end =
                Math.min(
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
                stockreviewRepository
                        .findAllWithRelations();

        return new StockReviewStatsResponse(
                reviews.size(),

                countByType(
                        reviews,
                        StockReviewType.COUNT
                ),

                countByType(
                        reviews,
                        StockReviewType.DATE
                ),

                countByType(
                        reviews,
                        StockReviewType.CONDITION
                ),

                countByStatus(
                        reviews,
                        StockReviewStatus.DRAFT
                ),

                countByStatus(
                        reviews,
                        StockReviewStatus.PENDING
                ),

                countByStatus(
                        reviews,
                        StockReviewStatus.APPROVED
                ),

                countByStatus(
                        reviews,
                        StockReviewStatus.ADJUSTED
                )
        );
    }

    public List<String> listStatuses() {
        return StockReviewStatus.ALL;
    }

    public Map<String, String> typeLabels() {
        return StockReviewType.labels();
    }

    /**
     * Ba loại được phép xuất hiện khi tạo phiếu:
     * COUNT, DATE và CONDITION.
     */
    public Map<String, String> creatableTypeLabels() {
        Map<String, String> labels =
                new LinkedHashMap<>();

        labels.put(
                StockReviewType.COUNT,
                StockReviewType.label(
                        StockReviewType.COUNT
                )
        );

        labels.put(
                StockReviewType.DATE,
                StockReviewType.label(
                        StockReviewType.DATE
                )
        );

        labels.put(
                StockReviewType.CONDITION,
                StockReviewType.label(
                        StockReviewType.CONDITION
                )
        );

        return labels;
    }

    @Transactional(readOnly = true)
    public Map<Integer, String> productTypeOptions() {
        return typeRepository
                .findAll()
                .stream()
                .sorted(
                        Comparator.comparing(
                                type ->
                                        type.getName()
                                                .toLowerCase(
                                                        Locale.ROOT
                                                )
                        )
                )
                .collect(
                        Collectors.toMap(
                                com.example.project.entity.Type::getId,
                                com.example.project.entity.Type::getName,
                                (first, ignored) -> first,
                                LinkedHashMap::new
                        )
                );
    }

    @Transactional(readOnly = true)
    public List<String> positionOptions() {
        return positionRepository
                .findAll()
                .stream()
                .map(Position::getName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name ->
                        !name.isEmpty()
                )
                .distinct()
                .sorted(
                        String.CASE_INSENSITIVE_ORDER
                )
                .toList();
    }

    public Map<String, String> conditionLabels() {
        return StockReviewCondition.labels();
    }

    /**
     * Form tạo mới không tự nạp toàn bộ lô.
     * Người dùng phải tìm kiếm hoặc chọn bộ lọc.
     */
    @Transactional(readOnly = true)
    public StockReviewCreateRequest buildDefaultForm() {
        StockReviewCreateRequest form =
                new StockReviewCreateRequest();

        form.setType(
                StockReviewType.COUNT
        );

        return form;
    }

    @Transactional(readOnly = true)
    public List<StockReviewBatchCandidateResponse>
    listReviewableBatches(String keyword) {
        return listReviewableBatches(
                keyword,
                null,
                null
        );
    }

    @Transactional(readOnly = true)
    public List<StockReviewBatchCandidateResponse>
    listReviewableBatches(
            String keyword,
            Integer typeId,
            String positionName
    ) {
        String normalizedKeyword =
                normalize(keyword);

        String normalizedPosition =
                normalize(positionName);

        /*
         * Không trả toàn bộ danh sách nếu người dùng
         * chưa tìm kiếm và chưa chọn bộ lọc.
         */
        if (normalizedKeyword.isBlank()
                && typeId == null
                && normalizedPosition.isBlank()) {
            return List.of();
        }

        Map<Integer, List<String>> positionsByProduct =
                positionRepository
                        .findAll()
                        .stream()
                        .filter(position ->
                                position.getProductID() != null
                                        && position
                                        .getProductID()
                                        .getProductID() != null
                        )
                        .collect(
                                Collectors.groupingBy(
                                        position ->
                                                position
                                                        .getProductID()
                                                        .getProductID(),

                                        Collectors.mapping(
                                                Position::getName,
                                                Collectors.toList()
                                        )
                                )
                        );

        return batchRepository
                .findAvailableBatchesForDestroy()
                .stream()
                .filter(batch ->
                        matchesBatchKeyword(
                                batch,
                                normalizedKeyword
                        )
                )
                .filter(batch ->
                        typeId == null
                                || (
                                batch.getProductID() != null
                                        && batch
                                        .getProductID()
                                        .getTypeID() != null
                                        && typeId.equals(
                                        batch
                                                .getProductID()
                                                .getTypeID()
                                                .getId()
                                )
                        )
                )
                .filter(batch ->
                        normalizedPosition.isBlank()
                                || positionsByProduct
                                .getOrDefault(
                                        batch.getProductID() != null
                                                ? batch
                                                .getProductID()
                                                .getProductID()
                                                : null,
                                        List.of()
                                )
                                .stream()
                                .anyMatch(position ->
                                        containsNormalized(
                                                position,
                                                normalizedPosition
                                        )
                                )
                )
                .map(batch ->
                        toBatchCandidate(
                                batch,
                                positionsByProduct.getOrDefault(
                                        batch.getProductID() != null
                                                ? batch
                                                .getProductID()
                                                .getProductID()
                                                : null,
                                        List.of()
                                )
                        )
                )
                .toList();
    }

    /**
     * Tạo phiếu rà soát số lượng, hạn dùng
     * hoặc tình trạng.
     */
    @Transactional
    public Integer create(
            StockReviewCreateRequest request,
            Integer currentAccountId,
            boolean isOwner,
            boolean asDraft
    ) {
        String reviewType =
                validateCreateRequest(
                        request,
                        !asDraft
                );

        Account creator =
                accountRepository
                        .findById(currentAccountId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy "
                                                + "tài khoản hiện tại"
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
                        "Một lô hàng không được xuất hiện "
                                + "nhiều lần trong phiếu rà soát"
                );
            }
        }

        Map<Integer, Batch> batchMap =
                batchRepository
                        .findAllById(
                                itemMap.keySet()
                        )
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        Batch::getId,
                                        Function.identity()
                                )
                        );

        if (batchMap.size() != itemMap.size()) {
            throw new IllegalArgumentException(
                    "Một số lô hàng không tồn tại"
            );
        }

        /*
         * Lưu nháp không cần tổng số lượng CONDITION
         * bằng tồn kho. Chỉ kiểm tra khi gửi phiếu.
         */
        if (!asDraft
                && StockReviewType.CONDITION.equals(
                reviewType
        )) {
            validateConditionQuantities(
                    itemMap,
                    batchMap
            );
        }
        String status =
                resolveCreateStatus(
                        isOwner,
                        asDraft
                );

        boolean approvedNow =
                isStatus(
                        status,
                        StockReviewStatus.APPROVED
                );

        Stockreview review =
                new Stockreview();

        /*
         * Entity hiện tại vẫn sử dụng tên stockCountCode.
         * Không đổi entity vì đây là phần core của dự án.
         */
        review.setStockCountCode(
                temporaryCode()
        );

        review.setType(reviewType);
        review.setReviewDate(Instant.now());
        review.setCreatedBy(creator);
        review.setStatus(status);

        review.setNote(
                trimToNull(
                        request.getNote()
                )
        );

        if (approvedNow) {
            review.setApprovedBy(creator);
            review.setApprovedAt(
                    Instant.now()
            );
        }

        Stockreview saved =
                stockreviewRepository.save(review);

        saved.setStockCountCode(
                formatReviewCode(
                        saved.getId()
                )
        );

        for (Map.Entry<Integer, StockReviewItemRequest> entry
                : itemMap.entrySet()) {

            Batch batch =
                    batchMap.get(
                            entry.getKey()
                    );

            StockReviewItemRequest item =
                    entry.getValue();

            int systemQty =
                    safe(
                            batch.getStorageQuantity()
                    );

            /*
             * Mỗi lô rà soát tình trạng được lưu thành
             * hai bản ghi chi tiết:
             * - Đạt chuẩn.
             * - Không đạt chuẩn.
             */
            if (StockReviewType.CONDITION.equals(
                    reviewType
            )) {
                saveConditionDetail(
                        saved,
                        batch,
                        systemQty,
                        item.getCompliantQty(),
                        StockReviewCondition.COMPLIANT,
                        item.getNote(),
                        item.getCompliantQty() != null
                );

                saveConditionDetail(
                        saved,
                        batch,
                        systemQty,
                        item.getNonCompliantQty(),
                        StockReviewCondition.NON_COMPLIANT,
                        item.getNote(),
                        item.getNonCompliantQty() != null
                );

                continue;
            }

            Stockreviewdetail detail =
                    new Stockreviewdetail();

            detail.setStockReviewID(saved);
            detail.setProductID(
                    batch.getProductID()
            );
            detail.setBatchID(batch);
            detail.setSystemQty(systemQty);

            detail.setNote(
                    trimToNull(
                            item.getNote()
                    )
            );

            if (StockReviewType.COUNT.equals(
                    reviewType
            )) {
                Integer enteredActualQty =
                        item.getActualQty();

                if (enteredActualQty == null) {
                    /*
                     * actualQty trong database là NOT NULL.
                     *
                     * Với dòng nháp chưa nhập:
                     * - Tạm lưu actualQty bằng systemQty.
                     * - discrepancy = null đánh dấu chưa nhập.
                     */
                    detail.setActualQty(systemQty);
                    detail.setDiscrepancy(null);
                } else {
                    detail.setActualQty(
                            enteredActualQty
                    );

                    detail.setDiscrepancy(
                            enteredActualQty
                                    - systemQty
                    );
                }
            } else {
                /*
                 * DATE không sử dụng actualQty nhưng database
                 * vẫn yêu cầu giá trị NOT NULL.
                 *
                 * actualExpirationDate được phép null
                 * khi lưu nháp.
                 */
                detail.setActualQty(systemQty);
                detail.setDiscrepancy(0);
            }

            if (StockReviewType.DATE.equals(
                    reviewType
            )) {
                detail.setRecordedExpirationDate(
                        batch.getExpirationDate()
                );

                detail.setActualExpirationDate(
                        item.getActualExpirationDate()
                );
            }

            stockreviewdetailRepository.save(
                    detail
            );
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

    /**
     * Kiểm tra tổng số lượng đạt chuẩn và không đạt chuẩn
     * của mỗi lô trước khi gửi phiếu.
     */
    private void validateConditionQuantities(
            Map<Integer, StockReviewItemRequest> itemMap,
            Map<Integer, Batch> batchMap
    ) {
        for (Map.Entry<Integer, StockReviewItemRequest> entry
                : itemMap.entrySet()) {

            Batch batch =
                    batchMap.get(
                            entry.getKey()
                    );

            if (batch == null) {
                throw new IllegalArgumentException(
                        "Không tìm thấy lô hàng cần rà soát"
                );
            }

            StockReviewItemRequest item =
                    entry.getValue();

            Integer compliantQty =
                    item.getCompliantQty();

            Integer nonCompliantQty =
                    item.getNonCompliantQty();

            if (compliantQty == null
                    || nonCompliantQty == null) {
                throw new IllegalArgumentException(
                        "Vui lòng nhập đầy đủ số lượng đạt chuẩn "
                                + "và không đạt chuẩn trước khi gửi phiếu"
                );
            }

            if (compliantQty < 0
                    || nonCompliantQty < 0) {
                throw new IllegalArgumentException(
                        "Số lượng rà soát không được âm"
                );
            }

            int systemQty =
                    safe(
                            batch.getStorageQuantity()
                    );

            int reviewedQty =
                    compliantQty
                            + nonCompliantQty;

            if (reviewedQty != systemQty) {
                throw new IllegalArgumentException(
                        "Tổng số lượng đạt chuẩn và không đạt chuẩn "
                                + "của lô "
                                + displayLotNumber(batch)
                                + " phải bằng tồn hệ thống ("
                                + systemQty
                                + ")"
                );
            }
        }
    }

    /**
     * Lưu một bản ghi tình trạng của lô.
     *
     * completed:
     * - false: dòng nháp chưa nhập số lượng.
     * - true: người dùng đã nhập, kể cả nhập 0.
     */
    private void saveConditionDetail(
            Stockreview review,
            Batch batch,
            int systemQty,
            Integer actualQty,
            String conditionStatus,
            String note,
            boolean completed
    ) {
        Stockreviewdetail detail =
                new Stockreviewdetail();

        detail.setStockReviewID(review);
        detail.setProductID(
                batch.getProductID()
        );
        detail.setBatchID(batch);
        detail.setSystemQty(systemQty);

        /*
         * actualQty trong database không cho phép null,
         * vì vậy dòng nháp chưa nhập tạm lưu bằng 0.
         */
        detail.setActualQty(
                safe(actualQty)
        );

        /*
         * CONDITION không sử dụng discrepancy để tính lệch.
         * Trường này được dùng làm cờ hoàn thiện:
         * - null: chưa nhập.
         * - 0: đã nhập.
         */
        detail.setDiscrepancy(
                completed
                        ? 0
                        : null
        );

        detail.setConditionStatus(
                conditionStatus
        );

        detail.setNote(
                trimToNull(note)
        );

        stockreviewdetailRepository.save(
                detail
        );
    }

    private String displayLotNumber(
            Batch batch
    ) {
        if (batch == null
                || batch.getLotNumber() == null
                || batch.getLotNumber().isBlank()) {
            return "không xác định";
        }

        return batch
                .getLotNumber()
                .trim();
    }

    /**
     * Tạo form chỉnh sửa từ dữ liệu phiếu nháp.
     */
    @Transactional(readOnly = true)
    public StockReviewCreateRequest getDraftForm(
            Integer stockReviewId,
            Integer currentAccountId,
            boolean isOwner
    ) {
        Stockreview review =
                findEditableDraft(
                        stockReviewId,
                        currentAccountId,
                        isOwner
                );

        String reviewType =
                StockReviewType.normalize(
                        review.getType()
                );

        List<Stockreviewdetail> details =
                stockreviewdetailRepository
                        .findByStockReviewIdWithRelations(
                                stockReviewId
                        );

        StockReviewCreateRequest form =
                new StockReviewCreateRequest();

        form.setType(reviewType);
        form.setNote(
                review.getNote()
        );

        /*
         * CONDITION có hai detail trên cùng một batch,
         * nên gom chúng lại thành một StockReviewItemRequest.
         */
        Map<Integer, StockReviewItemRequest> itemsByBatch =
                new LinkedHashMap<>();

        for (Stockreviewdetail detail
                : details) {

            if (detail.getBatchID() == null
                    || detail.getBatchID().getId() == null) {
                continue;
            }

            Integer batchId =
                    detail.getBatchID().getId();

            StockReviewItemRequest item =
                    itemsByBatch.computeIfAbsent(
                            batchId,
                            ignored -> {
                                StockReviewItemRequest created =
                                        new StockReviewItemRequest();

                                created.setBatchId(batchId);
                                created.setNote(
                                        detail.getNote()
                                );

                                return created;
                            }
                    );

            if (StockReviewType.COUNT.equals(
                    reviewType
            )) {
                /*
                 * discrepancy = null nghĩa là dòng nháp
                 * chưa nhập số lượng thực tế.
                 */
                if (detail.getDiscrepancy() == null) {
                    item.setActualQty(null);
                } else {
                    item.setActualQty(
                            detail.getActualQty()
                    );
                }
            }

            if (StockReviewType.DATE.equals(
                    reviewType
            )) {
                item.setActualExpirationDate(
                        detail.getActualExpirationDate()
                );
            }

            if (StockReviewType.CONDITION.equals(
                    reviewType
            )) {
                boolean completed =
                        detail.getDiscrepancy() != null;

                String condition =
                        StockReviewCondition.normalize(
                                detail.getConditionStatus()
                        );

                if (StockReviewCondition.COMPLIANT.equals(
                        condition
                )) {
                    item.setCompliantQty(
                            completed
                                    ? detail.getActualQty()
                                    : null
                    );
                } else if (
                        StockReviewCondition.NON_COMPLIANT.equals(
                                condition
                        )
                ) {
                    item.setNonCompliantQty(
                            completed
                                    ? detail.getActualQty()
                                    : null
                    );
                }
            }
        }

        form.getItems().addAll(
                itemsByBatch.values()
        );

        return form;
    }

    /**
     * Tìm thông tin sản phẩm/lô của các dòng trong form.
     *
     * Dùng để khôi phục danh sách đã chọn khi:
     * - Mở màn hình chỉnh sửa phiếu nháp.
     * - Backend trả form về sau lỗi validation.
     */
    @Transactional(readOnly = true)
    public List<StockReviewBatchCandidateResponse>
    findCandidatesForForm(
            StockReviewCreateRequest form
    ) {
        if (form == null
                || form.getItems() == null
                || form.getItems().isEmpty()) {
            return List.of();
        }

        List<Integer> batchIds =
                form.getItems()
                        .stream()
                        .filter(Objects::nonNull)
                        .map(
                                StockReviewItemRequest::getBatchId
                        )
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();

        if (batchIds.isEmpty()) {
            return List.of();
        }

        Map<Integer, Batch> batchesById =
                batchRepository
                        .findAllById(batchIds)
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        Batch::getId,
                                        Function.identity()
                                )
                        );

        Map<Integer, List<String>> positionsByProduct =
                positionRepository
                        .findAll()
                        .stream()
                        .filter(position ->
                                position.getProductID() != null
                                        && position
                                        .getProductID()
                                        .getProductID() != null
                        )
                        .collect(
                                Collectors.groupingBy(
                                        position ->
                                                position
                                                        .getProductID()
                                                        .getProductID(),

                                        Collectors.mapping(
                                                Position::getName,
                                                Collectors.toList()
                                        )
                                )
                        );
        /*
         * Duyệt batchIds để giữ đúng thứ tự các lô
         * mà người dùng đã chọn trên form.
         */
        return batchIds.stream()
                .map(batchesById::get)
                .filter(Objects::nonNull)
                .map(batch -> {
                    Product product =
                            batch.getProductID();

                    Integer productId =
                            product != null
                                    ? product.getProductID()
                                    : null;

                    return toBatchCandidate(
                            batch,
                            positionsByProduct.getOrDefault(
                                    productId,
                                    List.of()
                            )
                    );
                })
                .toList();
    }

    /**
     * Cập nhật nội dung phiếu nháp.
     *
     * keepDraft = true:
     * - Cho phép các trường thực tế còn trống.
     * - Trạng thái vẫn là Nháp.
     *
     * keepDraft = false:
     * - Bắt buộc nhập đầy đủ.
     * - Owner chuyển thẳng sang Đã duyệt.
     * - Nhân viên chuyển sang Chờ duyệt.
     */
    @Transactional
    public void updateDraft(
            Integer stockReviewId,
            StockReviewCreateRequest request,
            Integer currentAccountId,
            boolean isOwner,
            boolean keepDraft
    ) {
        Stockreview review =
                findEditableDraft(
                        stockReviewId,
                        currentAccountId,
                        isOwner
                );

        String reviewType =
                validateCreateRequest(
                        request,
                        !keepDraft
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
                        "Một lô hàng không được xuất hiện "
                                + "nhiều lần trong phiếu rà soát"
                );
            }
        }

        Map<Integer, Batch> batchMap =
                batchRepository
                        .findAllById(
                                itemMap.keySet()
                        )
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        Batch::getId,
                                        Function.identity()
                                )
                        );

        if (batchMap.size()
                != itemMap.size()) {
            throw new IllegalArgumentException(
                    "Một số lô hàng không tồn tại"
            );
        }

        /*
         * Khi tiếp tục lưu nháp, hai số lượng CONDITION
         * được phép để trống hoặc chưa bằng tồn hệ thống.
         *
         * Chỉ kiểm tra tổng khi gửi phiếu.
         */
        if (!keepDraft
                && StockReviewType.CONDITION.equals(
                reviewType
        )) {
            validateConditionQuantities(
                    itemMap,
                    batchMap
            );
        }

        List<Stockreviewdetail> oldDetails =
                stockreviewdetailRepository
                        .findByStockReviewIdWithRelations(
                                stockReviewId
                        );

        /*
         * Xóa detail cũ rồi tạo lại theo danh sách hiện tại.
         * Nhờ đó việc xóa lô bằng dấu X cũng được lưu.
         */
        stockreviewdetailRepository.deleteAll(
                oldDetails
        );

        stockreviewdetailRepository.flush();

        review.setType(reviewType);

        review.setNote(
                trimToNull(
                        request.getNote()
                )
        );

        /*
         * Phiếu vẫn là Nháp trong lúc tạo lại detail.
         */
        review.setStatus(
                StockReviewStatus.DRAFT
        );

        review.setApprovedBy(null);
        review.setApprovedAt(null);

        stockreviewRepository.save(review);

        for (Map.Entry<Integer, StockReviewItemRequest> entry
                : itemMap.entrySet()) {

            Batch batch =
                    batchMap.get(
                            entry.getKey()
                    );

            StockReviewItemRequest item =
                    entry.getValue();

            int systemQty =
                    safe(
                            batch.getStorageQuantity()
                    );

            if (StockReviewType.CONDITION.equals(
                    reviewType
            )) {
                saveConditionDetail(
                        review,
                        batch,
                        systemQty,
                        item.getCompliantQty(),
                        StockReviewCondition.COMPLIANT,
                        item.getNote(),
                        item.getCompliantQty() != null
                );

                saveConditionDetail(
                        review,
                        batch,
                        systemQty,
                        item.getNonCompliantQty(),
                        StockReviewCondition.NON_COMPLIANT,
                        item.getNote(),
                        item.getNonCompliantQty() != null
                );

                continue;
            }

            Stockreviewdetail detail =
                    new Stockreviewdetail();

            detail.setStockReviewID(review);

            detail.setProductID(
                    batch.getProductID()
            );

            detail.setBatchID(batch);
            detail.setSystemQty(systemQty);

            detail.setNote(
                    trimToNull(
                            item.getNote()
                    )
            );

            if (StockReviewType.COUNT.equals(
                    reviewType
            )) {
                Integer enteredActualQty =
                        item.getActualQty();

                if (enteredActualQty == null) {
                    /*
                     * Dòng nháp chưa nhập:
                     * - actualQty tạm bằng systemQty do NOT NULL.
                     * - discrepancy null đánh dấu chưa hoàn thiện.
                     */
                    detail.setActualQty(systemQty);
                    detail.setDiscrepancy(null);
                } else {
                    detail.setActualQty(
                            enteredActualQty
                    );

                    detail.setDiscrepancy(
                            enteredActualQty
                                    - systemQty
                    );
                }
            } else {
                /*
                 * DATE không sử dụng actualQty nhưng database
                 * vẫn yêu cầu có giá trị.
                 */
                detail.setActualQty(systemQty);
                detail.setDiscrepancy(0);

                detail.setRecordedExpirationDate(
                        batch.getExpirationDate()
                );

                /*
                 * Khi lưu nháp, hạn dùng thực tế có thể null.
                 */
                detail.setActualExpirationDate(
                        item.getActualExpirationDate()
                );
            }

            stockreviewdetailRepository.save(
                    detail
            );
        }

        if (keepDraft) {
            return;
        }

        Account actor =
                accountRepository
                        .findById(currentAccountId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy "
                                                + "tài khoản hiện tại"
                                )
                        );

        if (isOwner) {
            review.setStatus(
                    StockReviewStatus.APPROVED
            );

            review.setApprovedBy(actor);

            review.setApprovedAt(
                    Instant.now()
            );
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

    /**
     * Tìm phiếu và kiểm tra quyền chỉnh sửa nháp.
     */
    private Stockreview findEditableDraft(
            Integer stockReviewId,
            Integer currentAccountId,
            boolean isOwner
    ) {
        Stockreview review =
                stockreviewRepository
                        .findByIdWithRelations(
                                stockReviewId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy "
                                                + "phiếu rà soát kho"
                                )
                        );

        if (!isStatus(
                review.getStatus(),
                StockReviewStatus.DRAFT
        )) {
            throw new IllegalArgumentException(
                    "Chỉ phiếu rà soát kho đang ở trạng thái "
                            + "nháp mới được chỉnh sửa"
            );
        }

        /*
         * Owner được sửa tất cả phiếu nháp.
         * Nhân viên chỉ được sửa phiếu do chính mình tạo.
         */
        if (!isOwner) {
            Integer creatorId =
                    review.getCreatedBy() != null
                            ? review.getCreatedBy().getId()
                            : null;

            if (!Objects.equals(
                    creatorId,
                    currentAccountId
            )) {
                throw new IllegalArgumentException(
                        "Bạn không có quyền chỉnh sửa "
                                + "phiếu rà soát kho này"
                );
            }
        }

        return review;
    }

    /**
     * Kiểm tra detail đã lưu trước khi gửi phiếu
     * trực tiếp từ màn hình chi tiết.
     */
    private void validateDraftCompleteness(
            Stockreview review,
            List<Stockreviewdetail> details
    ) {
        if (details == null
                || details.isEmpty()) {
            throw new IllegalArgumentException(
                    "Phiếu chưa có lô hàng để gửi"
            );
        }

        String reviewType =
                StockReviewType.normalize(
                        review.getType()
                );

        if (StockReviewType.COUNT.equals(
                reviewType
        )) {
            boolean hasIncompleteItem =
                    details.stream()
                            .anyMatch(detail ->
                                    detail.getDiscrepancy()
                                            == null
                            );

            if (hasIncompleteItem) {
                throw new IllegalArgumentException(
                        "Vui lòng chỉnh sửa và nhập đầy đủ "
                                + "số lượng thực tế trước khi gửi"
                );
            }

            return;
        }

        if (StockReviewType.DATE.equals(
                reviewType
        )) {
            boolean hasIncompleteItem =
                    details.stream()
                            .anyMatch(detail ->
                                    detail
                                            .getActualExpirationDate()
                                            == null
                            );

            if (hasIncompleteItem) {
                throw new IllegalArgumentException(
                        "Vui lòng chỉnh sửa và nhập đầy đủ "
                                + "hạn dùng thực tế trước khi gửi"
                );
            }

            return;
        }

        if (StockReviewType.CONDITION.equals(
                reviewType
        )) {
            Map<Integer, List<Stockreviewdetail>>
                    detailsByBatch =
                    details.stream()
                            .filter(detail ->
                                    detail.getBatchID() != null
                                            && detail
                                            .getBatchID()
                                            .getId() != null
                            )
                            .collect(
                                    Collectors.groupingBy(
                                            detail ->
                                                    detail
                                                            .getBatchID()
                                                            .getId()
                                    )
                            );

            for (List<Stockreviewdetail> batchDetails
                    : detailsByBatch.values()) {

                boolean incomplete =
                        batchDetails.size() != 2
                                || batchDetails.stream()
                                .anyMatch(detail ->
                                        detail.getDiscrepancy()
                                                == null
                                );

                if (incomplete) {
                    throw new IllegalArgumentException(
                            "Vui lòng chỉnh sửa và nhập đầy đủ "
                                    + "số lượng đạt chuẩn, không đạt "
                                    + "chuẩn trước khi gửi"
                    );
                }

                int systemQty =
                        safe(
                                batchDetails
                                        .get(0)
                                        .getSystemQty()
                        );

                int reviewedQty =
                        batchDetails.stream()
                                .map(
                                        Stockreviewdetail::getActualQty
                                )
                                .mapToInt(this::safe)
                                .sum();

                if (reviewedQty != systemQty) {
                    throw new IllegalArgumentException(
                            "Tổng số lượng đạt chuẩn và không đạt "
                                    + "chuẩn phải bằng tồn hệ thống "
                                    + "của từng lô"
                    );
                }
            }

            return;
        }

        throw new IllegalArgumentException(
                "Loại phiếu rà soát này không hợp lệ"
        );
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
        Stockreview review =
                stockreviewRepository
                        .findByIdWithRelations(
                                stockReviewId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy "
                                                + "phiếu rà soát kho"
                                )
                        );

        String type =
                StockReviewType.normalize(
                        review.getType()
                );

        List<Stockreviewdetail> details =
                stockreviewdetailRepository
                        .findByStockReviewIdWithRelations(
                                stockReviewId
                        );

        /*
         * Với CONDITION, luôn xếp Đạt chuẩn ở trên
         * và Không đạt chuẩn ở dưới.
         */
        List<StockReviewDetailItemResponse> items =
                details.stream()
                        .sorted(
                                conditionDetailComparator(
                                        type
                                )
                        )
                        .map(detail ->
                                toDetailItem(
                                        detail,
                                        type
                                )
                        )
                        .toList();

        long issueItems =
                details.stream()
                        .filter(detail ->
                                isIssue(
                                        detail,
                                        type
                                )
                        )
                        .count();

        /*
         * CONDITION có hai detail cho một batch,
         * nhưng tổng số lô chỉ đếm một lần.
         */
        long totalItems =
                countReviewedBatches(
                        details,
                        type
                );

        /*
         * systemQty của CONDITION chỉ cộng một lần
         * cho mỗi batch.
         */
        int totalSystemQty =
                totalSystemQty(
                        details,
                        type
                );

        /*
         * actualQty của CONDITION là tổng hai nhóm
         * COMPLIANT và NON_COMPLIANT.
         */
        int totalActualQty =
                details.stream()
                        .map(
                                Stockreviewdetail::getActualQty
                        )
                        .filter(Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .sum();

        return new StockReviewDetailPageResponse(
                review.getId(),
                review.getStockCountCode(),
                review.getReviewDate(),
                formatInstant(
                        review.getReviewDate()
                ),
                type,
                StockReviewType.label(type),

                review.getCreatedBy() != null
                        ? review.getCreatedBy().getName()
                        : "Không rõ",

                review.getApprovedBy() != null
                        ? review.getApprovedBy().getName()
                        : "Chưa có",

                formatInstant(
                        review.getApprovedAt()
                ),

                review.getStatus(),
                statusCssClass(
                        review.getStatus()
                ),
                review.getNote(),
                totalItems,
                totalItems - issueItems,
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
        Stockreview review =
                stockreviewRepository
                        .findByIdWithRelations(
                                stockReviewId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy "
                                                + "phiếu rà soát kho"
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

        List<Stockreviewdetail> details =
                stockreviewdetailRepository
                        .findByStockReviewIdWithRelations(
                                stockReviewId
                        );

        validateDraftCompleteness(
                review,
                details
        );

        Account actor =
                accountRepository
                        .findById(currentAccountId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy "
                                                + "tài khoản hiện tại"
                                )
                        );

        if (isOwner) {
            review.setStatus(
                    StockReviewStatus.APPROVED
            );

            review.setApprovedBy(actor);

            review.setApprovedAt(
                    Instant.now()
            );
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
        Stockreview review =
                stockreviewRepository
                        .findByIdWithRelations(
                                stockReviewId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy "
                                                + "phiếu rà soát kho"
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

        Account owner =
                accountRepository
                        .findById(ownerAccountId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy "
                                                + "tài khoản hiện tại"
                                )
                        );

        review.setStatus(
                StockReviewStatus.APPROVED
        );

        review.setApprovedBy(owner);

        review.setApprovedAt(
                Instant.now()
        );

        stockreviewRepository.save(review);

        workflowNotificationService
                .stockReviewApproved(review);
    }

    @Transactional
    public void reject(
            Integer stockReviewId,
            Integer ownerAccountId
    ) {
        Stockreview review =
                stockreviewRepository
                        .findByIdWithRelations(
                                stockReviewId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy "
                                                + "phiếu rà soát kho"
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

        Account owner =
                accountRepository
                        .findById(ownerAccountId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy "
                                                + "tài khoản hiện tại"
                                )
                        );

        review.setStatus(
                StockReviewStatus.REJECTED
        );

        review.setApprovedBy(owner);

        review.setApprovedAt(
                Instant.now()
        );

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
                StockReviewType.normalize(
                        requestedType
                );

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
    getVoucherPrintPage(
            Integer stockReviewId
    ) {
        Stockreview review =
                stockreviewRepository
                        .findByIdWithRelations(
                                stockReviewId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy "
                                                + "phiếu rà soát kho"
                                )
                        );

        String type =
                StockReviewType.normalize(
                        review.getType()
                );

        List<Stockreviewdetail> details =
                stockreviewdetailRepository
                        .findByStockReviewIdWithRelations(
                                stockReviewId
                        );

        List<StockReviewVoucherPrintLineResponse> items =
                details.stream()
                        .sorted(
                                conditionDetailComparator(type)
                        )
                        .map(detail ->
                                toVoucherPrintLine(
                                        detail,
                                        type
                                )
                        )
                        .toList();

        long totalItems =
                countReviewedBatches(
                        details,
                        type
                );

        int totalSystemQty =
                totalSystemQty(
                        details,
                        type
                );

        int totalActualQty =
                details.stream()
                        .map(
                                Stockreviewdetail::getActualQty
                        )
                        .filter(Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .sum();

        int totalExcessQty =
                items.stream()
                        .map(
                                StockReviewVoucherPrintLineResponse
                                        ::getDiscrepancy
                        )
                        .filter(Objects::nonNull)
                        .filter(value ->
                                value > 0
                        )
                        .mapToInt(Integer::intValue)
                        .sum();

        int totalShortageQty =
                items.stream()
                        .map(
                                StockReviewVoucherPrintLineResponse
                                        ::getDiscrepancy
                        )
                        .filter(Objects::nonNull)
                        .filter(value ->
                                value < 0
                        )
                        .mapToInt(value ->
                                Math.abs(value)
                        )
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

        long issueItems =
                details.stream()
                        .filter(detail ->
                                isIssue(
                                        detail,
                                        type
                                )
                        )
                        .count();

        return new StockReviewVoucherPrintPageResponse(
                review.getId(),
                review.getStockCountCode(),
                formatInstant(
                        review.getReviewDate()
                ),
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
                        ? formatInstant(
                        review.getApprovedAt()
                )
                        : "Chưa có",

                review.getNote(),
                totalItems,
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
                StockReviewType.normalize(
                        review.getType()
                );

        long issueItems =
                details.stream()
                        .filter(detail ->
                                isIssue(
                                        detail,
                                        type
                                )
                        )
                        .count();

        return new StockReviewListItemResponse(
                review.getId(),
                review.getStockCountCode(),
                review.getReviewDate(),
                formatInstant(
                        review.getReviewDate()
                ),
                type,
                StockReviewType.label(type),

                review.getCreatedBy() != null
                        ? review.getCreatedBy().getName()
                        : "Không rõ",

                review.getApprovedBy() != null
                        ? review.getApprovedBy().getName()
                        : "Chưa có",

                formatInstant(
                        review.getApprovedAt()
                ),

                countReviewedBatches(
                        details,
                        type
                ),

                issueItems,
                review.getStatus(),
                statusCssClass(
                        review.getStatus()
                ),
                review.getNote()
        );
    }

    private StockReviewDetailItemResponse toDetailItem(
            Stockreviewdetail detail,
            String type
    ) {
        Product product =
                detail.getProductID();

        Batch batch =
                detail.getBatchID();

        Integer discrepancy =
                resolvedDiscrepancy(detail);

        /*
         * Đây là snapshot tại thời điểm rà soát.
         * Không lấy hạn dùng hiện tại của Batch làm fallback.
         */
        LocalDate recordedExpirationDate =
                detail.getRecordedExpirationDate();

        String condition =
                StockReviewCondition.normalize(
                        detail.getConditionStatus()
                );

        boolean issue =
                isIssue(
                        detail,
                        type
                );

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

                discrepancyCssClass(
                        discrepancy
                ),

                condition,

                StockReviewCondition.label(
                        condition
                ),

                conditionCssClass(
                        condition
                ),

                issue,
                detail.getNote()
        );
    }

    private StockReviewVoucherPrintLineResponse
    toVoucherPrintLine(
            Stockreviewdetail detail,
            String type
    ) {
        Product product =
                detail.getProductID();

        Batch batch =
                detail.getBatchID();

        int systemQty =
                safe(
                        detail.getSystemQty()
                );

        int actualQty =
                safe(
                        detail.getActualQty()
                );

        Integer discrepancy;

        if (StockReviewType.COUNT.equals(type)) {
            discrepancy =
                    resolvedDiscrepancy(
                            detail
                    );
        } else if (
                StockReviewType.CONDITION.equals(type)
        ) {
            /*
             * Với CONDITION:
             * - null: dòng nháp chưa nhập.
             * - 0: người dùng đã nhập.
             */
            discrepancy =
                    detail.getDiscrepancy();
        } else {
            discrepancy = 0;
        }

        BigDecimal unitCost =
                batch != null
                        && batch.getImportPricePerBase() != null
                        ? batch.getImportPricePerBase()
                        : BigDecimal.ZERO;

        BigDecimal discrepancyValue =
                StockReviewType.COUNT.equals(type)
                        ? unitCost.multiply(
                        BigDecimal.valueOf(
                                discrepancy
                        )
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

                StockReviewCondition.label(
                        condition
                ),

                isIssue(
                        detail,
                        type
                ),

                detail.getNote()
        );
    }

    private StockReviewBatchCandidateResponse
    toBatchCandidate(
            Batch batch,
            List<String> positions
    ) {
        Product product =
                batch.getProductID();

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

                batch.getStorageQuantity(),

                product != null
                        && product.getTypeID() != null
                        ? product.getTypeID().getId()
                        : null,

                product != null
                        && product.getTypeID() != null
                        ? product.getTypeID().getName()
                        : "Chưa phân loại",

                positions == null
                        ? List.of()
                        : positions
        );
    }

    /**
     * Kiểm tra dữ liệu tạo mới cho cả ba loại rà soát.
     *
     * requireComplete = false khi lưu nháp.
     * requireComplete = true khi gửi phiếu.
     */
    private String validateCreateRequest(
            StockReviewCreateRequest request,
            boolean requireComplete
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Dữ liệu phiếu rà soát kho không hợp lệ"
            );
        }

        String type =
                StockReviewType.normalize(
                        request.getType()
                );

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

            /*
             * Nếu đã nhập số lượng thì luôn phải kiểm tra
             * không âm, kể cả khi lưu nháp.
             */
            if (StockReviewType.COUNT.equals(type)
                    && item.getActualQty() != null
                    && item.getActualQty() < 0) {
                throw new IllegalArgumentException(
                        "Số lượng thực tế không được âm"
                );
            }

            /*
             * Chỉ bắt buộc số lượng thực tế khi gửi phiếu.
             */
            if (requireComplete
                    && StockReviewType.COUNT.equals(type)
                    && item.getActualQty() == null) {
                throw new IllegalArgumentException(
                        "Vui lòng nhập đầy đủ số lượng thực tế "
                                + "trước khi gửi phiếu"
                );
            }

            /*
             * Khi lưu nháp, hạn dùng thực tế được phép null.
             */
            if (requireComplete
                    && StockReviewType.DATE.equals(type)
                    && item.getActualExpirationDate() == null) {
                throw new IllegalArgumentException(
                        "Vui lòng nhập đầy đủ hạn dùng thực tế "
                                + "trước khi gửi phiếu"
                );
            }

            if (StockReviewType.CONDITION.equals(type)) {
                if (item.getCompliantQty() != null
                        && item.getCompliantQty() < 0) {
                    throw new IllegalArgumentException(
                            "Số lượng đạt chuẩn không được âm"
                    );
                }

                if (item.getNonCompliantQty() != null
                        && item.getNonCompliantQty() < 0) {
                    throw new IllegalArgumentException(
                            "Số lượng không đạt chuẩn không được âm"
                    );
                }

                /*
                 * Lưu nháp được phép để trống.
                 * Gửi phiếu mới bắt buộc nhập cả hai.
                 */
                if (requireComplete
                        && (
                        item.getCompliantQty() == null
                                || item.getNonCompliantQty()
                                == null
                )) {
                    throw new IllegalArgumentException(
                            "Vui lòng nhập đầy đủ số lượng đạt "
                                    + "chuẩn và không đạt chuẩn "
                                    + "trước khi gửi phiếu"
                    );
                }
            }
        }

        return type;
    }

    /**
     * Một dòng CONDITION chỉ được tính là có vấn đề khi:
     * - Tình trạng là NON_COMPLIANT.
     * - Số lượng không đạt chuẩn lớn hơn 0.
     */
    private boolean isIssue(
            Stockreviewdetail detail,
            String type
    ) {
        return switch (
                StockReviewType.normalize(type)
                ) {
            case StockReviewType.COUNT ->
                    resolvedDiscrepancy(detail)
                            != 0;

            case StockReviewType.DATE ->
                    !Objects.equals(
                            detail.getRecordedExpirationDate(),
                            detail.getActualExpirationDate()
                    );

            case StockReviewType.CONDITION ->
                    StockReviewCondition.NON_COMPLIANT.equals(
                            StockReviewCondition.normalize(
                                    detail.getConditionStatus()
                            )
                    )
                            && safe(
                            detail.getActualQty()
                    ) > 0;

            default -> false;
        };
    }

    /**
     * CONDITION tạo hai detail cho một batch,
     * vì vậy phải đếm số batch riêng biệt.
     */
    private long countReviewedBatches(
            List<Stockreviewdetail> details,
            String type
    ) {
        if (!StockReviewType.CONDITION.equals(
                type
        )) {
            return details.size();
        }

        return details.stream()
                .map(
                        Stockreviewdetail::getBatchID
                )
                .filter(Objects::nonNull)
                .map(Batch::getId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    /**
     * systemQty của CONDITION được lặp trên hai detail.
     * Chỉ cộng một lần cho mỗi batch.
     */
    private int totalSystemQty(
            List<Stockreviewdetail> details,
            String type
    ) {
        if (!StockReviewType.CONDITION.equals(
                type
        )) {
            return details.stream()
                    .map(
                            Stockreviewdetail::getSystemQty
                    )
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .sum();
        }

        return details.stream()
                .filter(detail ->
                        detail.getBatchID() != null
                                && detail
                                .getBatchID()
                                .getId() != null
                )
                .collect(
                        Collectors.toMap(
                                detail ->
                                        detail
                                                .getBatchID()
                                                .getId(),

                                detail ->
                                        safe(
                                                detail.getSystemQty()
                                        ),

                                (first, ignored) -> first,
                                LinkedHashMap::new
                        )
                )
                .values()
                .stream()
                .mapToInt(Integer::intValue)
                .sum();
    }
    /**
     * Với phiếu CONDITION, sắp xếp:
     * 1. COMPLIANT — Đạt chuẩn.
     * 2. NON_COMPLIANT — Không đạt chuẩn.
     */
    private Comparator<Stockreviewdetail>
    conditionDetailComparator(
            String type
    ) {
        if (!StockReviewType.CONDITION.equals(
                type
        )) {
            return (left, right) -> 0;
        }

        return Comparator
                .comparing(
                        (Stockreviewdetail detail) ->
                                detail.getBatchID() == null
                                        || detail
                                        .getBatchID()
                                        .getId() == null
                                        ? Integer.MAX_VALUE
                                        : detail
                                        .getBatchID()
                                        .getId()
                )
                .thenComparingInt(detail ->
                        StockReviewCondition.COMPLIANT.equals(
                                StockReviewCondition.normalize(
                                        detail.getConditionStatus()
                                )
                        )
                                ? 0
                                : 1
                );
    }

    private int resolvedDiscrepancy(
            Stockreviewdetail detail
    ) {
        return detail.getDiscrepancy() == null
                ? safe(
                detail.getActualQty()
        )
                - safe(
                detail.getSystemQty()
        )
                : detail.getDiscrepancy();
    }

    private boolean matchesKeyword(
            Stockreview review,
            List<Stockreviewdetail> details,
            String keyword
    ) {
        if (keyword == null
                || keyword.isBlank()) {
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
                        ? review
                        .getCreatedBy()
                        .getName()
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

                    boolean matchesProduct =
                            product != null
                                    && (
                                    containsNormalized(
                                            String.valueOf(
                                                    product
                                                            .getProductID()
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

                    boolean matchesBatch =
                            batch != null
                                    && containsNormalized(
                                    batch.getLotNumber(),
                                    keyword
                            );

                    return matchesProduct
                            || matchesBatch;
                });
    }

    private boolean matchesBatchKeyword(
            Batch batch,
            String keyword
    ) {
        if (keyword == null
                || keyword.isBlank()) {
            return true;
        }

        Product product =
                batch.getProductID();

        boolean matchesBatch =
                containsNormalized(
                        batch.getLotNumber(),
                        keyword
                );

        boolean matchesProduct =
                product != null
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

        return matchesBatch
                || matchesProduct;
    }

    private boolean matchesDate(
            Stockreview review,
            LocalDate from,
            LocalDate to
    ) {
        if (from == null
                && to == null) {
            return true;
        }

        if (review.getReviewDate() == null) {
            return false;
        }

        LocalDate reviewDate =
                toLocalDate(
                        review.getReviewDate()
                );

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
                                .normalize(
                                        review.getType()
                                )
                                .equals(type)
                )
                .count();
    }

    private String statusCssClass(
            String status
    ) {
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
                StockReviewCondition.normalize(
                        condition
                )
                ) {
            case StockReviewCondition.COMPLIANT ->
                    "text-success";

            case StockReviewCondition.NON_COMPLIANT ->
                    "text-danger";

            default ->
                    "text-secondary";
        };
    }

    private String temporaryCode() {
        return "TMP-SR-"
                + UUID.randomUUID();
    }

    private String formatReviewCode(
            Integer id
    ) {
        return "SR-"
                + String.format(
                "%06d",
                id == null
                        ? 0
                        : id
        );
    }

    private LocalDate parseDate(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }
    private LocalDate toLocalDate(
            Instant instant
    ) {
        return instant
                .atZone(
                        ZoneId.systemDefault()
                )
                .toLocalDate();
    }

    private String formatInstant(
            Instant instant
    ) {
        if (instant == null) {
            return "";
        }

        return DateTimeFormatter
                .ofPattern(
                        "dd/MM/yyyy HH:mm"
                )
                .withZone(
                        ZoneId.systemDefault()
                )
                .format(instant);
    }

    private String formatLocalDate(
            LocalDate date
    ) {
        return date == null
                ? ""
                : date.format(
                DateTimeFormatter.ofPattern(
                        "dd/MM/yyyy"
                )
        );
    }

    private int safe(
            Integer value
    ) {
        return value == null
                ? 0
                : value;
    }

    private boolean isStatus(
            String actual,
            String expected
    ) {
        return normalize(actual)
                .equals(
                        normalize(expected)
                );
    }

    private boolean containsNormalized(
            String value,
            String keyword
    ) {
        return value != null
                && normalize(value)
                .contains(keyword);
    }

    private String normalize(
            String value
    ) {
        if (value == null) {
            return "";
        }

        String normalized =
                Normalizer.normalize(
                        value,
                        Normalizer.Form.NFD
                );

        normalized =
                normalized.replaceAll(
                        "\\p{M}",
                        ""
                );

        normalized =
                normalized
                        .replace("Đ", "D")
                        .replace("đ", "d");

        return normalized
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private String trimToNull(
            String value
    ) {
        return value == null
                || value.isBlank()
                ? null
                : value.trim();
    }
}