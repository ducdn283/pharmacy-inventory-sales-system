package com.example.project.service;

import com.example.project.constant.StockCountStatus;
import com.example.project.dto.request.StockReviewCreateRequest;
import com.example.project.dto.request.StockReviewItemRequest;
import com.example.project.dto.response.*;
import com.example.project.entity.*;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.BatchRepository;
import com.example.project.repository.StockreviewRepository;
import com.example.project.repository.StockreviewdetailRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.math.BigDecimal;

@Service
public class StockreviewService {

    private final StockreviewRepository stockreviewRepository;
    private final StockreviewdetailRepository stockreviewdetailRepository;
    private final BatchRepository batchRepository;
    private final AccountRepository accountRepository;
    private final WorkflowNotificationService workflowNotificationService;

    public StockreviewService(StockreviewRepository stockreviewRepository,
                              StockreviewdetailRepository stockreviewdetailRepository,
                              BatchRepository batchRepository,
                              AccountRepository accountRepository,
                              WorkflowNotificationService workflowNotificationService) {
        this.stockreviewRepository = stockreviewRepository;
        this.stockreviewdetailRepository = stockreviewdetailRepository;
        this.batchRepository = batchRepository;
        this.accountRepository = accountRepository;
        this.workflowNotificationService = workflowNotificationService;
    }

    @Transactional(readOnly = true)
    public Page<StockReviewListItemResponse> search(String keyword,
                                                    String fromDate,
                                                    String toDate,
                                                    String status,
                                                    Pageable pageable) {
        String normalizedKeyword = normalize(keyword);
        LocalDate from = parseDate(fromDate);
        LocalDate to = parseDate(toDate);

        List<Stockreview> counts = stockreviewRepository.findAllWithRelations();

        Map<Integer, List<Stockreviewdetail>> detailMap = stockreviewdetailRepository.findAllWithRelations()
                .stream()
                .filter(detail -> detail.getStockReviewID() != null && detail.getStockReviewID().getId() != null)
                .collect(Collectors.groupingBy(detail -> detail.getStockReviewID().getId()));

        List<StockReviewListItemResponse> rows = counts.stream()
                .filter(count -> matchesKeyword(count, detailMap.getOrDefault(count.getId(), List.of()), normalizedKeyword))
                .filter(count -> matchesDate(count, from, to))
                .filter(count -> status == null || status.isBlank() || isStatus(count.getStatus(), status))
                .map(count -> toListItem(count, detailMap.getOrDefault(count.getId(), List.of())))
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), rows.size());

        List<StockReviewListItemResponse> content = start >= rows.size()
                ? List.of()
                : rows.subList(start, end);

        return new PageImpl<>(content, pageable, rows.size());
    }

    @Transactional(readOnly = true)
    public StockReviewStatsResponse getStats() {
        List<Stockreview> counts = stockreviewRepository.findAllWithRelations();

        return new StockReviewStatsResponse(
                counts.size(),
                countByStatus(counts, StockCountStatus.DRAFT),
                countByStatus(counts, StockCountStatus.PENDING),
                countByStatus(counts, StockCountStatus.APPROVED),
                countByStatus(counts, StockCountStatus.ADJUSTED)
        );
    }

    public List<String> listStatuses() {
        return StockCountStatus.ALL;
    }

    @Transactional(readOnly = true)
    public StockReviewCreateRequest buildDefaultForm() {
        StockReviewCreateRequest form = new StockReviewCreateRequest();

        for (StockReviewBatchCandidateResponse batch : listCountableBatches(null)) {
            StockReviewItemRequest item = new StockReviewItemRequest();
            item.setBatchId(batch.getBatchId());
            item.setActualQty(batch.getSystemQty());
            form.getItems().add(item);
        }

        return form;
    }

    @Transactional(readOnly = true)
    public List<StockReviewBatchCandidateResponse> listCountableBatches(String keyword) {
        String normalizedKeyword = normalize(keyword);

        return batchRepository.findAvailableBatchesForDestroy()
                .stream()
                .filter(batch -> matchesBatchKeyword(batch, normalizedKeyword))
                .map(this::toBatchCandidate)
                .toList();
    }

    @Transactional
    public Integer create(StockReviewCreateRequest request,
                          Integer currentAccountId,
                          boolean isOwner,
                          boolean asDraft) {
        validateCreateRequest(request);

        Account creator = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        Map<Integer, StockReviewItemRequest> itemMap = request.getItems()
                .stream()
                .collect(Collectors.toMap(
                        StockReviewItemRequest::getBatchId,
                        item -> item,
                        (first, second) -> second,
                        LinkedHashMap::new
                ));

        List<Batch> batches = batchRepository.findAllById(itemMap.keySet());

        if (batches.size() != itemMap.size()) {
            throw new IllegalArgumentException("Một số lô hàng không tồn tại");
        }

        String status = resolveCreateStatus(isOwner, asDraft);
        boolean approvedNow = isStatus(status, StockCountStatus.APPROVED);

        Stockreview stockCount = new Stockreview();
        stockCount.setStockCountCode(generateCode());
        stockCount.setReviewDate(Instant.now());
        stockCount.setCreatedBy(creator);
        stockCount.setStatus(status);
        stockCount.setNote(trimToNull(request.getNote()));

        if (approvedNow) {
            stockCount.setApprovedBy(creator);
            stockCount.setApprovedAt(Instant.now());
        }

        Stockreview saved = stockreviewRepository.save(stockCount);

        for (Batch batch : batches) {
            StockReviewItemRequest item = itemMap.get(batch.getId());

            int systemQty = batch.getStorageQuantity() == null ? 0 : batch.getStorageQuantity();
            int actualQty = item.getActualQty() == null ? systemQty : item.getActualQty();

            Stockreviewdetail detail = new Stockreviewdetail();
            detail.setStockReviewID(saved);
            detail.setProductID(batch.getProductID());
            detail.setBatchID(batch);
            detail.setSystemQty(systemQty);
            detail.setActualQty(actualQty);
            detail.setDiscrepancy(actualQty - systemQty);
            detail.setNote(trimToNull(item.getNote()));

            stockreviewdetailRepository.save(detail);
        }
        if (isStatus(saved.getStatus(), StockCountStatus.PENDING)) {
            workflowNotificationService
                    .stockCountPending(saved);
        }
        return saved.getId();
    }

    private String resolveCreateStatus(boolean isOwner, boolean asDraft) {
        if (asDraft) {
            return StockCountStatus.DRAFT;
        }

        return isOwner ? StockCountStatus.APPROVED : StockCountStatus.PENDING;
    }

    @Transactional(readOnly = true)
    public StockReviewDetailPageResponse getDetail(Integer stockCountId) {
        Stockreview count = stockreviewRepository.findByIdWithRelations(stockCountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kiểm kê"));

        List<Stockreviewdetail> details = stockreviewdetailRepository.findByStockCountIdWithRelations(stockCountId);

        List<StockReviewDetailItemResponse> items = details.stream()
                .map(this::toDetailItem)
                .toList();

        long totalItems = details.size();

        long discrepancyItems = details.stream()
                .filter(detail -> detail.getDiscrepancy() != null && detail.getDiscrepancy() != 0)
                .count();

        long matchedItems = totalItems - discrepancyItems;

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
                count.getId(),
                count.getStockCountCode(),
                count.getReviewDate(),
                formatInstant(count.getReviewDate()),
                count.getCreatedBy() != null ? count.getCreatedBy().getName() : "Không rõ",
                count.getApprovedBy() != null ? count.getApprovedBy().getName() : "Chưa có",
                formatInstant(count.getApprovedAt()),
                count.getStatus(),
                statusCssClass(count.getStatus()),
                count.getNote(),
                totalItems,
                matchedItems,
                discrepancyItems,
                totalSystemQty,
                totalActualQty,
                totalActualQty - totalSystemQty,
                items
        );
    }

    @Transactional
    public void submit(Integer stockCountId, Integer currentAccountId, boolean isOwner) {
        Stockreview count = stockreviewRepository.findByIdWithRelations(stockCountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kiểm kê"));

        if (!isStatus(count.getStatus(), StockCountStatus.DRAFT)) {
            throw new IllegalArgumentException("Chỉ có thể gửi phiếu kiểm kê đang ở trạng thái nháp");
        }

        Account actor = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        if (isOwner) {
            count.setStatus(StockCountStatus.APPROVED);
            count.setApprovedBy(actor);
            count.setApprovedAt(Instant.now());
        } else {
            count.setStatus(StockCountStatus.PENDING);
        }

        stockreviewRepository.save(count);
        if (!isOwner) {
            workflowNotificationService
                    .stockCountPending(count);
        }
    }

    @Transactional
    public void approve(Integer stockCountId, Integer ownerAccountId) {
        Stockreview count = stockreviewRepository.findByIdWithRelations(stockCountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kiểm kê"));

        if (!isStatus(count.getStatus(), StockCountStatus.PENDING)) {
            throw new IllegalArgumentException("Chỉ có thể duyệt phiếu kiểm kê đang chờ duyệt");
        }

        Account owner = accountRepository.findById(ownerAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        count.setStatus(StockCountStatus.APPROVED);
        count.setApprovedBy(owner);
        count.setApprovedAt(Instant.now());

        stockreviewRepository.save(count);
        workflowNotificationService.stockCountApproved(count);
    }

    @Transactional
    public void reject(Integer stockCountId, Integer ownerAccountId) {
        Stockreview count = stockreviewRepository.findByIdWithRelations(stockCountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kiểm kê"));

        if (!isStatus(count.getStatus(), StockCountStatus.PENDING)) {
            throw new IllegalArgumentException("Chỉ có thể từ chối phiếu kiểm kê đang chờ duyệt");
        }

        Account owner = accountRepository.findById(ownerAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        count.setStatus(StockCountStatus.REJECTED);
        count.setApprovedBy(owner);
        count.setApprovedAt(Instant.now());

        stockreviewRepository.save(count);
        workflowNotificationService.stockCountRejected(count);
    }

    @Transactional(readOnly = true)
    public StockReviewPrintPageResponse getPrintPage(String printedByName) {
        List<StockReviewPrintLineResponse> lines = batchRepository.findAvailableBatchesForDestroy()
                .stream()
                .map(batch -> {
                    Product product = batch.getProductID();

                    return new StockReviewPrintLineResponse(
                            product != null ? product.getProductID() : null,
                            product != null ? product.getCode() : "",
                            product != null ? product.getName() : "Không rõ",
                            batch.getLotNumber(),
                            formatLocalDate(batch.getExpirationDate()),
                            batch.getStorageQuantity()
                    );
                })
                .toList();

        return new StockReviewPrintPageResponse(
                LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                printedByName,
                lines.size(),
                lines
        );
    }

    private StockReviewListItemResponse toListItem(Stockreview count, List<Stockreviewdetail> details) {
        long discrepancyItems = details.stream()
                .filter(detail -> detail.getDiscrepancy() != null && detail.getDiscrepancy() != 0)
                .count();

        return new StockReviewListItemResponse(
                count.getId(),
                count.getStockCountCode(),
                count.getReviewDate(),
                formatInstant(count.getReviewDate()),
                count.getCreatedBy() != null ? count.getCreatedBy().getName() : "Không rõ",
                count.getApprovedBy() != null ? count.getApprovedBy().getName() : "Chưa có",
                formatInstant(count.getApprovedAt()),
                details.size(),
                discrepancyItems,
                count.getStatus(),
                statusCssClass(count.getStatus()),
                count.getNote()
        );
    }

    private StockReviewDetailItemResponse toDetailItem(Stockreviewdetail detail) {
        Product product = detail.getProductID();
        Batch batch = detail.getBatchID();

        Integer discrepancy = detail.getDiscrepancy() == null
                ? safe(detail.getActualQty()) - safe(detail.getSystemQty())
                : detail.getDiscrepancy();

        return new StockReviewDetailItemResponse(
                product != null ? product.getProductID() : null,
                product != null ? product.getCode() : "",
                product != null ? product.getName() : "Không rõ",
                batch != null ? batch.getId() : null,
                batch != null ? batch.getLotNumber() : "",
                batch != null ? batch.getExpirationDate() : null,
                batch != null ? formatLocalDate(batch.getExpirationDate()) : "",
                detail.getSystemQty(),
                detail.getActualQty(),
                discrepancy,
                discrepancyCssClass(discrepancy),
                detail.getNote()
        );
    }

    private StockReviewBatchCandidateResponse toBatchCandidate(Batch batch) {
        Product product = batch.getProductID();

        return new StockReviewBatchCandidateResponse(
                batch.getId(),
                product != null ? product.getProductID() : null,
                product != null ? product.getCode() : "",
                product != null ? product.getName() : "Không rõ",
                batch.getLotNumber(),
                batch.getExpirationDate(),
                formatLocalDate(batch.getExpirationDate()),
                batch.getStorageQuantity()
        );
    }

    @Transactional(readOnly = true)
    public StockReviewVoucherPrintPageResponse getVoucherPrintPage(Integer stockCountId) {
        Stockreview count = stockreviewRepository.findByIdWithRelations(stockCountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kiểm kê"));

        List<Stockreviewdetail> details =
                stockreviewdetailRepository.findByStockCountIdWithRelations(stockCountId);

        List<StockReviewVoucherPrintLineResponse> items = details.stream()
                .map(detail -> {
                    Product product = detail.getProductID();
                    Batch batch = detail.getBatchID();

                    int systemQty = safe(detail.getSystemQty());
                    int actualQty = safe(detail.getActualQty());

                    int discrepancy = detail.getDiscrepancy() == null
                            ? actualQty - systemQty
                            : detail.getDiscrepancy();

                    BigDecimal unitCost = batch != null && batch.getImportPricePerBase() != null
                            ? batch.getImportPricePerBase()
                            : BigDecimal.ZERO;

                    BigDecimal discrepancyValue = unitCost.multiply(BigDecimal.valueOf(discrepancy));

                    return new StockReviewVoucherPrintLineResponse(
                            product != null ? product.getProductID() : null,
                            product != null ? product.getCode() : "",
                            product != null ? product.getName() : "Không rõ",
                            batch != null ? batch.getLotNumber() : "",
                            batch != null ? formatLocalDate(batch.getExpirationDate()) : "",
                            systemQty,
                            actualQty,
                            discrepancy,
                            discrepancyValue
                    );
                })
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
                .map(StockReviewVoucherPrintLineResponse::getDiscrepancy)
                .filter(Objects::nonNull)
                .filter(value -> value > 0)
                .mapToInt(Integer::intValue)
                .sum();

        int totalShortageQty = items.stream()
                .map(StockReviewVoucherPrintLineResponse::getDiscrepancy)
                .filter(Objects::nonNull)
                .filter(value -> value < 0)
                .mapToInt(value -> Math.abs(value))
                .sum();

        int totalDiscrepancyQty = totalActualQty - totalSystemQty;

        BigDecimal totalDiscrepancyValue = items.stream()
                .map(StockReviewVoucherPrintLineResponse::getDiscrepancyValue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new StockReviewVoucherPrintPageResponse(
                count.getId(),
                count.getStockCountCode(),
                formatInstant(count.getReviewDate()),
                count.getStatus(),
                count.getCreatedBy() != null ? count.getCreatedBy().getName() : "Không rõ",
                count.getApprovedBy() != null ? count.getApprovedBy().getName() : "Chưa có",
                count.getApprovedAt() != null ? formatInstant(count.getApprovedAt()) : "Chưa có",
                count.getNote(),
                items.size(),
                totalSystemQty,
                totalActualQty,
                totalExcessQty,
                totalShortageQty,
                totalDiscrepancyQty,
                totalDiscrepancyValue,
                items
        );
    }

    private void validateCreateRequest(StockReviewCreateRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một lô hàng để kiểm kê");
        }

        for (StockReviewItemRequest item : request.getItems()) {
            if (item.getBatchId() == null) {
                throw new IllegalArgumentException("Dữ liệu lô hàng không hợp lệ");
            }

            if (item.getActualQty() == null || item.getActualQty() < 0) {
                throw new IllegalArgumentException("Số lượng thực tế không được để trống và không được âm");
            }
        }
    }

    private boolean matchesKeyword(Stockreview count,
                                   List<Stockreviewdetail> details,
                                   String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }

        if (containsNormalized(count.getStockCountCode(), keyword)
                || containsNormalized(count.getStatus(), keyword)
                || containsNormalized(count.getNote(), keyword)
                || containsNormalized(count.getCreatedBy() != null ? count.getCreatedBy().getName() : null, keyword)) {
            return true;
        }

        return details.stream().anyMatch(detail -> {
            Product product = detail.getProductID();
            Batch batch = detail.getBatchID();

            return product != null && (
                    containsNormalized(String.valueOf(product.getProductID()), keyword)
                            || containsNormalized(product.getCode(), keyword)
                            || containsNormalized(product.getName(), keyword)
                            || containsNormalized(product.getBarcode(), keyword))
                    || batch != null && containsNormalized(batch.getLotNumber(), keyword);
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
                        || containsNormalized(product.getCode(), keyword)
                        || containsNormalized(product.getName(), keyword)
                        || containsNormalized(product.getBarcode(), keyword));
    }

    private boolean matchesDate(Stockreview count, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }

        if (count.getReviewDate() == null) {
            return false;
        }

        LocalDate countDate = toLocalDate(count.getReviewDate());

        if (from != null && countDate.isBefore(from)) {
            return false;
        }

        return to == null || !countDate.isAfter(to);
    }

    private long countByStatus(List<Stockreview> counts, String status) {
        return counts.stream()
                .filter(count -> isStatus(count.getStatus(), status))
                .count();
    }

    private String statusCssClass(String status) {
        if (isStatus(status, StockCountStatus.APPROVED)) {
            return "status-approved";
        }

        if (isStatus(status, StockCountStatus.ADJUSTED)) {
            return "status-adjusted";
        }

        if (isStatus(status, StockCountStatus.PENDING)) {
            return "status-pending";
        }

        if (isStatus(status, StockCountStatus.REJECTED)) {
            return "status-rejected";
        }

        if (isStatus(status, StockCountStatus.DRAFT)) {
            return "status-draft";
        }

        return "status-default";
    }

    private String discrepancyCssClass(Integer discrepancy) {
        if (discrepancy == null || discrepancy == 0) {
            return "text-secondary";
        }

        return discrepancy > 0 ? "text-success" : "text-danger";
    }

    private String generateCode() {
        int nextId = stockreviewRepository.findAll()
                .stream()
                .map(Stockreview::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        return "SC-" + String.format("%06d", nextId);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private LocalDate toLocalDate(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).toLocalDate();
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

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private boolean isStatus(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    private boolean containsNormalized(String value, String keyword) {
        return value != null && normalize(value).contains(keyword);
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

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}