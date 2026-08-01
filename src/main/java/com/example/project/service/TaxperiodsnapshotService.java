package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ExpenseType;
import com.example.project.constant.ReturnPurchaseStatus;
import com.example.project.constant.ReturnStatus;
import com.example.project.constant.RoleConstants;
import com.example.project.constant.TaxRevenueGroup;
import com.example.project.dto.request.TaxPeriodCloseRequest;
import com.example.project.dto.request.TaxPeriodUpdateRequest;
import com.example.project.dto.response.TaxPeriodComputationResponse;
import com.example.project.dto.response.TaxPeriodDetailResponse;
import com.example.project.dto.response.TaxPeriodListItemResponse;
import com.example.project.entity.Financialsetting;
import com.example.project.entity.Invoice;
import com.example.project.entity.Purchaseinvoice;
import com.example.project.entity.Return;
import com.example.project.entity.Taxperiodsnapshot;
import com.example.project.repository.AccountpermissionRepository;
import com.example.project.repository.ExpenseRepository;
import com.example.project.repository.FinancialsettingRepository;
import com.example.project.repository.InvoiceRepository;
import com.example.project.repository.InvoicedetailRepository;
import com.example.project.repository.PurchaseinvoiceRepository;
import com.example.project.repository.ReturnRepository;
import com.example.project.repository.ReturndetailRepository;
import com.example.project.repository.TaxperiodsnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Tax periods ("kỳ thuế") — the pharmacy's VAT declaration periods and the snapshot taken when one
 * is closed.
 *
 * <p><strong>A period is always a calendar quarter.</strong> The docx ties period length to the
 * revenue group (group 1 yearly, groups 2/3 quarterly, group 4 monthly), but group 4 is out of scope
 * (BA, 2026-07-27 — see {@link TaxRevenueGroup}), which leaves the quarter as the only shape. A
 * group-1 period is still generated and closed so the chain below stays unbroken; it just declares
 * nothing.</p>
 *
 * <p><strong>Everything hangs off a chain, because the row does not store its own group.</strong>
 * {@code Taxperiodsnapshot} has {@code nextPeriodTaxType} — the group for the period <em>after</em>
 * it — and no column for the group the period itself was declared under. So:</p>
 * <pre>
 *   group(period N)            = nextPeriodTaxType(period N-1)
 *   vatCarryforwardIn(N)       = vatCarryforwardOut(N-1)
 *   group(first ever period)   = Financialsetting.revenueGroup      &lt;- the only seed
 * </pre>
 * <p>Two different things therefore flow along the same chain, which is why closing must be
 * sequential and why an already-closed period is frozen (only the newest may still be corrected).
 * Those write-side rules land with the closing feature; what is here is the read/compute half.</p>
 *
 * <p><strong>The group is never derived from revenue.</strong> Crossing a revenue threshold only
 * raises a warning (BA, 2026-07-27); {@code nextPeriodTaxType} defaults to the current group and a
 * human changes it if they decide to. That is the "suggest a reference value, but let the user
 * override" convention this codebase already uses for a supplier payment's amount.</p>
 *
 * <p><strong>Three date-storage styles, one period.</strong> {@code Invoice.date} is a
 * {@code LocalDateTime} of Vietnam wall-clock time, while {@code Purchaseinvoice.date} and
 * {@code Return.returnDate} are real {@code Instant}s. A quarter boundary therefore has to be
 * expressed twice — see {@link #localStart}/{@link #instantStart} and friends. Both are half-open
 * {@code [start, end)} so a document written at 23:59 on the last day of a quarter still belongs to
 * it; getting that wrong silently moves a sale into the next declaration.</p>
 */
@Service
public class TaxperiodsnapshotService {

    /** Every boundary in this service is Vietnam wall-clock, never the server's default zone. */
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Sanity bounds on a hand-typed year, so a stray digit cannot ask for quarter 1 of year 9999. */
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    /**
     * Expense types that reduce taxable income. Only running costs qualify: a refund payout has
     * already been taken off revenue, a debt payment settles a cost recognised earlier, and an
     * employee advance is not a cost at all. Purchase-linked slips are excluded separately, by the
     * repository query, because their money is already counted as cost of goods sold.
     */
    private static final List<String> DEDUCTIBLE_EXPENSE_TYPES = List.of(ExpenseType.OPERATIONAL);

    /** Statuses at which an expense's money has genuinely left — mirrors {@code ExpenseService}. */
    private static final List<String> DISBURSED_EXPENSE_STATUSES =
            List.of(ExpenseStatus.AWAITING_PAYMENT, ExpenseStatus.COMPLETED);

    private final TaxperiodsnapshotRepository taxperiodsnapshotRepository;
    private final InvoiceRepository invoiceRepository;
    private final ReturnRepository returnRepository;
    private final PurchaseinvoiceRepository purchaseinvoiceRepository;
    private final FinancialsettingRepository financialsettingRepository;
    private final AccountpermissionRepository accountpermissionRepository;
    private final InvoicedetailRepository invoicedetailRepository;
    private final ReturndetailRepository returndetailRepository;
    private final ExpenseRepository expenseRepository;
    private final PurchaseinvoiceService purchaseinvoiceService;

    public TaxperiodsnapshotService(TaxperiodsnapshotRepository taxperiodsnapshotRepository,
                                    InvoiceRepository invoiceRepository,
                                    ReturnRepository returnRepository,
                                    PurchaseinvoiceRepository purchaseinvoiceRepository,
                                    FinancialsettingRepository financialsettingRepository,
                                    AccountpermissionRepository accountpermissionRepository,
                                    InvoicedetailRepository invoicedetailRepository,
                                    ReturndetailRepository returndetailRepository,
                                    ExpenseRepository expenseRepository,
                                    PurchaseinvoiceService purchaseinvoiceService) {
        this.taxperiodsnapshotRepository = taxperiodsnapshotRepository;
        this.invoiceRepository = invoiceRepository;
        this.returnRepository = returnRepository;
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.financialsettingRepository = financialsettingRepository;
        this.accountpermissionRepository = accountpermissionRepository;
        this.invoicedetailRepository = invoicedetailRepository;
        this.returndetailRepository = returndetailRepository;
        this.expenseRepository = expenseRepository;
        this.purchaseinvoiceService = purchaseinvoiceService;
    }

    // ------------------------------------------------------------------ the period itself

    /**
     * One declaration period. {@code label} is the business key stored in
     * {@code Taxperiodsnapshot.periodLabel} and matches the docx's example format, e.g.
     * {@code "2026-Q3"} — which also happens to sort chronologically as a string.
     */
    public record TaxPeriod(String label, LocalDate startDate, LocalDate endDate) {

        public boolean contains(LocalDate date) {
            return date != null && !date.isBefore(startDate) && !date.isAfter(endDate);
        }
    }

    /** The calendar quarter a date falls in. */
    public static TaxPeriod quarterOf(LocalDate date) {
        int quarter = (date.getMonthValue() - 1) / 3 + 1;
        return quarter(date.getYear(), quarter);
    }

    /** Quarter {@code 1..4} of {@code year}, as its label and inclusive date bounds. */
    public static TaxPeriod quarter(int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new IllegalArgumentException("Quý phải nằm trong khoảng 1–4");
        }
        LocalDate start = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
        LocalDate end = start.plusMonths(3).minusDays(1);
        return new TaxPeriod(year + "-Q" + quarter, start, end);
    }

    /** The quarter immediately following the one that ended on {@code endDate}. */
    public static TaxPeriod quarterAfter(LocalDate endDate) {
        return quarterOf(endDate.plusDays(1));
    }

    /** Today's quarter, in Vietnam time. */
    public TaxPeriod currentQuarter() {
        return quarterOf(LocalDate.now(VN_ZONE));
    }

    /**
     * The period a "close the books" action would operate on: the one right after the last closed
     * period, or — when nothing has ever been closed — the quarter we are in now.
     */
    @Transactional(readOnly = true)
    public TaxPeriod nextPeriodToClose() {
        return taxperiodsnapshotRepository.findFirstByOrderByStartDateDescIdDesc()
                .map(Taxperiodsnapshot::getEndDate)
                .map(TaxperiodsnapshotService::quarterAfter)
                .orElseGet(this::currentQuarter);
    }

    // ------------------------------------------------------------------ period boundaries

    /** Inclusive lower bound for a {@code LocalDateTime} column ({@code Invoice.date}). */
    static LocalDateTime localStart(TaxPeriod period) {
        return period.startDate().atStartOfDay();
    }

    /** <em>Exclusive</em> upper bound: midnight opening the day after the period's last day. */
    static LocalDateTime localEndExclusive(TaxPeriod period) {
        return period.endDate().plusDays(1).atStartOfDay();
    }

    /** Inclusive lower bound for an {@code Instant} column, at Vietnam midnight. */
    static Instant instantStart(TaxPeriod period) {
        return period.startDate().atStartOfDay(VN_ZONE).toInstant();
    }

    /** <em>Exclusive</em> upper bound for an {@code Instant} column, at Vietnam midnight. */
    static Instant instantEndExclusive(TaxPeriod period) {
        return period.endDate().plusDays(1).atStartOfDay(VN_ZONE).toInstant();
    }

    // ------------------------------------------------------------------ the chain

    /**
     * The closed period immediately preceding {@code period} — the one supplying its revenue group
     * and its opening carry-forward. Chosen by "latest period that ended before this one starts", so
     * a hole in the chain degrades to the newest snapshot before the hole rather than throwing.
     */
    @Transactional(readOnly = true)
    public Optional<Taxperiodsnapshot> previousSnapshot(TaxPeriod period) {
        return taxperiodsnapshotRepository.findAllOldestFirst().stream()
                .filter(snapshot -> snapshot.getEndDate() != null)
                .filter(snapshot -> snapshot.getEndDate().isBefore(period.startDate()))
                .max(Comparator.comparing(Taxperiodsnapshot::getEndDate));
    }

    /**
     * Revenue group in force for a period: the previous snapshot's {@code nextPeriodTaxType},
     * falling back to {@code Financialsetting.revenueGroup} for the very first period (or when an
     * older snapshot left the column null).
     */
    @Transactional(readOnly = true)
    public Integer groupForPeriod(TaxPeriod period) {
        return previousSnapshot(period)
                .map(Taxperiodsnapshot::getNextPeriodTaxType)
                .orElseGet(this::settingRevenueGroup);
    }

    /** The group the pharmacy is on right now, i.e. the group of the current quarter. */
    @Transactional(readOnly = true)
    public Integer currentRevenueGroup() {
        return groupForPeriod(currentQuarter());
    }

    /**
     * Configured group, defaulting to {@link TaxRevenueGroup#DIRECT} when no financial setting row
     * exists yet — the same fallback {@code ReturnPurchaseService.revenueGroup()} uses, so the two
     * never disagree about whether input VAT is deductible.
     */
    private Integer settingRevenueGroup() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(Financialsetting::getRevenueGroup)
                .orElse(TaxRevenueGroup.DIRECT);
    }

    // ------------------------------------------------------------------ automatic group transition

    /**
     * The group to apply after {@code period}, given the group it was itself taxed under. Two rules
     * (see {@code TaxRevenueNotificationService.transitionRule}):
     *
     * <p><strong>1 → 2 is immediate.</strong> The very quarter revenue crosses ngưỡng 1 must already
     * be taxed under nhóm 2, so this returns {@link TaxRevenueGroup#DIRECT} the moment the year's
     * revenue reaches the threshold — regardless of which quarter {@code period} is.</p>
     *
     * <p><strong>2 → 3 is deferred to next year.</strong> Crossing ngưỡng 2 mid-year still owes nhóm
     * 2 for the rest of the year; nhóm 3 only starts the following January. So this only returns
     * {@link TaxRevenueGroup#DEDUCTION} when {@code period} is itself the year's last quarter
     * (December) — exactly when the caller is deciding the group for next year's first quarter.</p>
     */
    private Integer autoNextGroup(TaxPeriod period, Integer groupOfPeriod) {
        Financialsetting setting = financialsettingRepository.findFirstByOrderByIdAsc().orElse(null);
        int year = period.startDate().getYear();

        if (Integer.valueOf(TaxRevenueGroup.EXEMPT).equals(groupOfPeriod)) {
            if (revenueForYear(year).compareTo(threshold1(setting)) >= 0) {
                return TaxRevenueGroup.DIRECT;
            }
        } else if (Integer.valueOf(TaxRevenueGroup.DIRECT).equals(groupOfPeriod)
                && period.endDate().getMonthValue() == 12) {
            if (revenueForYear(year).compareTo(threshold2(setting)) >= 0) {
                return TaxRevenueGroup.DEDUCTION;
            }
        }
        return groupOfPeriod;
    }

    /** What {@link #autoNextGroup} would decide for {@code period} — for the preview screen to show. */
    @Transactional(readOnly = true)
    public Integer previewAutoNextGroup(TaxPeriod period) {
        return autoNextGroup(period, groupForPeriod(period));
    }

    /** Outcome of {@link #applyAutomaticGroupTransition()} — whether it changed anything, and to what. */
    public record GroupTransitionResult(boolean changed, Integer fromGroup, Integer toGroup) {
    }

    /**
     * Retroactively fixes the 1 → 2 transition the moment it is detected, so the quarter already in
     * progress ends up taxed under nhóm 2 in full — the "tính thuế ngay từ chính quý phát sinh vượt
     * ngưỡng" rule. The 2 → 3 transition needs no eager action here: it is decided inside
     * {@link #closePeriod}/{@link #updateLatest} exactly when the year's last quarter is closed,
     * which by definition cannot happen before that quarter — and therefore the year — has ended.
     *
     * <p>Called from wherever the Tax Period screens are opened or a period is closed, the same
     * event-driven timing the revenue-threshold notification already uses — there is no background
     * scheduler in this app, so the correction lands the next time someone visits, not the instant
     * the threshold is actually crossed.</p>
     *
     * <p>Idempotent: once the group is no longer {@link TaxRevenueGroup#EXEMPT}, this is a no-op, so
     * it is safe to call on every page load.</p>
     */
    @Transactional
    public GroupTransitionResult applyAutomaticGroupTransition() {
        Integer currentGroup = currentRevenueGroup();
        if (!Integer.valueOf(TaxRevenueGroup.EXEMPT).equals(currentGroup)) {
            return new GroupTransitionResult(false, currentGroup, currentGroup);
        }

        TaxPeriod current = currentQuarter();
        if (!Integer.valueOf(TaxRevenueGroup.DIRECT).equals(autoNextGroup(current, currentGroup))) {
            return new GroupTransitionResult(false, currentGroup, currentGroup);
        }

        // No previous snapshot at all means we are still in the very first period ever, before
        // anything has ever closed — there is nothing on the chain to retro-fix, so the seed itself
        // (Financialsetting.revenueGroup) is what needs to change instead.
        previousSnapshot(current).ifPresent(snapshot -> {
            snapshot.setNextPeriodTaxType(TaxRevenueGroup.DIRECT);
            taxperiodsnapshotRepository.save(snapshot);
        });
        syncFinancialSettingRevenueGroup(TaxRevenueGroup.DIRECT);

        return new GroupTransitionResult(true, TaxRevenueGroup.EXEMPT, TaxRevenueGroup.DIRECT);
    }

    /**
     * Keeps {@code Financialsetting.revenueGroup} mirroring the group actually in force. Nothing in
     * this service reads the column for that purpose once a period exists — {@link #groupForPeriod}
     * always follows the snapshot chain — but {@code ReturnPurchaseService} still reads it directly,
     * so leaving it stale would make that module disagree with the tax period screens about which
     * group is current.
     */
    private void syncFinancialSettingRevenueGroup(Integer group) {
        Financialsetting setting = financialsettingRepository.findFirstByOrderByIdAsc().orElse(null);
        if (setting == null || group.equals(setting.getRevenueGroup())) {
            return;
        }
        setting.setRevenueGroup(group);
        financialsettingRepository.save(setting);
    }

    private BigDecimal threshold1(Financialsetting setting) {
        return setting != null && setting.getAnnualRevenueThreshold1() != null
                ? setting.getAnnualRevenueThreshold1()
                : new BigDecimal("1000000000.00");
    }

    private BigDecimal threshold2(Financialsetting setting) {
        return setting != null && setting.getAnnualRevenueThreshold2() != null
                ? setting.getAnnualRevenueThreshold2()
                : new BigDecimal("3000000000.00");
    }

    // ------------------------------------------------------------------ live computation

    // ------------------------------------------------------------------ revenue

    /**
     * Revenue between two dates, both inclusive: what was sold, less what customers brought back.
     *
     * <p>Exists so the pharmacy has <strong>one</strong> definition of revenue. {@link #computePeriod}
     * needs it per quarter (it is the base of the group-2 percentage tax and of the group-3 profit),
     * and the revenue-threshold warning needs it per year to decide when the household crosses into
     * the next group. Two hand-written copies of the same sum would eventually disagree — and then
     * the yearly figure would stop being the sum of its quarters, on exactly the number that decides
     * which tax regime applies.</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal revenueBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Khoảng thời gian tính doanh thu không hợp lệ");
        }
        TaxPeriod span = new TaxPeriod(from + " → " + to, from, to);
        List<Invoice> invoices = invoiceRepository.findInPeriod(localStart(span), localEndExclusive(span));
        List<Return> customerReturns = returnRepository
                .findInPeriod(instantStart(span), instantEndExclusive(span)).stream()
                .filter(TaxperiodsnapshotService::isApprovedCustomerReturn)
                .toList();
        return scaled(revenueOf(invoices, customerReturns));
    }

    /**
     * Revenue of a whole calendar year — what the revenue-threshold warning compares against
     * {@code Financialsetting.annualRevenueThreshold1/2}. The thresholds are annual, and the group
     * a household belongs to is decided by the year's revenue, not by any single quarter's.
     */
    @Transactional(readOnly = true)
    public BigDecimal revenueForYear(int year) {
        return revenueBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    /**
     * The definition itself: sales less refunds, never negative. Kept private and taking the already
     * loaded rows so {@link #computePeriod} does not have to fetch them twice.
     */
    private static BigDecimal revenueOf(List<Invoice> invoices, List<Return> approvedCustomerReturns) {
        return sum(invoices, Invoice::getTotal)
                .subtract(sum(approvedCustomerReturns, Return::getTotalRefund))
                .max(BigDecimal.ZERO);
    }

    /** Computes {@link #nextPeriodToClose()} without storing anything. */
    @Transactional(readOnly = true)
    public TaxPeriodComputationResponse computeNextPeriod() {
        return computePeriod(nextPeriodToClose());
    }

    /**
     * Computes an explicitly chosen quarter, or {@link #nextPeriodToClose()} when either part is
     * missing — what the preview screen calls, since its year/quarter pickers are optional.
     */
    @Transactional(readOnly = true)
    public TaxPeriodComputationResponse computeQuarter(Integer year, Integer quarter) {
        return computePeriod(resolveQuarter(year, quarter));
    }

    /** Same fallback as {@link #computeQuarter}, exposed so a caller can label the picker. */
    @Transactional(readOnly = true)
    public TaxPeriod resolveQuarter(Integer year, Integer quarter) {
        if (year == null || quarter == null) {
            return nextPeriodToClose();
        }
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new IllegalArgumentException("Năm phải nằm trong khoảng " + MIN_YEAR + "–" + MAX_YEAR);
        }
        return quarter(year, quarter);
    }

    /** Years the picker offers, newest first — a window around the period waiting to be closed. */
    @Transactional(readOnly = true)
    public List<Integer> selectableYears() {
        int pivot = nextPeriodToClose().startDate().getYear();
        return List.of(pivot + 1, pivot, pivot - 1, pivot - 2);
    }

    /**
     * Totals a period straight from the transactions inside it. Nothing is written — this is the
     * "xem trước" figure, and later the pre-fill when the period is closed.
     *
     * <p>Output VAT is simply what the sales carry, less the VAT on goods customers brought back
     * <em>in this period</em> — the docx is explicit that a refund is deducted from the period its
     * {@code returnDate} falls in, not the period of the original sale. Input VAT is gated on the
     * group: outside the deduction method there is nothing to deduct, which mirrors
     * {@code ReturnPurchaseService.isDeductionGroup()}. A tax-exempt group declares nothing at
     * all.</p>
     */
    @Transactional(readOnly = true)
    public TaxPeriodComputationResponse computePeriod(TaxPeriod period) {
        Integer group = groupForPeriod(period);
        boolean deduction = TaxRevenueGroup.isDeductionGroup(group);
        boolean exempt = TaxRevenueGroup.isTaxExempt(group);

        Optional<Taxperiodsnapshot> previous = previousSnapshot(period);
        BigDecimal carryIn = previous
                .map(Taxperiodsnapshot::getVatCarryforwardOut)
                .map(TaxperiodsnapshotService::safe)
                .orElse(BigDecimal.ZERO);

        List<Invoice> invoices = invoiceRepository.findInPeriod(localStart(period), localEndExclusive(period));
        List<Return> returns = returnRepository.findInPeriod(instantStart(period), instantEndExclusive(period));
        List<Purchaseinvoice> purchases =
                purchaseinvoiceRepository.findInPeriod(instantStart(period), instantEndExclusive(period));

        List<Return> customerReturns = returns.stream()
                .filter(TaxperiodsnapshotService::isApprovedCustomerReturn)
                .toList();
        List<Return> supplierReturns = returns.stream()
                .filter(TaxperiodsnapshotService::isApprovedSupplierReturn)
                .toList();
        List<Purchaseinvoice> deductiblePurchases = purchases.stream()
                .filter(purchaseinvoiceService::isDeductible)
                .toList();

        BigDecimal revenue = revenueOf(invoices, customerReturns);

        // Only the deduction method has an input side, so only it can carry credit between periods.
        if (!deduction) {
            carryIn = BigDecimal.ZERO;
        }

        BigDecimal vatOutputFromSales;
        BigDecimal vatOutputReturnDeduction;
        if (deduction) {
            vatOutputFromSales = sum(invoices, Invoice::getTotalVATOutput);
            vatOutputReturnDeduction = sum(customerReturns, Return::getTotalVATRefund);
        } else if (exempt) {
            vatOutputFromSales = BigDecimal.ZERO;
            vatOutputReturnDeduction = BigDecimal.ZERO;
        } else {
            // Percentage method: the whole liability is a flat rate on revenue, and the returns
            // already came off the revenue above rather than off a separate output-VAT figure.
            vatOutputFromSales = revenue.multiply(TaxRevenueGroup.DIRECT_VAT_RATE);
            vatOutputReturnDeduction = BigDecimal.ZERO;
        }
        BigDecimal vatOutput = vatOutputFromSales.subtract(vatOutputReturnDeduction);

        BigDecimal vatInputFromPurchases =
                deduction ? sum(deductiblePurchases, Purchaseinvoice::getTotalVATInput) : BigDecimal.ZERO;
        BigDecimal vatInputReturnReversal =
                deduction ? sum(supplierReturns, Return::getTotalVATRefund) : BigDecimal.ZERO;
        BigDecimal vatInput = vatInputFromPurchases.subtract(vatInputReturnReversal);

        BigDecimal vatPayable = vatPayable(vatOutput, vatInput, carryIn);
        BigDecimal carryOut = carryForwardOut(vatOutput, vatInput, carryIn);

        // --- personal income tax. Group 2 pays a slice of revenue; group 3 pays a slice of profit,
        //     so only group 3 needs the cost side worked out at all.
        BigDecimal costOfGoodsSold = BigDecimal.ZERO;
        BigDecimal operatingCost = BigDecimal.ZERO;
        BigDecimal taxableIncome = BigDecimal.ZERO;
        BigDecimal incomeTax = BigDecimal.ZERO;

        if (deduction) {
            costOfGoodsSold = safe(invoicedetailRepository
                    .sumCostOfGoodsSoldInPeriod(localStart(period), localEndExclusive(period)))
                    .subtract(safe(returndetailRepository.sumRestockedCostInPeriod(
                            instantStart(period), instantEndExclusive(period), ReturnStatus.DEBT)))
                    .max(BigDecimal.ZERO);
            operatingCost = safe(expenseRepository.sumOperatingCostInPeriod(
                    instantStart(period), instantEndExclusive(period),
                    DEDUCTIBLE_EXPENSE_TYPES, DISBURSED_EXPENSE_STATUSES));
            // A loss-making quarter owes nothing; it does not create a negative tax.
            taxableIncome = revenue.subtract(costOfGoodsSold).subtract(operatingCost).max(BigDecimal.ZERO);
            incomeTax = taxableIncome.multiply(TaxRevenueGroup.DEDUCTION_PIT_RATE);
        } else if (!exempt) {
            incomeTax = revenue.multiply(TaxRevenueGroup.DIRECT_PIT_RATE);
        }

        return new TaxPeriodComputationResponse(
                period.label(),
                DATE.format(period.startDate()),
                DATE.format(period.endDate()),
                group,
                TaxRevenueGroup.label(group),
                deduction,
                exempt,
                !deduction && !exempt,
                scaled(revenue),
                percent(TaxRevenueGroup.DIRECT_VAT_RATE),
                scaled(vatOutputFromSales),
                scaled(vatOutputReturnDeduction),
                scaled(vatOutput),
                scaled(vatInputFromPurchases),
                scaled(vatInputReturnReversal),
                scaled(vatInput),
                scaled(carryIn),
                scaled(carryOut),
                scaled(vatPayable),
                scaled(costOfGoodsSold),
                scaled(operatingCost),
                scaled(taxableIncome),
                scaled(incomeTax),
                percent(deduction
                        ? TaxRevenueGroup.DEDUCTION_PIT_RATE
                        : (exempt ? BigDecimal.ZERO : TaxRevenueGroup.DIRECT_PIT_RATE)),
                invoices.size(),
                customerReturns.size(),
                deductiblePurchases.size(),
                supplierReturns.size(),
                purchases.size() - deductiblePurchases.size(),
                previous.map(Taxperiodsnapshot::getPeriodLabel).orElse(null),
                taxperiodsnapshotRepository.existsByPeriodLabel(period.label()));
    }

    /**
     * A customer return is one carrying an {@code invoiceID} — the FK discriminator every other
     * service uses. Only an approved slip counts, and for a customer return "approved" is
     * {@link ReturnStatus#DEBT} ("Nợ"), since approving one means the pharmacy now owes the customer.
     */
    private static boolean isApprovedCustomerReturn(Return ret) {
        return ret.getInvoiceID() != null && ReturnStatus.DEBT.equals(ret.getStatus());
    }

    /** Supplier return: {@code purchaseID} set and no {@code invoiceID}; approved is "Đã duyệt". */
    private static boolean isApprovedSupplierReturn(Return ret) {
        return ret.getInvoiceID() == null
                && ret.getPurchaseID() != null
                && ReturnPurchaseStatus.APPROVED.equals(ret.getStatus());
    }

    // ------------------------------------------------------------------ who may close a period

    /**
     * Whether the pharmacy currently employs an accountant with an enabled account. The BA's rule
     * hands the closing job to the Accountant and falls back to the Owner only when there is none,
     * so this is a live-data question, not a static role matrix.
     */
    @Transactional(readOnly = true)
    public boolean hasActiveAccountant() {
        return accountpermissionRepository.existsActiveByRole(RoleConstants.ACCOUNTANT);
    }

    /** Whether the given role may close a period right now. */
    @Transactional(readOnly = true)
    public boolean canClose(String role) {
        if (RoleConstants.ACCOUNTANT.equals(role)) {
            return true;
        }
        return RoleConstants.OWNER.equals(role) && !hasActiveAccountant();
    }

    /**
     * Why the button is disabled, or {@code null} when it is not. Returned as a message rather than
     * a boolean so the screen can explain itself instead of silently hiding the action.
     */
    @Transactional(readOnly = true)
    public String closeBlockedReason(String role, TaxPeriod period) {
        if (!canClose(role)) {
            return "Chỉ Kế toán được chốt kỳ thuế. Chủ nhà thuốc chỉ chốt thay khi nhà thuốc "
                    + "không có tài khoản kế toán đang hoạt động.";
        }
        if (taxperiodsnapshotRepository.existsByPeriodLabel(period.label())) {
            return "Kỳ " + period.label() + " đã được chốt.";
        }
        TaxPeriod due = nextPeriodToClose();
        if (!due.label().equals(period.label())) {
            return "Phải chốt lần lượt: kỳ cần chốt tiếp theo là " + due.label() + ".";
        }
        if (!period.endDate().isBefore(LocalDate.now(VN_ZONE))) {
            return "Kỳ " + period.label() + " chưa kết thúc (đến hết "
                    + DATE.format(period.endDate()) + "), chưa thể chốt số.";
        }
        return null;
    }

    // ------------------------------------------------------------------ closing a period

    /**
     * Writes the snapshot for the period currently due. The VAT figures come from
     * {@link #computePeriod}, never from the request — the same "server-authoritative amount" rule
     * Expense uses for a refund payout, and for the same reason: a declaration is derived from the
     * books, not typed. The group to apply next is likewise derived, by {@link #autoNextGroup} — see
     * that method and {@link #applyAutomaticGroupTransition} for the two transition rules.
     *
     * @return the new snapshot's id
     */
    @Transactional
    public Integer closePeriod(TaxPeriodCloseRequest request, String actorRole) {
        if (request == null || request.getPeriodLabel() == null || request.getPeriodLabel().isBlank()) {
            throw new IllegalArgumentException("Thiếu thông tin kỳ thuế cần chốt");
        }

        // Fixes a pending 1 → 2 escalation onto the chain before `due` is even resolved, in case
        // nobody visited the Tax Period screens since the threshold was crossed.
        applyAutomaticGroupTransition();

        TaxPeriod due = nextPeriodToClose();
        if (!due.label().equals(request.getPeriodLabel().trim())) {
            // A stale tab, or someone closing a quarter out of order: both would break the chain.
            throw new IllegalArgumentException(
                    "Kỳ cần chốt tiếp theo là " + due.label() + ", không phải "
                            + request.getPeriodLabel().trim() + ". Vui lòng tải lại trang.");
        }

        String blocked = closeBlockedReason(actorRole, due);
        if (blocked != null) {
            throw new IllegalArgumentException(blocked);
        }

        BigDecimal cashBalance = request.getCashBalanceAtPeriodEnd();
        if (cashBalance != null && cashBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Số dư quỹ tiền mặt cuối kỳ không được âm");
        }

        TaxPeriodComputationResponse computed = computePeriod(due);
        Integer nextGroup = autoNextGroup(due, computed.getRevenueGroup());

        Taxperiodsnapshot snapshot = new Taxperiodsnapshot();
        snapshot.setPeriodLabel(due.label());
        snapshot.setStartDate(due.startDate());
        snapshot.setEndDate(due.endDate());
        snapshot.setVatOutput(computed.getVatOutput());
        snapshot.setVatInput(computed.getVatInput());
        snapshot.setVatCarryforwardIn(computed.getVatCarryforwardIn());
        snapshot.setVatCarryforwardOut(computed.getVatCarryforwardOut());
        snapshot.setIncomeTax(computed.getIncomeTax());
        snapshot.setNextPeriodTaxType(nextGroup);
        snapshot.setQuarterlyRevenue(cashBalance);
        snapshot.setNote(trimToNull(request.getNote()));
        snapshot.setRecordedAt(LocalDateTime.now(VN_ZONE));

        Integer newId = taxperiodsnapshotRepository.save(snapshot).getId();
        // `due.endDate()` has necessarily already passed (closeBlockedReason enforces it), so this
        // satisfies "khi vượt qua endDate thì cập nhật revenueGroup" for the group taking over now.
        syncFinancialSettingRevenueGroup(nextGroup);
        return newId;
    }

    /**
     * Amends the newest closed period — the correction path for a period closed with the wrong
     * numbers. An older period cannot be touched at all, because every period after it was built
     * from its carry-forward and its {@code nextPeriodTaxType}.
     *
     * <p>{@code vatCarryforwardOut} is re-derived rather than accepted, so a period can never both
     * owe tax and carry credit forward. The figures are otherwise taken as typed: this is an
     * amendment to a filed declaration, not a recalculation — see {@link TaxPeriodUpdateRequest}.</p>
     */
    @Transactional
    public void updateLatest(Integer id, TaxPeriodUpdateRequest request) {
        Taxperiodsnapshot snapshot = taxperiodsnapshotRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ thuế"));

        Integer latestId = taxperiodsnapshotRepository.findFirstByOrderByStartDateDescIdDesc()
                .map(Taxperiodsnapshot::getId)
                .orElse(null);
        if (latestId == null || !latestId.equals(snapshot.getId())) {
            throw new IllegalArgumentException(
                    "Chỉ kỳ thuế mới nhất mới được điều chỉnh — các kỳ trước đó đã bị khóa.");
        }

        // Re-derived the same way as closePeriod(), not accepted from the request — see
        // autoNextGroup(). An amendment can no more hand-pick the next group than a fresh close can.
        Integer groupOfSnapshot = storedGroup(snapshot);
        Integer nextGroup = snapshot.getStartDate() == null
                ? groupOfSnapshot
                : autoNextGroup(new TaxPeriod(snapshot.getPeriodLabel(), snapshot.getStartDate(),
                        snapshot.getEndDate()), groupOfSnapshot);

        BigDecimal vatOutput = requireNonNegative(request.getVatOutput(), "Thuế GTGT đầu ra");
        BigDecimal vatInput = requireNonNegative(request.getVatInput(), "Thuế GTGT đầu vào");
        BigDecimal carryIn = requireNonNegative(request.getVatCarryforwardIn(),
                "Thuế GTGT khấu trừ chuyển từ kỳ trước");
        BigDecimal incomeTax = requireNonNegative(request.getIncomeTax(), "Thuế TNCN");

        // A period below threshold 1 owes nothing at all — no VAT, no PIT. The form does not offer
        // the fields, but an amendment must not be able to invent an obligation for it either, so
        // anything posted is dropped rather than validated. Only the group and the notes survive.
        if (TaxRevenueGroup.isTaxExempt(storedGroup(snapshot))) {
            vatOutput = BigDecimal.ZERO;
            vatInput = BigDecimal.ZERO;
            carryIn = BigDecimal.ZERO;
            incomeTax = BigDecimal.ZERO;
        }

        BigDecimal cashBalance = request.getCashBalanceAtPeriodEnd();
        if (cashBalance != null && cashBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Số dư quỹ tiền mặt cuối kỳ không được âm");
        }

        snapshot.setVatOutput(vatOutput);
        snapshot.setVatInput(vatInput);
        snapshot.setVatCarryforwardIn(carryIn);
        snapshot.setVatCarryforwardOut(carryForwardOut(vatOutput, vatInput, carryIn));
        snapshot.setIncomeTax(incomeTax);
        snapshot.setNextPeriodTaxType(nextGroup);
        snapshot.setQuarterlyRevenue(cashBalance);
        snapshot.setNote(trimToNull(request.getNote()));

        taxperiodsnapshotRepository.save(snapshot);
        // This snapshot is already closed, so its endDate has necessarily already passed.
        syncFinancialSettingRevenueGroup(nextGroup);
    }

    /**
     * A blank field means zero, but a negative one is always a typo — no declaration line can be
     * below zero once the returns have already been netted off inside it.
     */
    private static BigDecimal requireNonNegative(BigDecimal value, String label) {
        BigDecimal safe = safe(value);
        if (safe.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(label + " không được âm");
        }
        return scaled(safe);
    }

    // ------------------------------------------------------------------ read screens

    /** Closed periods, newest first. */
    @Transactional(readOnly = true)
    public List<TaxPeriodListItemResponse> listPeriods() {
        List<Taxperiodsnapshot> snapshots = taxperiodsnapshotRepository.findAllNewestFirst();
        Integer newestId = snapshots.stream().findFirst().map(Taxperiodsnapshot::getId).orElse(null);

        return snapshots.stream()
                .map(snapshot -> toListItem(snapshot, newestId))
                .toList();
    }

    private TaxPeriodListItemResponse toListItem(Taxperiodsnapshot snapshot, Integer newestId) {
        BigDecimal vatOutput = safe(snapshot.getVatOutput());
        BigDecimal vatInput = safe(snapshot.getVatInput());
        BigDecimal carryIn = safe(snapshot.getVatCarryforwardIn());
        Integer group = storedGroup(snapshot);

        return new TaxPeriodListItemResponse(
                snapshot.getId(),
                snapshot.getPeriodLabel(),
                formatDate(snapshot.getStartDate()),
                formatDate(snapshot.getEndDate()),
                group,
                TaxRevenueGroup.shortLabel(group),
                scaled(vatPayable(vatOutput, vatInput, carryIn)),
                scaled(snapshot.getIncomeTax()),
                formatDateTime(snapshot.getRecordedAt()),
                snapshot.getId() != null && snapshot.getId().equals(newestId));
    }

    /** One closed period, with the chain context needed to read its figures. */
    @Transactional(readOnly = true)
    public TaxPeriodDetailResponse getDetail(Integer id) {
        Taxperiodsnapshot snapshot = taxperiodsnapshotRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ thuế"));

        Integer group = storedGroup(snapshot);
        BigDecimal vatOutput = safe(snapshot.getVatOutput());
        BigDecimal vatInput = safe(snapshot.getVatInput());
        BigDecimal carryIn = safe(snapshot.getVatCarryforwardIn());

        Optional<Taxperiodsnapshot> previous = snapshot.getStartDate() == null
                ? Optional.empty()
                : previousSnapshot(new TaxPeriod(snapshot.getPeriodLabel(),
                        snapshot.getStartDate(), snapshot.getEndDate()));

        boolean editable = taxperiodsnapshotRepository.findFirstByOrderByStartDateDescIdDesc()
                .map(Taxperiodsnapshot::getId)
                .filter(latestId -> latestId.equals(snapshot.getId()))
                .isPresent();

        return new TaxPeriodDetailResponse(
                snapshot.getId(),
                snapshot.getPeriodLabel(),
                formatDate(snapshot.getStartDate()),
                formatDate(snapshot.getEndDate()),
                group,
                TaxRevenueGroup.label(group),
                TaxRevenueGroup.isDeductionGroup(group),
                TaxRevenueGroup.isTaxExempt(group),
                scaled(vatOutput),
                scaled(vatInput),
                scaled(carryIn),
                scaled(safe(snapshot.getVatCarryforwardOut())),
                scaled(vatPayable(vatOutput, vatInput, carryIn)),
                scaled(snapshot.getIncomeTax()),
                snapshot.getQuarterlyRevenue() == null
                        ? null
                        : scaled(snapshot.getQuarterlyRevenue()),
                snapshot.getNextPeriodTaxType(),
                TaxRevenueGroup.label(snapshot.getNextPeriodTaxType()),
                formatDateTime(snapshot.getRecordedAt()),
                snapshot.getNote(),
                previous.map(Taxperiodsnapshot::getPeriodLabel).orElse(null),
                editable);
    }

    /**
     * The group a stored period was declared under. It is not on the row, so it is read back off the
     * chain — the preceding snapshot's {@code nextPeriodTaxType}, or the configured group for the
     * first one.
     */
    private Integer storedGroup(Taxperiodsnapshot snapshot) {
        if (snapshot.getStartDate() == null) {
            return settingRevenueGroup();
        }
        return groupForPeriod(new TaxPeriod(
                snapshot.getPeriodLabel(), snapshot.getStartDate(), snapshot.getEndDate()));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * VAT actually payable for a period, floored at zero.
     *
     * <p>This and {@link #carryForwardOut} are two sides of the same subtraction — whichever way the
     * balance falls, exactly one of them is non-zero. They are written once here because the same
     * arithmetic is needed when computing a period, when rendering a closed one, and when amending
     * one; three copies would eventually disagree.</p>
     */
    private static BigDecimal vatPayable(BigDecimal vatOutput, BigDecimal vatInput, BigDecimal carryIn) {
        return balance(vatOutput, vatInput, carryIn).max(BigDecimal.ZERO);
    }

    /** VAT credit carried into the next period, floored at zero. See {@link #vatPayable}. */
    private static BigDecimal carryForwardOut(BigDecimal vatOutput, BigDecimal vatInput, BigDecimal carryIn) {
        return balance(vatOutput, vatInput, carryIn).negate().max(BigDecimal.ZERO);
    }

    private static BigDecimal balance(BigDecimal vatOutput, BigDecimal vatInput, BigDecimal carryIn) {
        return safe(vatOutput).subtract(safe(vatInput)).subtract(safe(carryIn));
    }

    private static <T> BigDecimal sum(List<T> items, java.util.function.Function<T, BigDecimal> field) {
        return items.stream()
                .map(field)
                .map(TaxperiodsnapshotService::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Money elsewhere in the app (Price Settings aside) carries 2 decimals; match it. */
    private static BigDecimal scaled(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** A stored rate ({@code 0.005}) as the number a screen shows ({@code 0.50}). */
    private static BigDecimal percent(BigDecimal rate) {
        return safe(rate).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String formatDate(LocalDate date) {
        return date == null ? "—" : DATE.format(date);
    }

    private static String formatDateTime(LocalDateTime dateTime) {
        return dateTime == null ? "—" : DATE_TIME.format(dateTime);
    }
}
