package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ExpenseType;
import com.example.project.constant.ReturnStatus;
import com.example.project.constant.ShiftReportStatus;
import com.example.project.constant.StockReviewStatus;
import com.example.project.constant.StockReviewType;
import com.example.project.dto.response.ApprovalItemResponse;
import com.example.project.dto.response.ApprovalStatsResponse;
import com.example.project.entity.Expense;
import com.example.project.entity.Return;
import com.example.project.entity.Shiftreport;
import com.example.project.entity.Stockreview;
import com.example.project.repository.ExpenseRepository;
import com.example.project.repository.ReturnRepository;
import com.example.project.repository.ShiftreportRepository;
import com.example.project.repository.StockreviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Tổng hợp danh sách phê duyệt dành cho Owner.
 *
 * Nghiệp vụ duyệt và từ chối vẫn được xử lý tại service
 * tương ứng của từng module.
 */
@Service
public class ApprovalService {

    private static final String TYPE_RETURN =
            "Trả hàng";

    private static final String TYPE_STOCK_REVIEW =
            "Rà soát kho";

    private static final String TYPE_SHIFT_REPORT =
            "Báo cáo ca";

    private static final String TYPE_EXPENSE =
            "Phiếu chi";

    private static final String TYPE_CODE_RETURN =
            "RETURN";

    private static final String TYPE_CODE_STOCK_REVIEW =
            "STOCK_REVIEW";

    private static final String TYPE_CODE_SHIFT_REPORT =
            "SHIFT_REPORT";

    private static final String TYPE_CODE_EXPENSE =
            "EXPENSE";

    /**
     * Phiếu đã xử lý tiếp tục xuất hiện trong ba ngày gần nhất.
     */
    private static final Duration RESOLVED_LOOKBACK =
            Duration.ofDays(3);

    private static final ZoneId VN_ZONE =
            ZoneId.of("Asia/Ho_Chi_Minh");

    private final ReturnRepository returnRepository;

    private final StockreviewRepository stockreviewRepository;

    private final ShiftreportRepository shiftreportRepository;

    private final ExpenseRepository expenseRepository;

    private final ReturnService returnService;

    private final StockreviewService stockreviewService;

    private final ShiftreportService shiftreportService;

    private final ExpenseService expenseService;

    public ApprovalService(
            ReturnRepository returnRepository,
            StockreviewRepository stockreviewRepository,
            ShiftreportRepository shiftreportRepository,
            ExpenseRepository expenseRepository,
            ReturnService returnService,
            StockreviewService stockreviewService,
            ShiftreportService shiftreportService,
            ExpenseService expenseService
    ) {
        this.returnRepository = returnRepository;
        this.stockreviewRepository = stockreviewRepository;
        this.shiftreportRepository = shiftreportRepository;
        this.expenseRepository = expenseRepository;
        this.returnService = returnService;
        this.stockreviewService = stockreviewService;
        this.shiftreportService = shiftreportService;
        this.expenseService = expenseService;
    }

    @Transactional(readOnly = true)
    public List<ApprovalItemResponse> list(
            String typeFilter
    ) {
        List<ApprovalItemResponse> items =
                new ArrayList<>();

        Instant cutoff =
                Instant.now().minus(RESOLVED_LOOKBACK);

        if (matchesType(
                typeFilter,
                TYPE_RETURN
        )) {
            returnRepository
                    .findAllWithRelations()
                    .stream()
                    .filter(ret ->
                            ret.getInvoiceID() != null
                    )
                    .filter(ret ->
                            isStatus(
                                    ret.getStatus(),
                                    ReturnStatus.PENDING
                            )
                                    || isStatus(
                                    ret.getStatus(),
                                    ReturnStatus.DEBT
                            )
                                    || isStatus(
                                    ret.getStatus(),
                                    ReturnStatus.REJECTED
                            )
                    )
                    .map(this::toApprovalItem)
                    .filter(item ->
                            item.isPending()
                                    || isWithinLookback(
                                    item.getRequestedAt(),
                                    cutoff
                            )
                    )
                    .forEach(items::add);
        }

        /*
         * Stock Adjustment không nằm trong danh sách phê duyệt.
         * Chỉ Stock Review được tổng hợp tại đây.
         */
        if (matchesType(
                typeFilter,
                TYPE_STOCK_REVIEW
        )) {
            stockreviewRepository
                    .findAllWithRelations()
                    .stream()
                    .filter(review ->
                            !isStatus(
                                    review.getStatus(),
                                    StockReviewStatus.DRAFT
                            )
                    )
                    .map(this::toApprovalItem)
                    .filter(item ->
                            item.isPending()
                                    || isWithinLookback(
                                    item.getRequestedAt(),
                                    cutoff
                            )
                    )
                    .forEach(items::add);
        }

        if (matchesType(
                typeFilter,
                TYPE_SHIFT_REPORT
        )) {
            shiftreportRepository
                    .findAllWithRelations()
                    .stream()
                    .filter(shift ->
                            !isStatus(
                                    shift.getStatus(),
                                    ShiftReportStatus.DRAFT
                            )
                    )
                    .map(this::toApprovalItem)
                    .filter(item ->
                            item.isPending()
                                    || isWithinLookback(
                                    item.getRequestedAt(),
                                    cutoff
                            )
                    )
                    .forEach(items::add);
        }

        if (matchesType(
                typeFilter,
                TYPE_EXPENSE
        )) {
            expenseRepository
                    .findAll()
                    .stream()
                    .filter(expense ->
                            !isStatus(
                                    expense.getStatus(),
                                    ExpenseStatus.DRAFT
                            )
                    )
                    .map(this::toApprovalItem)
                    .filter(item ->
                            item.isPending()
                                    || isWithinLookback(
                                    item.getRequestedAt(),
                                    cutoff
                            )
                    )
                    .forEach(items::add);
        }

        return items.stream()
                .sorted(
                        Comparator
                                .comparing(
                                        ApprovalItemResponse::isPending
                                )
                                .reversed()
                                .thenComparing(
                                        ApprovalItemResponse
                                                ::getRequestedAt,
                                        Comparator.nullsLast(
                                                Comparator.reverseOrder()
                                        )
                                )
                )
                .toList();
    }

    @Transactional(readOnly = true)
    public ApprovalStatsResponse getStats() {
        long returnCount =
                returnRepository
                        .findAllWithRelations()
                        .stream()
                        .filter(ret ->
                                ret.getInvoiceID() != null
                                        && isStatus(
                                        ret.getStatus(),
                                        ReturnStatus.PENDING
                                )
                        )
                        .count();

        long stockReviewCount =
                stockreviewRepository
                        .findAllWithRelations()
                        .stream()
                        .filter(review ->
                                isStatus(
                                        review.getStatus(),
                                        StockReviewStatus.PENDING
                                )
                        )
                        .count();

        long shiftReportCount =
                shiftreportRepository
                        .findAllWithRelations()
                        .stream()
                        .filter(shift ->
                                isStatus(
                                        shift.getStatus(),
                                        ShiftReportStatus.PENDING
                                )
                        )
                        .count();

        long expenseCount =
                expenseRepository
                        .findAll()
                        .stream()
                        .filter(expense ->
                                isStatus(
                                        expense.getStatus(),
                                        ExpenseStatus.PENDING
                                )
                        )
                        .count();

        return new ApprovalStatsResponse(
                returnCount
                        + stockReviewCount
                        + shiftReportCount
                        + expenseCount,
                returnCount,
                stockReviewCount,
                shiftReportCount,
                expenseCount
        );
    }

    public List<String> listTypes() {
        return List.of(
                TYPE_RETURN,
                TYPE_STOCK_REVIEW,
                TYPE_SHIFT_REPORT,
                TYPE_EXPENSE
        );
    }

    /**
     * Duyệt các phiếu có selector dạng CODE:id.
     *
     * Một phiếu lỗi hoặc đã được người khác xử lý sẽ được bỏ qua,
     * không làm dừng toàn bộ danh sách.
     */
    @Transactional
    public int bulkApprove(
            List<String> selectors,
            Integer ownerAccountId
    ) {
        if (selectors == null) {
            return 0;
        }

        int approved = 0;

        for (String selector : selectors) {
            if (selector == null
                    || !selector.contains(":")) {
                continue;
            }

            String[] parts =
                    selector.split(":", 2);

            try {
                Integer id =
                        Integer.valueOf(parts[1]);

                switch (parts[0]) {
                    case TYPE_CODE_RETURN ->
                            returnService.approve(id);

                    case TYPE_CODE_STOCK_REVIEW ->
                            stockreviewService.approve(
                                    id,
                                    ownerAccountId
                            );

                    case TYPE_CODE_SHIFT_REPORT ->
                            shiftreportService.approve(
                                    id,
                                    ownerAccountId
                            );

                    case TYPE_CODE_EXPENSE ->
                            expenseService.approve(
                                    id,
                                    ownerAccountId
                            );

                    default -> {
                        continue;
                    }
                }

                approved++;
            } catch (IllegalArgumentException ignored) {
                /*
                 * Bỏ qua phiếu không còn đủ điều kiện phê duyệt.
                 */
            }
        }

        return approved;
    }

    private boolean isWithinLookback(
            Instant requestedAt,
            Instant cutoff
    ) {
        return requestedAt != null
                && !requestedAt.isBefore(cutoff);
    }

    private ApprovalItemResponse toApprovalItem(
            Return ret
    ) {
        String id =
                String.valueOf(ret.getId());

        Instant requestedAt =
                normalizeVnEncoded(
                        ret.getReturnDate()
                );

        boolean pending =
                isStatus(
                        ret.getStatus(),
                        ReturnStatus.PENDING
                );

        return new ApprovalItemResponse(
                TYPE_RETURN,
                TYPE_CODE_RETURN + ":" + id,
                ret.getReturnCode(),
                ret.getReturnedBy() != null
                        ? ret.getReturnedBy().getName()
                        : "Không rõ",
                requestedAt,
                formatInstant(requestedAt),
                "Hoàn "
                        + formatMoney(
                        ret.getTotalRefund()
                ),
                ret.getStatus(),
                statusCssClass(ret.getStatus()),
                pending,
                "/owner/returns/" + id,
                "/owner/returns/"
                        + id
                        + "/approve",
                "/owner/returns/"
                        + id
                        + "/reject"
        );
    }

    private ApprovalItemResponse toApprovalItem(
            Stockreview review
    ) {
        String id =
                String.valueOf(review.getId());

        boolean pending =
                isStatus(
                        review.getStatus(),
                        StockReviewStatus.PENDING
                );

        String description =
                StockReviewType.label(
                        review.getType()
                );

        if (review.getNote() != null
                && !review.getNote().isBlank()) {
            description += " — "
                    + truncate(
                    review.getNote(),
                    60
            );
        }

        return new ApprovalItemResponse(
                TYPE_STOCK_REVIEW,
                TYPE_CODE_STOCK_REVIEW + ":" + id,
                review.getStockCountCode(),
                review.getCreatedBy() != null
                        ? review.getCreatedBy().getName()
                        : "Không rõ",
                review.getReviewDate(),
                formatInstant(
                        review.getReviewDate()
                ),
                description,
                review.getStatus(),
                statusCssClass(
                        review.getStatus()
                ),
                pending,
                "/owner/stock-reviews/" + id,
                "/owner/stock-reviews/"
                        + id
                        + "/approve",
                "/owner/stock-reviews/"
                        + id
                        + "/reject"
        );
    }

    private ApprovalItemResponse toApprovalItem(
            Shiftreport shift
    ) {
        String id =
                String.valueOf(shift.getId());

        Instant requestedAt =
                normalizeVnEncoded(
                        shift.getEndTime() != null
                                ? shift.getEndTime()
                                : shift.getStartTime()
                );

        boolean pending =
                isStatus(
                        shift.getStatus(),
                        ShiftReportStatus.PENDING
                );

        String discrepancy =
                shift.getCashDiscrepancy() != null
                        ? ", chênh lệch quỹ "
                        + formatMoney(
                        shift.getCashDiscrepancy()
                )
                        : "";

        return new ApprovalItemResponse(
                TYPE_SHIFT_REPORT,
                TYPE_CODE_SHIFT_REPORT + ":" + id,
                shift.getShiftReportCode(),
                shift.getCashierID() != null
                        ? shift.getCashierID().getName()
                        : "Không rõ",
                requestedAt,
                formatInstant(requestedAt),
                "Doanh thu "
                        + formatMoney(
                        shift.getTotalRevenue()
                )
                        + discrepancy,
                shift.getStatus(),
                statusCssClass(
                        shift.getStatus()
                ),
                pending,
                "/owner/shift-reports/" + id,
                "/owner/shift-reports/"
                        + id
                        + "/approve",
                "/owner/shift-reports/"
                        + id
                        + "/reject"
        );
    }

    private ApprovalItemResponse toApprovalItem(
            Expense expense
    ) {
        String id =
                String.valueOf(expense.getId());

        boolean pending =
                isStatus(
                        expense.getStatus(),
                        ExpenseStatus.PENDING
                );

        return new ApprovalItemResponse(
                TYPE_EXPENSE,
                TYPE_CODE_EXPENSE + ":" + id,
                "PC-"
                        + String.format(
                        "%06d",
                        expense.getId()
                ),
                expense.getApplicantID() != null
                        ? expense.getApplicantID().getName()
                        : "Không rõ",
                expense.getDate(),
                formatInstant(expense.getDate()),
                ExpenseType.vietnameseName(
                        expense.getExpenseType()
                )
                        + " — "
                        + formatMoney(
                        expense.getAmount()
                ),
                expense.getStatus(),
                statusCssClass(
                        expense.getStatus()
                ),
                pending,
                "/owner/expenses/" + id,
                "/owner/expenses/"
                        + id
                        + "/approve",
                "/owner/expenses/"
                        + id
                        + "/reject"
        );
    }

    private boolean matchesType(
            String typeFilter,
            String type
    ) {
        return typeFilter == null
                || typeFilter.isBlank()
                || type.equals(typeFilter);
    }

    private String formatMoney(
            BigDecimal value
    ) {
        BigDecimal amount =
                value == null
                        ? BigDecimal.ZERO
                        : value;

        return String.format(
                Locale.forLanguageTag("vi-VN"),
                "%,dđ",
                amount.longValue()
        );
    }

    private String truncate(
            String value,
            int maxLength
    ) {
        if (value == null) {
            return "";
        }

        String trimmed = value.trim();

        return trimmed.length() <= maxLength
                ? trimmed
                : trimmed.substring(0, maxLength)
                + "…";
    }

    /**
     * Một số module cũ lưu giờ Việt Nam như thể là UTC.
     * Stock Review sử dụng Instant thực tế nên không cần chuyển đổi.
     */
    private Instant normalizeVnEncoded(
            Instant vnEncoded
    ) {
        return vnEncoded == null
                ? null
                : vnEncoded.minus(
                Duration.ofHours(7)
        );
    }

    private String formatInstant(
            Instant instant
    ) {
        if (instant == null) {
            return "";
        }

        return DateTimeFormatter
                .ofPattern("dd/MM/yyyy HH:mm")
                .withZone(VN_ZONE)
                .format(instant);
    }

    private String statusCssClass(
            String status
    ) {
        String normalized = normalize(status);

        if (normalized.contains("cho duyet")
                || normalized.contains(
                "cho thanh toan"
        )) {
            return "status-pending";
        }

        if (normalized.contains("tu choi")
                || normalized.contains("da huy")) {
            return "status-rejected";
        }

        return "status-approved";
    }

    private boolean isStatus(
            String actual,
            String expected
    ) {
        return normalize(actual)
                .equals(normalize(expected));
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
}