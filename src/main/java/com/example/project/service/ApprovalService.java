package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ExpenseType;
import com.example.project.constant.PurchaseInvoiceStatus;
import com.example.project.constant.ReturnStatus;
import com.example.project.constant.ShiftReportStatus;
import com.example.project.constant.StockReviewStatus;
import com.example.project.constant.StockReviewType;
import com.example.project.dto.response.ApprovalItemResponse;
import com.example.project.dto.response.ApprovalStatsResponse;
import com.example.project.entity.Expense;
import com.example.project.entity.Purchaseinvoice;
import com.example.project.entity.Return;
import com.example.project.entity.Shiftreport;
import com.example.project.entity.Stockreview;
import com.example.project.repository.ExpenseRepository;
import com.example.project.repository.PurchaseinvoiceRepository;
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
 * Read-only aggregator for the Owner's unified Approve List. Approve/reject business logic still
 * lives in each module's own service (Return/StockReview/ShiftReport/Expense/PurchaseInvoice) — this
 * class only reads their PENDING + a recent window of resolved items for display, and dispatches the
 * bulk-approve action to each one's existing {@code approve(...)} method.
 */
@Service
public class ApprovalService {

    private static final String TYPE_RETURN = "Trả hàng";
    private static final String TYPE_STOCK_REVIEW = "Rà soát kho";
    private static final String TYPE_SHIFT_REPORT = "Báo cáo ca";
    private static final String TYPE_EXPENSE = "Phiếu chi";
    /**
     * Phiếu nhập hàng — phải qua một bước duyệt độc lập trước khi hàng thật sự vào kho, vì
     * {@code PurchaseinvoiceService.approvePurchaseInvoice} là nơi DUY NHẤT cộng tồn.
     */
    private static final String TYPE_PURCHASE_INVOICE = "Phiếu nhập";

    /** Short, stable codes for the bulk-approve checkbox value ("CODE:id") — distinct from the
     *  Vietnamese TYPE_* display labels used for the type filter dropdown. */
    private static final String TYPE_CODE_RETURN = "RETURN";
    private static final String TYPE_CODE_STOCK_REVIEW = "STOCK_REVIEW";
    private static final String TYPE_CODE_SHIFT_REPORT = "SHIFT_REPORT";
    private static final String TYPE_CODE_EXPENSE = "EXPENSE";
    private static final String TYPE_CODE_PURCHASE_INVOICE = "PURCHASE_INVOICE";

    /** How far back a resolved (Đã duyệt/Từ chối) item stays visible after being handled, so approving
     *  something doesn't make it vanish immediately — purely a display window, not a data retention rule. */
    private static final Duration RESOLVED_LOOKBACK = Duration.ofDays(3);

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ReturnRepository returnRepository;
    private final StockreviewRepository stockreviewRepository;
    private final ShiftreportRepository shiftreportRepository;
    private final ExpenseRepository expenseRepository;
    private final PurchaseinvoiceRepository purchaseinvoiceRepository;
    private final ReturnService returnService;
    private final StockreviewService stockreviewService;
    private final ShiftreportService shiftreportService;
    private final ExpenseService expenseService;
    private final PurchaseinvoiceService purchaseinvoiceService;

    public ApprovalService(ReturnRepository returnRepository,
                           StockreviewRepository stockreviewRepository,
                           ShiftreportRepository shiftreportRepository,
                           ExpenseRepository expenseRepository,
                           PurchaseinvoiceRepository purchaseinvoiceRepository,
                           ReturnService returnService,
                           StockreviewService stockreviewService,
                           ShiftreportService shiftreportService,
                           ExpenseService expenseService,
                           PurchaseinvoiceService purchaseinvoiceService) {
        this.returnRepository = returnRepository;
        this.stockreviewRepository = stockreviewRepository;
        this.shiftreportRepository = shiftreportRepository;
        this.expenseRepository = expenseRepository;
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.returnService = returnService;
        this.stockreviewService = stockreviewService;
        this.shiftreportService = shiftreportService;
        this.expenseService = expenseService;
        this.purchaseinvoiceService = purchaseinvoiceService;
    }

    @Transactional(readOnly = true)
    public List<ApprovalItemResponse> list(String typeFilter) {
        List<ApprovalItemResponse> items = new ArrayList<>();
        Instant cutoff = Instant.now().minus(RESOLVED_LOOKBACK);

        if (matchesType(typeFilter, TYPE_RETURN)) {
            returnRepository.findAllWithRelations().stream()
                    .filter(ret -> ret.getInvoiceID() != null)
                    // DEBT và COMPLETED đều nghĩa là "đã duyệt", chỉ khác còn nợ tiền hay không —
                    // thiếu COMPLETED thì phiếu duyệt xong mà cấn trừ hết nợ sẽ biến mất khỏi màn
                    // duyệt ngay lập tức, thay vì nằm lại đủ thời gian nhìn cho hết.
                    .filter(ret -> isStatus(ret.getStatus(), ReturnStatus.PENDING) || isStatus(ret.getStatus(), ReturnStatus.DEBT)
                            || isStatus(ret.getStatus(), ReturnStatus.COMPLETED)
                            || isStatus(ret.getStatus(), ReturnStatus.REJECTED))
                    .map(this::toApprovalItem)
                    .filter(item -> item.isPending() || isWithinLookback(item.getRequestedAt(), cutoff))
                    .forEach(items::add);
        }

        // Điều chỉnh kho KHÔNG còn ở đây: BA bỏ bước duyệt (chỉ Owner tạo, tự chịu trách nhiệm) nên
        // phiếu đi thẳng Nháp → Hoàn thành, không bao giờ có trạng thái chờ duyệt để gom vào đây.

        if (matchesType(typeFilter, TYPE_STOCK_REVIEW)) {
            stockreviewRepository.findAllWithRelations().stream()
                    .filter(count -> !isStatus(count.getStatus(), StockReviewStatus.DRAFT))
                    .map(this::toApprovalItem)
                    .filter(item -> item.isPending() || isWithinLookback(item.getRequestedAt(), cutoff))
                    .forEach(items::add);
        }

        if (matchesType(typeFilter, TYPE_SHIFT_REPORT)) {
            shiftreportRepository.findAllWithRelations().stream()
                    .filter(shift -> !isStatus(shift.getStatus(), ShiftReportStatus.DRAFT))
                    .map(this::toApprovalItem)
                    .filter(item -> item.isPending() || isWithinLookback(item.getRequestedAt(), cutoff))
                    .forEach(items::add);
        }

        if (matchesType(typeFilter, TYPE_EXPENSE)) {
            expenseRepository.findAll().stream()
                    .filter(expense -> !isStatus(expense.getStatus(), ExpenseStatus.DRAFT))
                    .map(this::toApprovalItem)
                    .filter(item -> item.isPending() || isWithinLookback(item.getRequestedAt(), cutoff))
                    .forEach(items::add);
        }

        if (matchesType(typeFilter, TYPE_PURCHASE_INVOICE)) {
            // Phiếu Nháp chưa nộp và phiếu Đã hủy không phải việc của người duyệt. Từ chối một phiếu
            // nhập đưa nó VỀ Nháp (không hủy), nên phiếu vừa bị từ chối cũng không hiện lại ở đây —
            // khác 4 loại kia (có trạng thái "Từ chối" riêng để còn nhìn thấy trong 3 ngày).
            purchaseinvoiceRepository.findAll().stream()
                    .filter(invoice -> !isStatus(invoice.getStatus(), PurchaseInvoiceStatus.DRAFT)
                            && !isStatus(invoice.getStatus(), PurchaseInvoiceStatus.CANCELLED))
                    .map(this::toApprovalItem)
                    .filter(item -> item.isPending() || isWithinLookback(item.getRequestedAt(), cutoff))
                    .forEach(items::add);
        }

        return items.stream()
                .sorted(Comparator.comparing(ApprovalItemResponse::isPending).reversed()
                        .thenComparing(ApprovalItemResponse::getRequestedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ApprovalStatsResponse getStats() {
        long returnCount = returnRepository.findAllWithRelations().stream()
                .filter(ret -> ret.getInvoiceID() != null && isStatus(ret.getStatus(), ReturnStatus.PENDING))
                .count();
        long stockReviewCount = stockreviewRepository.findAllWithRelations().stream()
                .filter(count -> isStatus(count.getStatus(), StockReviewStatus.PENDING))
                .count();
        long shiftReportCount = shiftreportRepository.findAllWithRelations().stream()
                .filter(shift -> isStatus(shift.getStatus(), ShiftReportStatus.PENDING))
                .count();
        long expenseCount = expenseRepository.findAll().stream()
                .filter(expense -> isStatus(expense.getStatus(), ExpenseStatus.PENDING))
                .count();
        long purchaseInvoiceCount = purchaseinvoiceRepository.findAll().stream()
                .filter(invoice -> isStatus(invoice.getStatus(), PurchaseInvoiceStatus.PENDING_APPROVAL))
                .count();

        return new ApprovalStatsResponse(
                returnCount + stockReviewCount + shiftReportCount + expenseCount + purchaseInvoiceCount,
                returnCount,
                stockReviewCount,
                shiftReportCount,
                expenseCount,
                purchaseInvoiceCount
        );
    }

    public List<String> listTypes() {
        return List.of(TYPE_RETURN, TYPE_PURCHASE_INVOICE, TYPE_STOCK_REVIEW, TYPE_SHIFT_REPORT, TYPE_EXPENSE);
    }

    /** Approves every "CODE:id" selector the Owner checked, dispatching to each module's own approve().
     *  One bad/stale row doesn't abort the rest — returns how many actually succeeded. */
    @Transactional
    public int bulkApprove(List<String> selectors, Integer ownerAccountId) {
        if (selectors == null) {
            return 0;
        }
        int approved = 0;
        for (String selector : selectors) {
            if (selector == null || !selector.contains(":")) {
                continue;
            }
            String[] parts = selector.split(":", 2);
            try {
                Integer id = Integer.valueOf(parts[1]);
                switch (parts[0]) {
                    case TYPE_CODE_RETURN -> returnService.approve(id);
                    case TYPE_CODE_STOCK_REVIEW -> stockreviewService.approve(id, ownerAccountId);
                    case TYPE_CODE_SHIFT_REPORT -> shiftreportService.approve(id, ownerAccountId);
                    case TYPE_CODE_EXPENSE -> expenseService.approve(id, ownerAccountId);
                    case TYPE_CODE_PURCHASE_INVOICE -> purchaseinvoiceService.approvePurchaseInvoice(id);
                    default -> {
                        continue;
                    }
                }
                approved++;
            } catch (IllegalArgumentException ignored) {
                // Skip rows that are already resolved by someone else or otherwise no longer approvable.
            }
        }
        return approved;
    }

    private boolean isWithinLookback(Instant requestedAt, Instant cutoff) {
        return requestedAt != null && !requestedAt.isBefore(cutoff);
    }

    private ApprovalItemResponse toApprovalItem(Return ret) {
        String id = String.valueOf(ret.getId());
        Instant requestedAt = normalizeVnEncoded(ret.getReturnDate());
        boolean pending = isStatus(ret.getStatus(), ReturnStatus.PENDING);
        return new ApprovalItemResponse(
                TYPE_RETURN,
                TYPE_CODE_RETURN + ":" + id,
                ret.getReturnCode(),
                ret.getReturnedBy() != null ? ret.getReturnedBy().getName() : "Không rõ",
                requestedAt,
                formatInstant(requestedAt),
                "Hoàn " + formatMoney(ret.getTotalRefund()),
                ret.getStatus(),
                statusCssClass(ret.getStatus()),
                pending,
                "/owner/returns/" + id,
                "/owner/returns/" + id + "/approve",
                "/owner/returns/" + id + "/reject"
        );
    }

    private ApprovalItemResponse toApprovalItem(Stockreview count) {
        String id = String.valueOf(count.getId());
        boolean pending = isStatus(count.getStatus(), StockReviewStatus.PENDING);
        return new ApprovalItemResponse(
                TYPE_STOCK_REVIEW,
                TYPE_CODE_STOCK_REVIEW + ":" + id,
                count.getStockCountCode(),
                count.getCreatedBy() != null ? count.getCreatedBy().getName() : "Không rõ",
                count.getReviewDate(),
                formatInstant(count.getReviewDate()),
                // Ba loại phiếu rà soát kho (đếm số lượng / hạn dùng / tình trạng) đều vào chung nhóm này,
                // mà hệ quả của chúng khác hẳn nhau — duyệt phiếu hạn dùng là cho phép ghi đè hạn dùng
                // của lô. Nên loại phải hiện ngay trên dòng, không bắt người duyệt mở từng phiếu ra xem.
                summaryOf(count),
                count.getStatus(),
                statusCssClass(count.getStatus()),
                pending,
                "/owner/stock-reviews/" + id,
                "/owner/stock-reviews/" + id + "/approve",
                "/owner/stock-reviews/" + id + "/reject"
        );
    }

    private ApprovalItemResponse toApprovalItem(Shiftreport shift) {
        String id = String.valueOf(shift.getId());
        Instant requestedAt = normalizeVnEncoded(shift.getEndTime() != null ? shift.getEndTime() : shift.getStartTime());
        boolean pending = isStatus(shift.getStatus(), ShiftReportStatus.PENDING);
        String discrepancy = shift.getCashDiscrepancy() != null
                ? ", chênh lệch quỹ " + formatMoney(shift.getCashDiscrepancy())
                : "";
        return new ApprovalItemResponse(
                TYPE_SHIFT_REPORT,
                TYPE_CODE_SHIFT_REPORT + ":" + id,
                shift.getShiftReportCode(),
                shift.getCashierID() != null ? shift.getCashierID().getName() : "Không rõ",
                requestedAt,
                formatInstant(requestedAt),
                "Doanh thu " + formatMoney(shift.getTotalRevenue()) + discrepancy,
                shift.getStatus(),
                statusCssClass(shift.getStatus()),
                pending,
                "/owner/shift-reports/" + id,
                "/owner/shift-reports/" + id + "/approve",
                "/owner/shift-reports/" + id + "/reject"
        );
    }

    private ApprovalItemResponse toApprovalItem(Expense expense) {
        String id = String.valueOf(expense.getId());
        boolean pending = isStatus(expense.getStatus(), ExpenseStatus.PENDING);
        return new ApprovalItemResponse(
                TYPE_EXPENSE,
                TYPE_CODE_EXPENSE + ":" + id,
                "PC-" + String.format("%06d", expense.getId()),
                expense.getApplicantID() != null ? expense.getApplicantID().getName() : "Không rõ",
                expense.getDate(),
                formatInstant(expense.getDate()),
                ExpenseType.vietnameseName(expense.getExpenseType()) + " — " + formatMoney(expense.getAmount()),
                expense.getStatus(),
                statusCssClass(expense.getStatus()),
                pending,
                "/owner/expenses/" + id,
                "/owner/expenses/" + id + "/approve",
                "/owner/expenses/" + id + "/reject"
        );
    }

    /**
     * Phiếu nhập chờ duyệt. Mốc thời gian dùng {@code date} (ngày lập phiếu) — bảng
     * {@code purchaseinvoice} không có cột "thời điểm nộp duyệt" riêng, và {@code approvedAt} chỉ có
     * giá trị SAU khi duyệt nên không sắp xếp được hàng đang chờ.
     */
    private ApprovalItemResponse toApprovalItem(Purchaseinvoice invoice) {
        String id = String.valueOf(invoice.getId());
        boolean pending = isStatus(invoice.getStatus(), PurchaseInvoiceStatus.PENDING_APPROVAL);
        String supplier = invoice.getSupplierID() != null ? invoice.getSupplierID().getName() : "Không rõ NCC";
        return new ApprovalItemResponse(
                TYPE_PURCHASE_INVOICE,
                TYPE_CODE_PURCHASE_INVOICE + ":" + id,
                invoice.getPurchaseInvoiceCode(),
                invoice.getEmployeeID() != null ? invoice.getEmployeeID().getName() : "Không rõ",
                invoice.getDate(),
                formatInstant(invoice.getDate()),
                supplier + " — " + formatMoney(invoice.getTotalAmount()),
                invoice.getStatus(),
                statusCssClass(invoice.getStatus()),
                pending,
                "/owner/purchase-invoices/" + id,
                "/owner/purchase-invoices/" + id + "/approve",
                "/owner/purchase-invoices/" + id + "/reject"
        );
    }

    /** "Rà soát hạn dùng" hoặc "Kiểm đếm số lượng — {ghi chú}" — loại luôn đứng trước ghi chú. */
    private String summaryOf(Stockreview count) {
        String typeLabel = StockReviewType.label(count.getType());
        String note = count.getNote();
        return note != null && !note.isBlank()
                ? typeLabel + " — " + truncate(note, 45)
                : typeLabel;
    }

    private boolean matchesType(String typeFilter, String type) {
        return typeFilter == null || typeFilter.isBlank() || type.equals(typeFilter);
    }

    private String formatMoney(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value;
        return String.format(Locale.forLanguageTag("vi-VN"), "%,dđ", amount.longValue());
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "…";
    }

    /**
     * Return.returnDate and Shiftreport.startTime/endTime are written via a "nowVn()" trick (VN
     * wall-clock digits stored as if they were UTC — see ReturnService/ShiftreportService) while
     * Stockadjustment.date and Stockreview.reviewDate are genuine {@code Instant.now()} UTC values.
     * Undoing the VN encoding here means every {@code requestedAt} in this aggregator ends up as a
     * real UTC instant — required both to sort/window-filter the 4 sources together correctly and to
     * format them all the same way below.
     */
    private Instant normalizeVnEncoded(Instant vnEncoded) {
        return vnEncoded == null ? null : vnEncoded.minus(Duration.ofHours(7));
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(VN_ZONE)
                .format(instant);
    }

    private String statusCssClass(String status) {
        String normalized = normalize(status);
        // "Chờ thanh toán" (Expense approved, not fully paid yet) shows amber like a pending state.
        if (normalized.contains("cho duyet") || normalized.contains("cho thanh toan")) {
            return "status-pending";
        }
        if (normalized.contains("tu choi") || normalized.contains("da huy")) {
            return "status-rejected";
        }
        return "status-approved";
    }

    private boolean isStatus(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
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
