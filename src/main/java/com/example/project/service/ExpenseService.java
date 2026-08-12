package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ExpenseType;
import com.example.project.constant.ReturnStatus;
import com.example.project.constant.RoleConstants;
import com.example.project.dto.request.ExpenseCreateRequest;
import com.example.project.dto.response.ExpenseDetailResponse;
import com.example.project.dto.response.ExpenseListItemResponse;
import com.example.project.dto.response.ExpenseReferenceOptionResponse;
import com.example.project.dto.response.ExpenseStatsResponse;
import com.example.project.entity.Account;
import com.example.project.entity.Customer;
import com.example.project.entity.Expense;
import com.example.project.entity.Invoice;
import com.example.project.entity.Purchaseinvoice;
import com.example.project.entity.Return;
import com.example.project.entity.Shiftreport;
import com.example.project.entity.Supplier;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.AccountpermissionRepository;
import com.example.project.repository.ExpenseRepository;
import com.example.project.repository.ReturnRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Expense ("Phiếu chi") — the pharmacy's cash-outflow control screen. Most slips are plain manual
 * entry, but a {@link ExpenseType#RETURN_REFUND_PAYOUT} slip is the real payout leg of a
 * <em>customer</em> return, and a {@link ExpenseType#GOODS_PAYMENT} slip is the real payment leg of
 * a purchase invoice.
 *
 * <p><strong>Customer vs. supplier returns.</strong> The discriminator used consistently across
 * {@code ReturnService}, {@code ApprovalService}, {@code IncomeService} and
 * {@code ShiftreportService} is the FK: {@code invoiceID != null} is a customer return,
 * {@code purchaseID != null && invoiceID == null} is a supplier one. Expense only ever touches the
 * former (the pharmacy pays the customer back); the latter is money coming <em>in</em> and belongs
 * to {@code IncomeService.listSupplierReturns()}, the exact mirror of
 * {@link #listCustomerReturns()}. Returning goods to a supplier costs no cash, so it is out of
 * scope here by design.</p>
 *
 * <p><strong>A return slip computes, it does not pay.</strong> A {@code Return} row only carries
 * {@code totalRefund} — no cash/banking/credit split. Deciding how the money physically leaves —
 * and recording that it did — is entirely this module's job, which is what makes
 * {@link #listCustomerReturns()} the single gateway to paying a customer back.
 * {@code ShiftreportService} relies on the same thing: an Expense slip is a shift's <em>only</em>
 * source of cash-out.</p>
 *
 * <p><strong>Paying a supplier.</strong> A {@link ExpenseType#GOODS_PAYMENT} slip can point at a
 * {@code PurchaseInvoice} and is the real payment leg for it — including money still owed, since
 * that debt is the import invoice itself (see {@link ExpenseType#PURCHASE_LINKABLE}). Only once the
 * Owner confirms the money actually left (see {@link #confirmPayment}) does the slip push money onto
 * {@code Purchaseinvoice.paid} via
 * {@link PurchaseinvoiceService#applyPayment(Integer, java.math.BigDecimal)}, which re-derives and
 * stores the invoice's status in the same transaction. Money is only ever considered disbursed once
 * the slip is {@link ExpenseStatus#COMPLETED} — see {@link #disbursedAmount}.</p>
 *
 * <p><strong>Shift attachment.</strong> A slip is stamped with the actor's open shift at the moment
 * the money is <em>actually paid</em> (not merely approved), so the register can be reconciled —
 * see {@link #attachOpenShift}. A shift's whole {@code totalCashOut} is the sum of its slips'
 * {@code paidByCash}, so this stamp is load-bearing: a slip left unstamped is cash the register can
 * never account for.</p>
 *
 * <p><strong>Approval and real payment are two separate steps (BA 2026-08).</strong> Workflow
 * mirrors {@code StockadjustmentService}'s draft/submit/approve/reject shape, plus a real payment
 * step: {@link #createExpense}/{@link #submit}/{@link #approve} only ever move a slip as far as
 * {@link ExpenseStatus#AWAITING_PAYMENT} — approved, but nothing has left the drawer/bank account
 * yet. Only {@link #confirmPayment}, Owner-only regardless of who raised or approved the slip, moves
 * it the rest of the way to {@link ExpenseStatus#COMPLETED} and triggers the money-moving side
 * effects above. {@link ExpenseStatus#CANCELLED} is reachable right up until real payment — see
 * {@link #cancel} — but not after, since by then there is real money to un-ring the bell on.</p>
 */
@Service
public class ExpenseService {

    private static final String PAYMENT_CASH = "CASH";
    private static final String PAYMENT_BANKING = "BANKING";
    private static final String PAYMENT_MIXED = "MIXED";
    private static final String PAYMENT_CREDIT = "CREDIT";

    private final ExpenseRepository expenseRepository;
    private final AccountRepository accountRepository;
    private final ReturnRepository returnRepository;
    private final AccountpermissionRepository accountpermissionRepository;
    private final PurchaseinvoiceService purchaseinvoiceService;
    private final ShiftreportService shiftreportService;
    private final WorkflowNotificationService workflowNotificationService;
    private final FinancialsettingService financialsettingService;
    // @Lazy vì ReturnService đã inject ExpenseService (chiều ngược) — cần phá vòng lặp bean. Chỉ
    // dùng ở confirmPayment() để báo ReturnService đồng bộ lại trạng thái sau khi tiền thực chi.
    private final ReturnService returnService;

    public ExpenseService(ExpenseRepository expenseRepository,
                          AccountRepository accountRepository,
                          ReturnRepository returnRepository,
                          AccountpermissionRepository accountpermissionRepository,
                          PurchaseinvoiceService purchaseinvoiceService,
                          ShiftreportService shiftreportService,
                          WorkflowNotificationService workflowNotificationService,
                          FinancialsettingService financialsettingService,
                          @Lazy ReturnService returnService) {
        this.expenseRepository = expenseRepository;
        this.accountRepository = accountRepository;
        this.returnRepository = returnRepository;
        this.accountpermissionRepository = accountpermissionRepository;
        this.purchaseinvoiceService = purchaseinvoiceService;
        this.shiftreportService = shiftreportService;
        this.workflowNotificationService = workflowNotificationService;
        this.financialsettingService = financialsettingService;
        this.returnService = returnService;
    }

    // ------------------------------------------------------------------ list / search

    @Transactional(readOnly = true)
    public Page<ExpenseListItemResponse> search(String keyword,
                                                 String fromDate,
                                                 String toDate,
                                                 String expenseType,
                                                 String status,
                                                 Pageable pageable) {
        return search(keyword, fromDate, toDate, expenseType, status, pageable, null);
    }

    @Transactional(readOnly = true)
    public Page<ExpenseListItemResponse> search(String keyword,
                                                 String fromDate,
                                                 String toDate,
                                                 String expenseType,
                                                 String status,
                                                 Pageable pageable,
                                                 Integer applicantAccountId) {
        final String normalizedKeyword = normalize(keyword);
        final LocalDate from = parseDate(fromDate);
        final LocalDate to = parseDate(toDate);

        List<Expense> expenses = expenseRepository.findAll();

        List<ExpenseListItemResponse> filtered = expenses.stream()
                .filter(expense -> belongsToApplicant(expense, applicantAccountId))
                .filter(expense -> matchesKeyword(expense, normalizedKeyword))
                .filter(expense -> matchesDate(expense, from, to))
                .filter(expense -> expenseType == null || expenseType.isBlank()
                        || expenseType.equals(expense.getExpenseType()))
                .filter(expense -> status == null || status.isBlank() || status.equals(expense.getStatus()))
                // Mới nhất lên đầu. Phải có mốc phụ theo id vì resolveDate() cắt về đầu ngày, nên MỌI
                // phiếu trong cùng một ngày có date y hệt nhau: chỉ so date thôi thì các phiếu hôm nay
                // hoà nhau và giữ nguyên thứ tự findAll() trả về, tức là phiếu cũ nhất nằm trên cùng.
                .sorted(Comparator.comparing(Expense::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Expense::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toListItem)
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());

        List<ExpenseListItemResponse> content = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public ExpenseStatsResponse getStats() {
        return getStats(null);
    }

    @Transactional(readOnly = true)
    public ExpenseStatsResponse getStats(Integer applicantAccountId) {
        List<Expense> expenses = expenseRepository.findAll().stream()
                .filter(expense -> belongsToApplicant(expense, applicantAccountId))
                .toList();
        YearMonth currentMonth = YearMonth.now();

        List<Expense> thisMonth = expenses.stream()
                .filter(expense -> expense.getDate() != null)
                .filter(expense -> YearMonth.from(toLocalDate(expense.getDate())).equals(currentMonth))
                .toList();

        // BA 2026-08: chỉ COMPLETED là tiền THẬT đã rời quỹ (xem ExpenseService.confirmPayment) —
        // DRAFT/PENDING/AWAITING_PAYMENT/CANCELLED đều chưa/không phải tiền thật, không được cộng
        // vào "Đã chi trong tháng". Trước đây cờ này chỉ loại CANCELLED, hợp lý khi duyệt = hoàn
        // thành ngay lập tức; giờ AWAITING_PAYMENT có thể tồn tại lâu nên phải loại nốt.
        BigDecimal monthlyPaidTotal = thisMonth.stream()
                .filter(expense -> ExpenseStatus.COMPLETED.equals(expense.getStatus()))
                .map(Expense::getPaid)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long pendingCount = countByStatus(expenses, ExpenseStatus.PENDING);
        long awaitingPaymentCount = countByStatus(expenses, ExpenseStatus.AWAITING_PAYMENT);

        return new ExpenseStatsResponse(thisMonth.size(), monthlyPaidTotal, pendingCount, awaitingPaymentCount);
    }

    public List<String> listStatuses() {
        return ExpenseStatus.ALL;
    }

    public Map<String, String> expenseTypeLabels() {
        return ExpenseType.vietnameseLabels();
    }

    /** Which types show the purchase-invoice picker — fed to the form so JS can't drift from Java. */
    public List<String> purchaseLinkableTypes() {
        return ExpenseType.PURCHASE_LINKABLE;
    }

    // ------------------------------------------------------------------ reference documents

    /**
     * Customer returns still waiting for their refund to be paid out, for the create screen's
     * picker. Unlike Income's equivalent this needs no "pick the party first" step — a customer
     * return is selectable on its own — so the list is rendered straight into the page instead of
     * being fetched over AJAX.
     *
     * <p>A return qualifies when all four hold:</p>
     * <ol>
     *   <li>{@code invoiceID != null} — it is a customer return, not a supplier one;</li>
     *   <li>status is {@link ReturnStatus#DEBT} — note this is the <em>approved</em> state: per
     *       {@code ReturnStatus}'s javadoc there is deliberately no "Duyệt" for returns, because
     *       approving one means the pharmacy now owes the customer money;</li>
     *   <li>it has a real cash refund — see {@link #cashRefundAmount};</li>
     *   <li>no live Expense already points at it — see {@link #committedByReturnId()}.</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public List<ExpenseReferenceOptionResponse> listCustomerReturns() {
        Map<Integer, BigDecimal> committed = committedByReturnId();

        return returnRepository.findAllWithRelations().stream()
                .filter(ret -> ret.getInvoiceID() != null)
                .filter(ret -> ReturnStatus.DEBT.equals(ret.getStatus()))
                // Còn tiền hoàn chưa ai nhận, chứ không phải "chưa có phiếu chi nào" — hoàn tiền
                // chia được nhiều lần, xem committedByReturnId().
                .filter(ret -> availableToRefund(ret, committed).compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(Return::getReturnDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Return::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ret -> new ExpenseReferenceOptionResponse(
                        ret.getId(),
                        ret.getReturnCode(),
                        formatInstant(ret.getReturnDate()),
                        availableToRefund(ret, committed),
                        referenceDetail(ret)))
                .toList();
    }

    /**
     * {@code returnId -> refund payable}, for the create screen to auto-fill the (readonly) amount
     * box the moment a return is picked. A plain scalar map rather than the DTO list because that
     * is the established shape for a {@code th:inline} lookup in this codebase.
     */
    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> customerReturnAmounts() {
        return amountsById(listCustomerReturns());
    }

    /**
     * {@code returnId -> phần còn phải hoàn thực}, cho màn Return của {@code ReturnService} hiển thị
     * đúng "còn phải hoàn bao nhiêu" sau khi trừ những phiếu chi RETURN_REFUND_PAYOUT còn sống đã
     * chi ra — cùng vai trò {@code PurchaseinvoiceService.remainingDebt()} đóng cho
     * {@code Purchaseinvoice.paid}, chỉ khác chỗ Return không có cột tiền-đã-chi của riêng nó nên
     * nguồn sự thật nằm hẳn ở Expense (xem "Read second": phiếu trả CHỈ TÍNH tiền, không chi tiền).
     * Trả 0 cho một return đã được hoàn đủ (kể cả supplier return — công thức vẫn đúng vì
     * {@code committedByReturnId()} chỉ đếm phiếu chi thực sự trỏ vào nó, mà chỉ trả hàng khách mới
     * có phiếu chi loại này).
     */
    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> outstandingRefundByReturnId(Collection<Return> returns) {
        Map<Integer, BigDecimal> committed = committedByReturnId();
        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        for (Return ret : returns) {
            if (ret != null && ret.getId() != null) {
                result.put(ret.getId(), availableToRefund(ret, committed));
            }
        }
        return result;
    }

    /**
     * Tổng tiền các phiếu chi RETURN_REFUND_PAYOUT đã THỰC CHI ({@link ExpenseStatus#COMPLETED}) cho
     * một phiếu trả — dùng bởi {@code ReturnService.syncStatusAfterRefundPayment()} để biết khi nào
     * phiếu trả đã được hoàn ĐỦ tiền thật. Khác {@link #committedByReturnId()} (đếm cả phiếu mới chỉ
     * CAM KẾT — DRAFT/PENDING/AWAITING_PAYMENT — chưa chắc tiền đã rời quỹ): dùng con số đó ở đây sẽ
     * chuyển phiếu trả sang Hoàn thành ngay khi phiếu chi được TẠO, trước cả khi Owner xác nhận
     * thanh toán.
     */
    @Transactional(readOnly = true)
    public BigDecimal disbursedRefundAmount(Integer returnId) {
        if (returnId == null) {
            return BigDecimal.ZERO;
        }
        return expenseRepository.findAll().stream()
                .filter(expense -> ExpenseStatus.COMPLETED.equals(expense.getStatus()))
                .filter(expense -> expense.getReturnID() != null
                        && returnId.equals(expense.getReturnID().getId()))
                .map(Expense::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Purchase invoices the pharmacy still owes money on, for the debt-payment picker. The figure
     * shown is what is still <em>available to commit</em>, not the raw debt — see
     * {@link #availableToPay}.
     */
    @Transactional(readOnly = true)
    public List<ExpenseReferenceOptionResponse> listPayablePurchaseInvoices() {
        Map<Integer, BigDecimal> committed = committedByPurchaseId();

        return purchaseinvoiceService.findPayableInvoices().stream()
                .map(invoice -> Map.entry(invoice, availableToPay(invoice, committed)))
                .filter(entry -> entry.getValue().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.<Map.Entry<Purchaseinvoice, BigDecimal>, Instant>comparing(
                                entry -> entry.getKey().getDate(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(entry -> entry.getKey().getId(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(entry -> new ExpenseReferenceOptionResponse(
                        entry.getKey().getId(),
                        entry.getKey().getPurchaseInvoiceCode(),
                        formatInstant(entry.getKey().getDate()),
                        entry.getValue(),
                        purchaseReferenceDetail(entry.getKey())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> payablePurchaseInvoiceAmounts() {
        return amountsById(listPayablePurchaseInvoices());
    }

    private Map<Integer, BigDecimal> amountsById(List<ExpenseReferenceOptionResponse> options) {
        Map<Integer, BigDecimal> amounts = new LinkedHashMap<>();
        for (ExpenseReferenceOptionResponse option : options) {
            amounts.put(option.getId(), option.getAmount());
        }
        return amounts;
    }

    // ------------------------------------------------------------------ detail

    @Transactional(readOnly = true)
    public ExpenseDetailResponse getDetail(Integer expenseId) {
        return getDetail(expenseId, null);
    }

    @Transactional(readOnly = true)
    public ExpenseDetailResponse getDetail(Integer expenseId, Integer requiredApplicantAccountId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));
        ensureApplicantAccess(expense, requiredApplicantAccountId);
        return toDetail(expense);
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates a new expense slip. When {@code asDraft} is true it is saved as
     * {@link ExpenseStatus#DRAFT} regardless of role. Otherwise: the Owner's slip is auto-approved
     * straight to {@link ExpenseStatus#AWAITING_PAYMENT} (money has not left yet — see
     * {@link #confirmPayment}); anyone else's goes to {@link ExpenseStatus#PENDING} for the Owner to
     * approve.
     */
    @Transactional
    public Integer createExpense(ExpenseCreateRequest request, Integer currentAccountId, boolean isOwner,
                                  boolean asDraft) {
        return createExpense(request, currentAccountId, isOwner, asDraft, isOwner, null);
    }

    @Transactional
    public Integer createExpense(ExpenseCreateRequest request, Integer currentAccountId, boolean isOwner,
                                  boolean asDraft, boolean canPayCash, String requiredExpenseType) {
        String expenseType = resolveExpenseType(request.getExpenseType());
        if (requiredExpenseType != null && !requiredExpenseType.equals(expenseType)) {
            throw new IllegalArgumentException("Tài khoản này chỉ được tạo phiếu chi hoàn tiền trả hàng");
        }

        // Both links are resolved before validation because whenever a slip points at a document,
        // that document decides the amount — the posted value is display-only and never trusted.
        Return linkedReturn = resolveCustomerReturn(request, expenseType);
        Purchaseinvoice linkedPurchase = resolvePurchaseInvoice(request, expenseType);
        BigDecimal amount = resolveAmount(request, linkedReturn, linkedPurchase);
        if (ExpenseType.RETURN_REFUND_PAYOUT.equals(requiredExpenseType)
                && amount.compareTo(ExpenseType.PHARMACIST_REFUND_LIMIT) >= 0) {
            throw new IllegalArgumentException(
                    "Dược sĩ chỉ được tạo phiếu hoàn tiền trả hàng dưới 500.000đ");
        }

        // Every NOT NULL column (expenseType/reason/amount) must have a real value even for a
        // draft — unlike Stock Adjustment's items, Expense has no field that's genuinely optional
        // at the DB level, so "draft" only means "not yet sent for approval", not "incomplete data".
        validateRequest(request, amount);

        Account applicant = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        Expense expense = new Expense();
        expense.setApplicantID(applicant);
        expense.setExpenseType(expenseType);
        expense.setDate(resolveDate(request.getDate()));
        expense.setReason(request.getReason() != null ? request.getReason().trim() : "");
        expense.setAmount(amount);
        expense.setNote(trimToNull(request.getNote()));

        if (linkedReturn != null) {
            expense.setReturnID(linkedReturn);
            // Derived, not posted: the payee is whoever the original sale was billed to. Stays null
            // for a walk-in sale, which has no Customer row.
            expense.setCustomerID(customerOf(linkedReturn));
        }

        if (linkedPurchase != null) {
            expense.setPurchaseID(linkedPurchase);
            expense.setSupplierID(linkedPurchase.getSupplierID());
        }

        // Một phiếu là một lần chi: tiền đã chi luôn đúng bằng số tiền của phiếu, không có phiếu
        // "chi thiếu so với chính nó". Chi thiếu so với CHỨNG TỪ thì nằm ở chỗ khác — chứng từ còn nợ.
        BigDecimal[] split = resolveSplit(request, amount, canPayCash);
        expense.setPaid(amount);
        expense.setPaidByCash(split[0]);
        expense.setPaidByBanking(split[1]);
        // NOT NULL-safe and consistent with the two columns above: Expense has no @DynamicInsert, so
        // leaving this null makes Hibernate write an explicit NULL rather than fall back to the
        // column's DEFAULT 0. No UI collects a debt-offset portion yet.
        expense.setPaidByCredit(BigDecimal.ZERO);

        if (asDraft) {
            expense.setStatus(ExpenseStatus.DRAFT);
        } else if (isOwner) {
            applyApproval(expense, applicant);
        } else {
            expense.setStatus(ExpenseStatus.PENDING);
        }

        expense.setExpenseCode(generateCode());
        Expense saved = expenseRepository.save(expense);
        // Re-stamp the human-facing code from the real generated id (matches Stock Adjustment/
        // Purchase Invoice convention: the placeholder above only reserves a slot in sequence).
        saved.setExpenseCode(formatCode(saved.getId()));

        Expense finalSaved = expenseRepository.save(saved);

        if (ExpenseStatus.PENDING.equals(finalSaved.getStatus())) {
            workflowNotificationService.expensePending(finalSaved);
        }

        return finalSaved.getId();
    }

    /** Sends a {@link ExpenseStatus#DRAFT} slip forward, same shape as {@code StockadjustmentService#submit}. */
    @Transactional
    public void submit(Integer expenseId, Integer currentAccountId, boolean isOwner) {
        submit(expenseId, currentAccountId, isOwner, false);
    }

    @Transactional
    public void submit(Integer expenseId, Integer currentAccountId, boolean isOwner,
                       boolean restrictToApplicant) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));
        if (restrictToApplicant) {
            ensureApplicantAccess(expense, currentAccountId);
        }

        if (!ExpenseStatus.DRAFT.equals(expense.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể gửi duyệt phiếu đang ở trạng thái nháp");
        }

        Account actor = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        if (isOwner) {
            applyApproval(expense, actor);
        } else {
            expense.setStatus(ExpenseStatus.PENDING);
        }

        expenseRepository.save(expense);
        if (!isOwner) {
            workflowNotificationService
                    .expensePending(expense);
        }
    }

    @Transactional
    public void approve(Integer expenseId, Integer approverAccountId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));

        if (!ExpenseStatus.PENDING.equals(expense.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể duyệt phiếu đang ở trạng thái chờ duyệt");
        }

        Account approver = accountRepository.findById(approverAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        applyApproval(expense, approver);
        expenseRepository.save(expense);
        workflowNotificationService.expenseApproved(expense);
    }

    @Transactional
    public void reject(Integer expenseId, Integer approverAccountId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));

        if (!ExpenseStatus.PENDING.equals(expense.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể từ chối phiếu đang ở trạng thái chờ duyệt");
        }

        Account approver = accountRepository.findById(approverAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        expense.setStatus(ExpenseStatus.REJECTED);
        expense.setApprovedAt(Instant.now());
        expenseRepository.save(expense);
        workflowNotificationService.expenseRejected(expense);
        // approver identity for a rejection isn't modeled separately from approvedAt/status;
        // Expense has no dedicated "rejectedBy" column (see Pharmacy-Database-Description.docx).
    }

    /**
     * Internal correction for a wrongly-entered slip — same spirit as
     * {@code PurchaseinvoiceService.cancelPurchaseInvoice()}: not a real accounting reversal, just
     * marks the record void.
     *
     * <p><strong>Only before real money has left (BA 2026-08).</strong> A phiếu chi cannot be edited
     * or topped up after creation, so {@code DRAFT}/{@code PENDING}/{@code AWAITING_PAYMENT} can
     * still be cancelled — nothing has been disbursed yet at any of those, so there is nothing to
     * reverse (no purchase invoice / fund / shift effect has fired — see {@link #confirmPayment}). A
     * {@code COMPLETED} slip is terminal: real money is already out, so cancelling would need an
     * actual reversal this method never performed even under the old rules; raise a fresh slip
     * instead to record what actually happened.</p>
     */
    @Transactional
    public void cancel(Integer expenseId, String reason) {
        cancel(expenseId, reason, null);
    }

    @Transactional
    public void cancel(Integer expenseId, String reason, Integer requiredApplicantAccountId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));
        ensureApplicantAccess(expense, requiredApplicantAccountId);

        if (ExpenseStatus.CANCELLED.equals(expense.getStatus())) {
            throw new IllegalArgumentException("Phiếu chi này đã bị hủy trước đó");
        }
        if (ExpenseStatus.COMPLETED.equals(expense.getStatus())) {
            throw new IllegalArgumentException(
                    "Phiếu chi đã hoàn thành (tiền đã thực chi) không thể hủy nữa");
        }

        expense.setStatus(ExpenseStatus.CANCELLED);
        String trimmedReason = trimToNull(reason);
        if (trimmedReason != null) {
            String existingNote = expense.getNote();
            expense.setNote(existingNote == null || existingNote.isBlank()
                    ? "Lý do hủy: " + trimmedReason
                    : existingNote + " | Lý do hủy: " + trimmedReason);
        }

        expenseRepository.save(expense);
    }

    // ------------------------------------------------------------------ mapping

    /**
     * Cố tình KHÔNG có cột "tình trạng công nợ" ở đây. Một phiếu chi là một lần chi tiền, không mang
     * khái niệm nợ: nợ là thuộc tính của CHỨNG TỪ (phiếu nhập còn phải trả bao nhiêu, phiếu trả hàng
     * còn phải hoàn bao nhiêu), và việc theo dõi nó là của màn Công nợ. Phiếu thu cũng đúng như vậy —
     * {@code IncomeService} không có trường nợ nào trên phiếu, chỉ hiện số còn nợ ở ô chọn chứng từ.
     */
    private ExpenseListItemResponse toListItem(Expense expense) {
        return new ExpenseListItemResponse(
                expense.getId(),
                formatCode(expense.getId()),
                formatInstant(expense.getDate()),
                expense.getExpenseType(),
                ExpenseType.vietnameseName(expense.getExpenseType()),
                expense.getApplicantID() != null ? expense.getApplicantID().getName() : "Không rõ",
                expense.getAmount(),
                expense.getPaid(),
                paymentDisplay(expense.getPaidByCash(), expense.getPaidByBanking(), expense.getPaidByCredit()),
                expense.getStatus(),
                statusCssClass(expense.getStatus())
        );
    }

    private boolean belongsToApplicant(Expense expense, Integer applicantAccountId) {
        return applicantAccountId == null
                || expense.getApplicantID() != null
                && applicantAccountId.equals(expense.getApplicantID().getId());
    }

    private void ensureApplicantAccess(Expense expense, Integer requiredApplicantAccountId) {
        if (!belongsToApplicant(expense, requiredApplicantAccountId)) {
            throw new IllegalArgumentException("Bạn không có quyền xem hoặc thao tác phiếu chi này");
        }
    }

    /**
     * Hình thức chi, suy ra từ ba cột tiền — cùng cách với {@code IncomeService#paymentDisplay}.
     */
    private String paymentDisplay(BigDecimal paidByCash, BigDecimal paidByBanking, BigDecimal paidByCredit) {
        boolean hasCash = isPositive(paidByCash);
        boolean hasBanking = isPositive(paidByBanking);
        boolean hasCredit = isPositive(paidByCredit);
        if (hasCredit && !hasCash && !hasBanking) {
            return paymentTypeLabel(PAYMENT_CREDIT);
        }
        if (hasCash && hasBanking && !hasCredit) {
            return paymentTypeLabel(PAYMENT_MIXED);
        }
        if (hasBanking && !hasCash && !hasCredit) {
            return paymentTypeLabel(PAYMENT_BANKING);
        }
        if (hasCash && !hasBanking && !hasCredit) {
            return paymentTypeLabel(PAYMENT_CASH);
        }
        StringBuilder parts = new StringBuilder();
        if (hasCash) {
            parts.append(paymentTypeLabel(PAYMENT_CASH));
        }
        if (hasBanking) {
            appendPaymentPart(parts, paymentTypeLabel(PAYMENT_BANKING));
        }
        if (hasCredit) {
            appendPaymentPart(parts, paymentTypeLabel(PAYMENT_CREDIT));
        }
        return parts.isEmpty() ? "—" : parts.toString();
    }

    private String paymentTypeLabel(String code) {
        return switch (code) {
            case PAYMENT_CASH -> "Tiền mặt";
            case PAYMENT_BANKING -> "Chuyển khoản";
            case PAYMENT_MIXED -> "TM + CK";
            case PAYMENT_CREDIT -> "Cấn trừ công nợ";
            default -> code;
        };
    }

    private void appendPaymentPart(StringBuilder parts, String label) {
        if (!parts.isEmpty()) {
            parts.append(" + ");
        }
        parts.append(label);
    }

    private boolean isPositive(BigDecimal value) {
        return nullToZero(value).compareTo(BigDecimal.ZERO) > 0;
    }

    private ExpenseDetailResponse toDetail(Expense expense) {
        Return linkedReturn = expense.getReturnID();
        Customer customer = expense.getCustomerID();
        Purchaseinvoice linkedPurchase = expense.getPurchaseID();
        Supplier supplier = expense.getSupplierID();
        Shiftreport shift = expense.getShiftReportID();

        return new ExpenseDetailResponse(
                expense.getId(),
                formatCode(expense.getId()),
                formatInstant(expense.getDate()),
                expense.getExpenseType(),
                ExpenseType.vietnameseName(expense.getExpenseType()),
                expense.getApplicantID() != null ? expense.getApplicantID().getName() : "Không rõ",
                expense.getReason(),
                expense.getAmount(),
                expense.getPaid(),
                expense.getPaidByCash(),
                expense.getPaidByBanking(),
                expense.getPaidByCredit(),
                paymentDisplay(expense.getPaidByCash(), expense.getPaidByBanking(), expense.getPaidByCredit()),
                expense.getStatus(),
                statusCssClass(expense.getStatus()),
                approverName(expense),
                formatInstant(expense.getApprovedAt()),
                expense.getNote(),
                linkedReturn != null ? linkedReturn.getId() : null,
                linkedReturn != null ? linkedReturn.getReturnCode() : null,
                customer != null ? customer.getName() : null,
                linkedPurchase != null ? linkedPurchase.getId() : null,
                linkedPurchase != null ? linkedPurchase.getPurchaseInvoiceCode() : null,
                supplier != null ? supplier.getName() : null,
                shift != null ? shift.getId() : null,
                shift != null ? shift.getShiftReportCode() : null
        );
    }

    private String approverName(Expense expense) {
        // Expense has no dedicated "approvedBy" FK (only approvedAt) — see docx. Nothing to show yet.
        return expense.getApprovedAt() != null ? "Chủ nhà thuốc" : "Chưa có";
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The single choke point where a slip becomes approved — reached from create-as-Owner,
     * submit-as-Owner and approve. Lands on {@link ExpenseStatus#AWAITING_PAYMENT}, not
     * {@link ExpenseStatus#COMPLETED}: approving only authorises the slip, it does not move any
     * money yet — see {@link #confirmPayment} for the step that actually does.
     */
    private void applyApproval(Expense expense, Account approver) {
        expense.setApprovedAt(Instant.now());
        expense.setStatus(ExpenseStatus.AWAITING_PAYMENT);
    }

    /**
     * The Owner confirms that an {@link ExpenseStatus#AWAITING_PAYMENT} slip's money has actually
     * left — the real payment leg. Owner-only regardless of who raised or approved the slip
     * (Accountant included): {@code ExpensePageController} only maps this route under
     * {@code /owner/**}, matching approve/reject. This is the single choke point that pushes money
     * onto a linked purchase invoice, debits the financial-setting fund and stamps the shift — none
     * of that happens at approval any more. For a {@link ExpenseType#RETURN_REFUND_PAYOUT} slip, this
     * is also the point that syncs the linked {@code Return}'s status back —
     * see {@link ReturnService#syncStatusAfterRefundPayment(Integer)}.
     */
    @Transactional
    public void confirmPayment(Integer expenseId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));

        if (!ExpenseStatus.AWAITING_PAYMENT.equals(expense.getStatus())) {
            throw new IllegalArgumentException(
                    "Chỉ có thể xác nhận thanh toán cho phiếu đang ở trạng thái chờ thanh toán");
        }

        BigDecimal paid = nullToZero(expense.getPaid());
        expense.setStatus(ExpenseStatus.COMPLETED);
        settlePurchaseInvoice(expense, paid);
        // Tiền rời quỹ đúng lúc này — cùng thời điểm status chuyển COMPLETED, không phải lúc duyệt
        // (Chờ thanh toán chưa phải tiền thật). Xem FinancialsettingService.adjustFundBalances: quỹ
        // chưa từng được thiết lập (còn null) thì delta của quỹ đó bị bỏ qua lặng lẽ.
        financialsettingService.adjustFundBalances(
                nullToZero(expense.getPaidByCash()).negate(),
                nullToZero(expense.getPaidByBanking()).negate());
        attachOpenShift(expense, applicantIdOf(expense));
        expenseRepository.save(expense);

        // Đường thứ hai vào ReturnStatus.COMPLETED (xem javadoc của ReturnStatus): phiếu chi hoàn
        // tiền vừa thực chi xong, báo ReturnService tự kiểm và tất toán phiếu trả nếu không còn nợ.
        // No-op cho mọi loại phiếu chi khác (getReturnID() luôn null ngoài RETURN_REFUND_PAYOUT).
        if (expense.getReturnID() != null) {
            returnService.syncStatusAfterRefundPayment(expense.getReturnID().getId());
        }
    }

    /** Người LẬP phiếu — xem {@link #attachOpenShift}. */
    private Integer applicantIdOf(Expense expense) {
        return expense.getApplicantID() != null ? expense.getApplicantID().getId() : null;
    }

    /**
     * Stamps the slip with the open shift of whoever <strong>raised</strong> it, so the cash that left
     * can be reconciled against that person's register. Keeps an existing stamp — a slip belongs to
     * the shift it was raised in, not to whichever shift is open when it is topped up later.
     *
     * <p><strong>Creator, never approver or payer.</strong> The person who raised the slip is the one
     * who handled the money; approving it only authorises counting it, and paying it out later does
     * not move it to the payer's shift.</p>
     *
     * <p><strong>Cash always belongs to a shift; a transfer never opens one.</strong> A cash slip
     * calls {@code ensureOpenShiftFor} — opening a shift if the Owner has not sold anything yet —
     * while a banking-only slip only looks up an already-open one. {@code ensureOpenShiftFor} itself
     * returns {@code null} for a role that does not run a register, and {@link #resolveSplit} refuses
     * cash from anyone but the Owner, so the cash branch here is only ever reached by someone allowed
     * a shift.</p>
     */
    private void attachOpenShift(Expense expense, Integer accountId) {
        if (expense.getShiftReportID() != null || accountId == null) {
            return;
        }
        Shiftreport shift = touchesCashDrawer(expense)
                ? shiftreportService.ensureOpenShiftFor(accountId)
                : shiftreportService.findDraftShift(accountId).orElse(null);
        if (shift != null) {
            expense.setShiftReportID(shift);
        }
    }

    /** Whether any of this slip's money left as physical cash. */
    private boolean touchesCashDrawer(Expense expense) {
        return nullToZero(expense.getPaidByCash()).compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Whether the slip was raised by an Owner — the only role allowed to pay cash (see
     * {@link #resolveSplit}).
     */
    private boolean raisedByOwner(Expense expense) {
        Integer applicantId = applicantIdOf(expense);
        return applicantId != null
                && accountpermissionRepository.existsByAccountIdAndRole(applicantId, RoleConstants.OWNER);
    }

    private String resolveExpenseType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw new IllegalArgumentException("Vui lòng chọn loại phiếu chi");
        }
        String type = rawType.trim().toUpperCase(Locale.ROOT);
        if (!ExpenseType.isValid(type)) {
            throw new IllegalArgumentException("Loại phiếu chi không hợp lệ");
        }
        return type;
    }

    private Instant resolveDate(String rawDate) {
        LocalDate date = parseDate(rawDate);
        LocalDate resolved = date != null ? date : LocalDate.now();
        return resolved.atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    /**
     * Resolves and fully validates the customer return a {@link ExpenseType#RETURN_REFUND_PAYOUT}
     * slip pays out, or {@code null} for every other type (a {@code returnId} left over in the form
     * from a type the user switched away from is ignored rather than silently linked).
     */
    private Return resolveCustomerReturn(ExpenseCreateRequest request, String expenseType) {
        if (!ExpenseType.RETURN_REFUND_PAYOUT.equals(expenseType)) {
            return null;
        }
        if (request.getReturnId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn phiếu trả hàng của khách cần hoàn tiền");
        }

        Return ret = returnRepository.findById(request.getReturnId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng"));

        if (ret.getInvoiceID() == null) {
            throw new IllegalArgumentException(
                    "Phiếu chi hoàn tiền chỉ áp dụng cho phiếu trả hàng của khách, không áp dụng cho trả hàng nhà cung cấp");
        }
        if (!ReturnStatus.DEBT.equals(ret.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể hoàn tiền cho phiếu trả hàng đã duyệt");
        }
        if (cashRefundAmount(ret).compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Phiếu trả hàng này không phát sinh tiền hoàn (toàn bộ đã cấn trừ vào công nợ)");
        }
        if (availableToRefund(ret, committedByReturnId()).compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Phiếu trả hàng này đã được hoàn đủ tiền");
        }
        return ret;
    }

    /**
     * The part of a return that is real money leaving the register: what the pharmacy owes
     * ({@code totalRefund}), less anything already settled by writing down the original invoice's
     * debt ({@code offsetDebtAmount}) — paying that out again would refund the customer twice.
     *
     * <p>In practice {@code offsetDebtAmount} is always zero on a customer return —
     * {@code ReturnService.assertReturnable} makes the customer clear the invoice's debt before
     * returning anything, so there is nothing left to offset. The subtraction is kept because the
     * column still exists and nothing enforces that zero.</p>
     */
    private BigDecimal cashRefundAmount(Return ret) {
        return nullToZero(ret.getTotalRefund())
                .subtract(nullToZero(ret.getOffsetDebtAmount()))
                .max(BigDecimal.ZERO);
    }

    /**
     * {@code returnID -> tổng tiền các phiếu chi còn sống đã nhận hoàn cho phiếu trả đó}. Một phiếu
     * trả có thể được hoàn nhiều lần (xem {@link #createExpense}), nên đây là một tổng chứ không
     * phải cờ một-phiếu-một-lần.
     *
     * <p>Cộng cả phiếu chưa duyệt lẫn phiếu đã chi: phiếu chưa duyệt là tiền đã hứa, không được để
     * hai phiếu cùng nhận trọn phần hoàn rồi cả hai cùng được duyệt. Phiếu bị từ chối / bị hủy thì
     * không tính, nên hủy một phiếu là trả phần hoàn đó về cho phiếu sau — cố ý khác
     * {@code IncomeService.linkedReturnIds()}, nơi rào lỏng hơn sẽ kẹt phiếu trả lại vĩnh viễn.</p>
     */
    private Map<Integer, BigDecimal> committedByReturnId() {
        Map<Integer, BigDecimal> committed = new LinkedHashMap<>();
        for (Expense expense : liveExpenses()) {
            Return ret = expense.getReturnID();
            if (ret == null || ret.getId() == null) {
                continue;
            }
            committed.merge(ret.getId(), nullToZero(expense.getAmount()), BigDecimal::add);
        }
        return committed;
    }

    /** Phần tiền hoàn của một phiếu trả chưa được phiếu chi nào nhận. */
    private BigDecimal availableToRefund(Return ret, Map<Integer, BigDecimal> committed) {
        return cashRefundAmount(ret)
                .subtract(committed.getOrDefault(ret.getId(), BigDecimal.ZERO))
                .max(BigDecimal.ZERO);
    }

    private Customer customerOf(Return ret) {
        Invoice invoice = ret.getInvoiceID();
        return invoice != null ? invoice.getCustomerID() : null;
    }

    /**
     * Resolves and validates the purchase invoice this slip settles. Allowed for every type in
     * {@link ExpenseType#PURCHASE_LINKABLE} and always optional — paying a supplier without
     * pointing at one specific invoice is legitimate — so a missing id is not an error, unlike a
     * refund payout.
     */
    private Purchaseinvoice resolvePurchaseInvoice(ExpenseCreateRequest request, String expenseType) {
        if (!ExpenseType.GOODS_PAYMENT.equals(expenseType)) {
            return null;
        }

        if (request.getPurchaseId() == null) {
            return null;
        }

        Purchaseinvoice invoice = purchaseinvoiceService.findPayableInvoices().stream()
                .filter(candidate -> request.getPurchaseId().equals(candidate.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Phiếu nhập không tồn tại, đã hủy hoặc đã trả đủ tiền"));

        if (availableToPay(invoice, committedByPurchaseId()).compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Phiếu nhập này đã có phiếu chi khác nhận trả toàn bộ phần còn nợ");
        }
        return invoice;
    }

    /**
     * Số tiền của phiếu = số tiền người lập nhập cho lần chi này — không có khái niệm "chi thiếu so
     * với chính nó" (một phiếu là một lần chi). Chặn trần ở phần chứng từ còn thiếu (xem
     * {@link #cappedByDocument}), để hai phiếu chi cùng lúc không cùng trả vượt phần còn nợ.
     *
     * <p><strong>Ngoại lệ: hoàn tiền trả hàng không nhận số người dùng gõ, luôn lấy nguyên phần
     * còn phải hoàn của phiếu trả</strong> (đúng như {@link ExpenseCreateRequest#getReturnId()} đã ghi
     * — "the posted value is never trusted"), khác phiếu nhập vẫn cho trả một phần. Ô tiền trên form
     * chỉ hiển thị, không sửa được khi chọn loại hoàn tiền — xem {@code expense/create.html}.</p>
     */
    private BigDecimal resolveAmount(ExpenseCreateRequest request,
                                     Return linkedReturn,
                                     Purchaseinvoice linkedPurchase) {
        BigDecimal posted = request.getAmount();
        if (linkedReturn != null) {
            return availableToRefund(linkedReturn, committedByReturnId());
        }
        if (linkedPurchase != null) {
            return cappedByDocument(posted, availableToPay(linkedPurchase, committedByPurchaseId()),
                    "Số tiền chi vượt quá phần còn phải trả của phiếu nhập");
        }
        return posted;
    }

    /**
     * Bỏ trống ô tiền trên một phiếu có gắn chứng từ = "trả nốt", nên mặc định là toàn bộ phần còn
     * lại. Nhập vượt phần còn lại thì báo lỗi chứ không tự cắt bớt: người lập cần biết con số họ gõ
     * không được ghi nhận, thay vì thấy phiếu lưu xong với một số khác.
     */
    private BigDecimal cappedByDocument(BigDecimal posted, BigDecimal available, String overLimitMessage) {
        if (posted == null || posted.compareTo(BigDecimal.ZERO) <= 0) {
            return available;
        }
        if (posted.compareTo(available) > 0) {
            throw new IllegalArgumentException(overLimitMessage
                    + " (" + String.format(Locale.forLanguageTag("vi-VN"), "%,.0fđ", available) + ")");
        }
        return posted;
    }

    /**
     * How much of an invoice's debt is not yet spoken for: the raw debt minus what slips still in
     * flight have promised. Without this, two drafts each for the full debt would both be accepted
     * and the invoice would end up overpaid the moment both are approved.
     */
    private BigDecimal availableToPay(Purchaseinvoice invoice, Map<Integer, BigDecimal> committed) {
        BigDecimal debt = purchaseinvoiceService.remainingDebt(invoice);
        return debt.subtract(committed.getOrDefault(invoice.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
    }

    /**
     * {@code purchaseId -> money promised but not yet disbursed}, summed over every live slip. A
     * slip's promise is {@code amount - disbursed}: the disbursed part is already sitting in
     * {@code Purchaseinvoice.paid}, so counting it here would deduct it twice.
     */
    private Map<Integer, BigDecimal> committedByPurchaseId() {
        Map<Integer, BigDecimal> committed = new LinkedHashMap<>();
        for (Expense expense : liveExpenses()) {
            Purchaseinvoice invoice = expense.getPurchaseID();
            if (invoice == null || invoice.getId() == null) {
                continue;
            }
            BigDecimal outstanding = nullToZero(expense.getAmount())
                    .subtract(disbursedAmount(expense))
                    .max(BigDecimal.ZERO);
            committed.merge(invoice.getId(), outstanding, BigDecimal::add);
        }
        return committed;
    }

    /**
     * Money this slip has actually pushed onto its purchase invoice. Only a {@code COMPLETED} slip
     * has disbursed anything — approval alone ({@code AWAITING_PAYMENT}) authorises the slip but does
     * not move money yet, see {@link #confirmPayment}.
     */
    private BigDecimal disbursedAmount(Expense expense) {
        return isDisbursed(expense) ? nullToZero(expense.getPaid()) : BigDecimal.ZERO;
    }

    /** Whether money has actually left for this slip — status-only, independent of amount. */
    private boolean isDisbursed(Expense expense) {
        return ExpenseStatus.COMPLETED.equals(expense.getStatus());
    }

    private List<Expense> liveExpenses() {
        return expenseRepository.findAll().stream()
                .filter(expense -> !ExpenseStatus.REJECTED.equals(expense.getStatus()))
                .filter(expense -> !ExpenseStatus.CANCELLED.equals(expense.getStatus()))
                .toList();
    }

    /** Pushes {@code delta} onto the slip's purchase invoice, if it has one. */
    private void settlePurchaseInvoice(Expense expense, BigDecimal delta) {
        Purchaseinvoice invoice = expense.getPurchaseID();
        if (invoice == null || delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        purchaseinvoiceService.applyPayment(invoice.getId(), delta);
    }

    /** Secondary line in the debt-payment picker: which supplier, and the invoice's own total. */
    private String purchaseReferenceDetail(Purchaseinvoice invoice) {
        Supplier supplier = invoice.getSupplierID();
        String supplierName = supplier != null && supplier.getName() != null && !supplier.getName().isBlank()
                ? supplier.getName()
                : "Không rõ NCC";
        return supplierName + " · Tổng "
                + String.format(Locale.forLanguageTag("vi-VN"), "%,.0fđ", nullToZero(invoice.getTotalAmount()));
    }

    /** Secondary line in the picker: who is being paid, and which sale it came from. */
    private String referenceDetail(Return ret) {
        Invoice invoice = ret.getInvoiceID();
        Customer customer = customerOf(ret);
        String customerName = customer != null && customer.getName() != null && !customer.getName().isBlank()
                ? customer.getName()
                : "Khách lẻ";
        String invoiceNumber = invoice != null && invoice.getInvoiceNumber() != null
                ? invoice.getInvoiceNumber()
                : "—";
        return customerName + " · HĐ " + invoiceNumber;
    }

    /**
     * Returns {@code [paidByCash, paidByBanking]}.
     *
     * <p><strong>Only roles operating the register may pay in cash</strong>: Owner and Pharmacist
     * have shifts; Accountant settles by transfer and never opens the drawer. Enforcing it here is
     * what makes {@link #attachOpenShift}'s "stamp the creator's shift" rule safe. The default is
     * therefore all cash for a register role and all banking for Accountant.</p>
     *
     */
    private BigDecimal[] resolveSplit(ExpenseCreateRequest request, BigDecimal amount, boolean canPayCash) {
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        }
        BigDecimal cash = request.getPaidByCash();
        BigDecimal banking = request.getPaidByBanking();
        if (cash == null && banking == null) {
            return canPayCash
                    ? new BigDecimal[]{amount, BigDecimal.ZERO}
                    : new BigDecimal[]{BigDecimal.ZERO, amount};
        }
        cash = nullToZero(cash);
        banking = nullToZero(banking);
        assertCashAllowed(cash, canPayCash);
        if (cash.add(banking).setScale(2, RoundingMode.HALF_UP)
                .compareTo(amount.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalArgumentException("Tiền mặt + chuyển khoản phải bằng số tiền chi");
        }
        return new BigDecimal[]{cash, banking};
    }

    /** @see #resolveSplit */
    private void assertCashAllowed(BigDecimal cash, boolean canPayCash) {
        if (!canPayCash && nullToZero(cash).compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalArgumentException(
                    "Kế toán chỉ được chi qua chuyển khoản; phần tiền mặt phải do Chủ nhà thuốc chi");
        }
    }

    /**
     * {@code amount} is passed in rather than read off the request because a refund payout takes it
     * from the linked return instead of the form.
     */
    private void validateRequest(ExpenseCreateRequest request, BigDecimal amount) {
        if (request.getExpenseType() == null || request.getExpenseType().isBlank()) {
            throw new IllegalArgumentException("Vui lòng chọn loại phiếu chi");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập lý do chi");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền cần chi phải lớn hơn 0");
        }
    }

    private boolean matchesKeyword(Expense expense, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        return containsNormalized(formatCode(expense.getId()), normalizedKeyword)
                || containsNormalized(expense.getReason(), normalizedKeyword)
                || containsNormalized(expense.getApplicantID() != null ? expense.getApplicantID().getName() : null,
                        normalizedKeyword);
    }

    private boolean matchesDate(Expense expense, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        if (expense.getDate() == null) {
            return false;
        }
        LocalDate date = toLocalDate(expense.getDate());
        if (from != null && date.isBefore(from)) {
            return false;
        }
        return to == null || !date.isAfter(to);
    }

    private long countByStatus(List<Expense> expenses, String status) {
        return expenses.stream().filter(expense -> status.equals(expense.getStatus())).count();
    }

    private String statusCssClass(String status) {
        if (status == null) {
            return "status-default";
        }
        if (ExpenseStatus.DRAFT.equals(status)) {
            return "status-draft";
        }
        if (ExpenseStatus.PENDING.equals(status)) {
            return "status-pending";
        }
        if (ExpenseStatus.REJECTED.equals(status)) {
            return "status-rejected";
        }
        if (ExpenseStatus.AWAITING_PAYMENT.equals(status)) {
            return "status-awaiting";
        }
        if (ExpenseStatus.COMPLETED.equals(status)) {
            return "status-approved";
        }
        if (ExpenseStatus.CANCELLED.equals(status)) {
            return "status-rejected";
        }
        return "status-default";
    }

    private String formatCode(Integer id) {
        if (id == null) {
            return "PC-000000";
        }
        return "PC-" + String.format("%06d", id);
    }

    private String generateCode() {
        int nextId = expenseRepository.findAll().stream()
                .map(Expense::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        return "PC-" + String.format("%06d", nextId);
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(instant);
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

    private BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
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
