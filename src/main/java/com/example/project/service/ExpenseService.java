package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ExpenseType;
import com.example.project.constant.ReturnStatus;
import com.example.project.dto.request.ExpenseCreateRequest;
import com.example.project.dto.response.ExpenseDetailResponse;
import com.example.project.dto.response.ExpenseListItemResponse;
import com.example.project.dto.response.ExpenseReferenceOptionResponse;
import com.example.project.dto.response.ExpenseResponse;
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
import com.example.project.repository.ExpenseRepository;
import com.example.project.repository.ReturnRepository;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Expense ("Phiếu chi") — the pharmacy's cash-outflow control screen. Most slips are still plain
 * manual entry, but a {@link ExpenseType#RETURN_REFUND_PAYOUT} slip is now the real payout leg of a
 * <em>customer</em> return: {@code ReturnStatus}'s own javadoc has always said "the actual cash
 * payout lives on a separate Expense, handled in a later phase" — this is that phase.
 *
 * <p><strong>Customer vs. supplier returns.</strong> {@code Return.returnType} does <em>not</em>
 * say which side a slip belongs to — since 2026-07-26 it is derived from the amounts and holds a
 * payment method ({@code CASH}/{@code BANKING}/{@code MIXED}/{@code DEBT}). The discriminator used
 * consistently across {@code ReturnService}, {@code ApprovalService}, {@code IncomeService} and
 * {@code ShiftreportService} is the FK: {@code invoiceID != null} is a customer return,
 * {@code purchaseID != null && invoiceID == null} is a supplier one. Expense only ever touches the
 * former (the pharmacy pays the customer back); the latter is money coming <em>in</em> and belongs
 * to {@code IncomeService.listSupplierReturns()}, the exact mirror of
 * {@link #listCustomerReturns()}. Returning goods to a supplier costs no cash, so it is out of
 * scope here by design.</p>
 *
 * <p><strong>Paying a supplier.</strong> A {@link ExpenseType#OPERATIONAL} slip can point at a
 * {@code PurchaseInvoice} and is the real payment leg for it — including money still owed, since
 * that debt is the import invoice itself (see {@link ExpenseType#PURCHASE_LINKABLE} for the BA's
 * reasoning). Approving or paying the slip pushes the money onto {@code Purchaseinvoice.paid} via
 * {@link PurchaseinvoiceService#applyPayment(Integer, java.math.BigDecimal)}, which re-derives and
 * stores the invoice's status in the same transaction. Cancelling an already-approved slip reverses
 * it. Money is only ever considered disbursed once the slip is approved — see
 * {@link #disbursedAmount}.</p>
 *
 * <p><strong>Shift attachment.</strong> A slip is stamped with the actor's open shift at the moment
 * the money is authorised, so the register can be reconciled — see {@link #attachOpenShift}. Only
 * the Expense side is wired here; making {@code ShiftreportService.computeTransactionTotals()}
 * actually read those slips is another contributor's work, so a cash expense still does not move
 * {@code totalCashOut} yet.</p>
 *
 * <p>Workflow mirrors {@code StockadjustmentService}'s draft/submit/approve/reject shape, plus a
 * payment step ({@link ExpenseStatus#AWAITING_PAYMENT} → {@link ExpenseStatus#COMPLETED}) since an
 * Expense tracks real cash leaving the register, not just an approval.</p>
 */
@Service
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final AccountRepository accountRepository;
    private final ReturnRepository returnRepository;
    private final PurchaseinvoiceService purchaseinvoiceService;
    private final ShiftreportService shiftreportService;

    public ExpenseService(ExpenseRepository expenseRepository,
                          AccountRepository accountRepository,
                          ReturnRepository returnRepository,
                          PurchaseinvoiceService purchaseinvoiceService,
                          ShiftreportService shiftreportService) {
        this.expenseRepository = expenseRepository;
        this.accountRepository = accountRepository;
        this.returnRepository = returnRepository;
        this.purchaseinvoiceService = purchaseinvoiceService;
        this.shiftreportService = shiftreportService;
    }

    // ------------------------------------------------------------------ generated-REST passthrough

    @Transactional(readOnly = true)
    public List<ExpenseResponse> getAll() {
        return expenseRepository.findAll()
                .stream()
                .map(ExpenseResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------ list / search

    @Transactional(readOnly = true)
    public Page<ExpenseListItemResponse> search(String keyword,
                                                 String fromDate,
                                                 String toDate,
                                                 String expenseType,
                                                 String status,
                                                 Pageable pageable) {
        final String normalizedKeyword = normalize(keyword);
        final LocalDate from = parseDate(fromDate);
        final LocalDate to = parseDate(toDate);

        List<Expense> expenses = expenseRepository.findAll();

        List<ExpenseListItemResponse> filtered = expenses.stream()
                .filter(expense -> matchesKeyword(expense, normalizedKeyword))
                .filter(expense -> matchesDate(expense, from, to))
                .filter(expense -> expenseType == null || expenseType.isBlank()
                        || expenseType.equals(expense.getExpenseType()))
                .filter(expense -> status == null || status.isBlank() || status.equals(expense.getStatus()))
                .sorted((a, b) -> {
                    Instant da = a.getDate();
                    Instant db = b.getDate();
                    if (da == null || db == null) {
                        return 0;
                    }
                    return db.compareTo(da);
                })
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
        List<Expense> expenses = expenseRepository.findAll();
        YearMonth currentMonth = YearMonth.now();

        List<Expense> thisMonth = expenses.stream()
                .filter(expense -> expense.getDate() != null)
                .filter(expense -> YearMonth.from(toLocalDate(expense.getDate())).equals(currentMonth))
                .toList();

        // Cancelled slips don't count as real cash out (same convention as
        // PurchaseinvoiceService.getStats() excluding cancelled invoices from its totals).
        BigDecimal monthlyPaidTotal = thisMonth.stream()
                .filter(expense -> !ExpenseStatus.CANCELLED.equals(expense.getStatus()))
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
     *   <li>no live Expense already points at it — see {@link #linkedReturnIds}.</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public List<ExpenseReferenceOptionResponse> listCustomerReturns() {
        Set<Integer> linked = linkedReturnIds();

        return returnRepository.findAllWithRelations().stream()
                .filter(ret -> ret.getInvoiceID() != null)
                .filter(ret -> ReturnStatus.DEBT.equals(ret.getStatus()))
                .filter(ret -> cashRefundAmount(ret).compareTo(BigDecimal.ZERO) > 0)
                .filter(ret -> !linked.contains(ret.getId()))
                .sorted(Comparator.comparing(Return::getReturnDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Return::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ret -> new ExpenseReferenceOptionResponse(
                        ret.getId(),
                        ret.getReturnCode(),
                        formatInstant(ret.getReturnDate()),
                        cashRefundAmount(ret),
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
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));
        return toDetail(expense);
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates a new expense slip. When {@code asDraft} is true it is saved as
     * {@link ExpenseStatus#DRAFT} regardless of role. Otherwise: the Owner's slip is auto-approved
     * (status resolved straight to {@link ExpenseStatus#AWAITING_PAYMENT} or
     * {@link ExpenseStatus#COMPLETED} depending on whether it's fully paid); anyone else's goes to
     * {@link ExpenseStatus#PENDING} for the Owner to approve.
     */
    @Transactional
    public Integer createExpense(ExpenseCreateRequest request, Integer currentAccountId, boolean isOwner,
                                  boolean asDraft) {
        String expenseType = resolveExpenseType(request.getExpenseType());

        // Resolved before validation because a refund payout takes its amount from the return, not
        // from the form — the posted value is display-only (the box is readonly) and never trusted.
        Return linkedReturn = resolveCustomerReturn(request, expenseType);
        BigDecimal amount = linkedReturn != null ? cashRefundAmount(linkedReturn) : request.getAmount();

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

        Purchaseinvoice linkedPurchase = resolvePurchaseInvoice(request, expenseType, amount);
        if (linkedPurchase != null) {
            expense.setPurchaseID(linkedPurchase);
            expense.setSupplierID(linkedPurchase.getSupplierID());
        }

        BigDecimal paid = resolvePaid(request, amount);
        BigDecimal[] split = resolveSplit(request, paid);
        expense.setPaid(paid);
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
        return expenseRepository.save(saved).getId();
    }

    /** Sends a {@link ExpenseStatus#DRAFT} slip forward, same shape as {@code StockadjustmentService#submit}. */
    @Transactional
    public void submit(Integer expenseId, Integer currentAccountId, boolean isOwner) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));

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
        // approver identity for a rejection isn't modeled separately from approvedAt/status;
        // Expense has no dedicated "rejectedBy" column (see Pharmacy-Database-Description.docx).
    }

    /**
     * Records an additional payment against an {@link ExpenseStatus#AWAITING_PAYMENT} slip.
     * {@code cashPortion}/{@code bankingPortion} are the amount being paid <em>now</em> (not the
     * cumulative total) — they're added to whatever was already paid. Moves to
     * {@link ExpenseStatus#COMPLETED} once {@code paid >= amount}.
     *
     * <p>{@code currentAccountId} exists only so a slip approved while no shift was open can still
     * pick one up here — this is where the cash physically moves for a part-paid slip.</p>
     */
    @Transactional
    public void markPaid(Integer expenseId, BigDecimal cashPortion, BigDecimal bankingPortion,
                          Integer currentAccountId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));

        if (!ExpenseStatus.AWAITING_PAYMENT.equals(expense.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể ghi nhận thanh toán cho phiếu đang chờ thanh toán");
        }

        BigDecimal cash = cashPortion != null ? cashPortion : BigDecimal.ZERO;
        BigDecimal banking = bankingPortion != null ? bankingPortion : BigDecimal.ZERO;
        BigDecimal portion = cash.add(banking);

        if (portion.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền thanh toán phải lớn hơn 0");
        }

        BigDecimal previouslyPaid = expense.getPaid() != null ? expense.getPaid() : BigDecimal.ZERO;
        BigDecimal newPaid = previouslyPaid.add(portion);

        if (newPaid.compareTo(expense.getAmount()) > 0) {
            throw new IllegalArgumentException("Số tiền thanh toán vượt quá số tiền cần chi còn lại");
        }

        expense.setPaid(newPaid);
        expense.setPaidByCash(nullToZero(expense.getPaidByCash()).add(cash));
        expense.setPaidByBanking(nullToZero(expense.getPaidByBanking()).add(banking));
        expense.setPaidByCredit(nullToZero(expense.getPaidByCredit()));

        if (newPaid.compareTo(expense.getAmount()) >= 0) {
            expense.setStatus(ExpenseStatus.COMPLETED);
        }

        // Only the increment: the slip was already approved, so everything before this was pushed
        // onto the invoice at approval time.
        settlePurchaseInvoice(expense, portion);
        attachOpenShift(expense, currentAccountId);

        expenseRepository.save(expense);
    }

    /**
     * Internal correction for a wrongly-entered slip — same spirit as
     * {@code PurchaseinvoiceService.cancelPurchaseInvoice()}: not a real accounting reversal, just
     * marks the record void. Only allowed before it's fully paid.
     */
    @Transactional
    public void cancel(Integer expenseId, String reason) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));

        if (ExpenseStatus.COMPLETED.equals(expense.getStatus())) {
            throw new IllegalArgumentException("Không thể hủy phiếu chi đã hoàn thành");
        }
        if (ExpenseStatus.CANCELLED.equals(expense.getStatus())) {
            throw new IllegalArgumentException("Phiếu chi này đã bị hủy trước đó");
        }

        // Give the money back to the invoice's outstanding debt before voiding the slip. Computed
        // while the status is still the pre-cancel one, since that is what decides whether anything
        // was ever disbursed. A DRAFT/PENDING slip pushed nothing, so this is a no-op for them.
        settlePurchaseInvoice(expense, disbursedAmount(expense).negate());

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
                expense.getStatus(),
                statusCssClass(expense.getStatus())
        );
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
     * The single choke point where a slip becomes authorised — reached from create-as-Owner,
     * submit-as-Owner and approve. That makes it the right (and only) place to push the money onto
     * a linked purchase invoice: before this the slip's {@code paid} is just a figure the creator
     * typed, after it the cash has really left.
     */
    private void applyApproval(Expense expense, Account approver) {
        expense.setApprovedAt(Instant.now());
        BigDecimal paid = expense.getPaid() != null ? expense.getPaid() : BigDecimal.ZERO;
        expense.setStatus(paid.compareTo(expense.getAmount()) >= 0
                ? ExpenseStatus.COMPLETED
                : ExpenseStatus.AWAITING_PAYMENT);
        settlePurchaseInvoice(expense, paid);
        attachOpenShift(expense, approver.getId());
    }

    /**
     * Stamps the slip with the actor's currently open shift, so the cash that just left can be
     * reconciled against the register. Keeps the existing stamp if there is one — a slip belongs to
     * the shift that authorised it, not to whichever shift happens to be open when it is topped up
     * later.
     *
     * <p><strong>Deliberately {@code findDraftShift}, never {@code ensureOpenShiftFor}.</strong>
     * Creating a shift from this screen would be actively harmful: Expense is an Owner + Accountant
     * screen, {@code ensureOpenShiftFor} does not check the role, and an Accountant is never meant
     * to have a shift. Give one to an Accountant and
     * {@code ShiftreportController.logoutGuard()} — which also does not check the role — would
     * redirect every later logout to {@code /accountant/shift-reports/{id}}, a route that does not
     * exist (shift screens are Owner + Pharmacist only). They would be unable to log out at all.
     * Attaching only an already-open shift makes the Accountant case fall out as {@code null} with
     * no role check anywhere, and matches the rule that shifts open on the first counter sale — an
     * expense is paid out of a drawer that is already open, it does not open one.</p>
     */
    private void attachOpenShift(Expense expense, Integer accountId) {
        if (expense.getShiftReportID() != null || accountId == null) {
            return;
        }
        shiftreportService.findDraftShift(accountId).ifPresent(expense::setShiftReportID);
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

    private BigDecimal resolvePaid(ExpenseCreateRequest request, BigDecimal amount) {
        if (request.isFullyPaid()) {
            return amount;
        }
        BigDecimal paid = request.getPaid() != null ? request.getPaid() : BigDecimal.ZERO;
        if (paid.compareTo(BigDecimal.ZERO) < 0 || paid.compareTo(amount) > 0) {
            throw new IllegalArgumentException("Số tiền đã chi phải nằm trong khoảng 0 đến tổng số tiền cần chi");
        }
        return paid;
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
        if (linkedReturnIds().contains(ret.getId())) {
            throw new IllegalArgumentException("Phiếu trả hàng này đã có phiếu chi hoàn tiền");
        }
        return ret;
    }

    /**
     * The part of a return that is real money leaving the register. Deliberately <em>not</em>
     * {@code totalRefund}: {@code refundCredit}/{@code offsetDebtAmount} was already settled by
     * reducing the original invoice's debt when the return was approved, so paying it out again
     * would refund the customer twice. Same helper (and same reasoning) as
     * {@code IncomeService.cashRefundAmount}.
     */
    private BigDecimal cashRefundAmount(Return ret) {
        return nullToZero(ret.getRefundCash()).add(nullToZero(ret.getRefundBanking()));
    }

    /**
     * Returns already claimed by a live Expense. Rejected and cancelled slips are excluded so a
     * voided payout releases its return back into the picker — a deliberate difference from
     * {@code IncomeService.linkedReturnIds()}, whose looser guard would strand the return forever.
     * Since {@code 38515f8} dropped {@code Return.expenseID} this is the only link direction left,
     * so this set is the sole duplicate guard.
     */
    private Set<Integer> linkedReturnIds() {
        return liveExpenses().stream()
                .map(Expense::getReturnID)
                .filter(Objects::nonNull)
                .map(Return::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
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
    private Purchaseinvoice resolvePurchaseInvoice(ExpenseCreateRequest request, String expenseType,
                                                    BigDecimal amount) {
        if (!ExpenseType.supportsPurchaseInvoiceLink(expenseType) || request.getPurchaseId() == null) {
            return null;
        }

        Purchaseinvoice invoice = purchaseinvoiceService.findPayableInvoices().stream()
                .filter(candidate -> request.getPurchaseId().equals(candidate.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Phiếu nhập không tồn tại, đã hủy hoặc đã trả đủ tiền"));

        BigDecimal available = availableToPay(invoice, committedByPurchaseId());
        if (amount != null && amount.compareTo(available) > 0) {
            throw new IllegalArgumentException(String.format(Locale.forLanguageTag("vi-VN"),
                    "Số tiền chi vượt quá số còn phải trả cho phiếu nhập này (%,.0fđ)", available));
        }
        return invoice;
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
     * Money this slip has actually pushed onto its purchase invoice. Only an <em>approved</em> slip
     * has disbursed anything: a draft or a pending one records a {@code paid} figure the creator
     * typed, but nobody has authorised it leaving the register yet.
     */
    private BigDecimal disbursedAmount(Expense expense) {
        boolean approved = ExpenseStatus.AWAITING_PAYMENT.equals(expense.getStatus())
                || ExpenseStatus.COMPLETED.equals(expense.getStatus());
        return approved ? nullToZero(expense.getPaid()) : BigDecimal.ZERO;
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

    /** Returns {@code [paidByCash, paidByBanking]}, defaulting an unsplit amount entirely to cash. */
    private BigDecimal[] resolveSplit(ExpenseCreateRequest request, BigDecimal paid) {
        if (paid.compareTo(BigDecimal.ZERO) == 0) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        }
        BigDecimal cash = request.getPaidByCash();
        BigDecimal banking = request.getPaidByBanking();
        if (cash == null && banking == null) {
            return new BigDecimal[]{paid, BigDecimal.ZERO};
        }
        cash = nullToZero(cash);
        banking = nullToZero(banking);
        if (cash.add(banking).setScale(2, RoundingMode.HALF_UP)
                .compareTo(paid.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalArgumentException("Tiền mặt + chuyển khoản phải bằng số tiền đã chi");
        }
        return new BigDecimal[]{cash, banking};
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
