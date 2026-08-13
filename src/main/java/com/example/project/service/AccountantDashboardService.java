package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ReturnStatus;
import com.example.project.dto.response.AccountantDashboardResponse;
import com.example.project.dto.response.DashboardView.ChartSeries;
import com.example.project.dto.response.DashboardView.DashboardChart;
import com.example.project.entity.Expense;
import com.example.project.entity.Income;
import com.example.project.entity.Invoice;
import com.example.project.entity.Purchaseinvoice;
import com.example.project.entity.Return;
import com.example.project.repository.ExpenseRepository;
import com.example.project.repository.IncomeRepository;
import com.example.project.repository.InvoiceRepository;
import com.example.project.repository.InvoicedetailRepository;
import com.example.project.repository.PurchaseinvoiceRepository;
import com.example.project.repository.ReturnRepository;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountantDashboardService {

    private static final ZoneId VN_ZONE =
            ZoneId.of("Asia/Ho_Chi_Minh");

    private static final DateTimeFormatter DATE_DISPLAY =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DateTimeFormatter DATE_TIME_DISPLAY =
            DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy HH:mm"
            );

    private static final String INCOME_STATUS_DRAFT =
            "Nháp";

    private static final String INCOME_STATUS_PENDING =
            "Chờ duyệt";

    private static final String INCOME_STATUS_REJECTED =
            "Từ chối";

    private static final String INVOICE_STATUS_SIGNED =
            "Đã ký";

    private static final String STATUS_CANCELLED =
            "Đã hủy";

    private final InvoiceRepository invoiceRepository;

    private final InvoicedetailRepository
            invoicedetailRepository;

    private final IncomeRepository incomeRepository;

    private final ExpenseRepository expenseRepository;

    private final PurchaseinvoiceRepository
            purchaseinvoiceRepository;

    private final ReturnRepository returnRepository;

    private final PurchaseinvoiceService
            purchaseinvoiceService;

    public AccountantDashboardService(
            InvoiceRepository invoiceRepository,
            InvoicedetailRepository invoicedetailRepository,
            IncomeRepository incomeRepository,
            ExpenseRepository expenseRepository,
            PurchaseinvoiceRepository purchaseinvoiceRepository,
            ReturnRepository returnRepository,
            PurchaseinvoiceService purchaseinvoiceService
    ) {
        this.invoiceRepository =
                invoiceRepository;

        this.invoicedetailRepository =
                invoicedetailRepository;

        this.incomeRepository =
                incomeRepository;

        this.expenseRepository =
                expenseRepository;

        this.purchaseinvoiceRepository =
                purchaseinvoiceRepository;

        this.returnRepository =
                returnRepository;

        this.purchaseinvoiceService =
                purchaseinvoiceService;
    }

    @Transactional(readOnly = true)
    public AccountantDashboardResponse getDashboard(
            String currentAccountName
    ) {
        return getDashboard(
                currentAccountName,
                "week",
                null,
                "/accountant"
        );
    }

    @Transactional(readOnly = true)
    public AccountantDashboardResponse getDashboard(
            String currentAccountName,
            String requestedPeriod,
            String requestedDate
    ) {
        return getDashboard(
                currentAccountName,
                requestedPeriod,
                requestedDate,
                "/accountant"
        );
    }

    /**
     * Tạo dashboard tài chính dùng chung cho Owner và Accountant.
     *
     * @param dashboardBasePath nhận "/owner" hoặc "/accountant"
     *                          để tạo đúng các đường dẫn thao tác.
     */
    @Transactional(readOnly = true)
    public AccountantDashboardResponse getDashboard(
            String currentAccountName,
            String requestedPeriod,
            String requestedDate,
            String dashboardBasePath
    ) {
        LocalDate today =
                LocalDate.now(VN_ZONE);

        String basePath =
                normalizeDashboardBasePath(
                        dashboardBasePath
                );

        OverviewPeriod period =
                OverviewPeriod.from(
                        requestedPeriod
                );

        LocalDate selectedDate =
                parseSelectedDate(
                        requestedDate,
                        today
                );

        List<Invoice> invoices =
                invoiceRepository
                        .findAllWithRelations();

        List<Income> incomes =
                incomeRepository
                        .findAllWithRelations();

        List<Expense> expenses =
                expenseRepository
                        .findAllWithRelations();

        List<Purchaseinvoice> purchaseInvoices =
                purchaseinvoiceRepository
                        .findAllWithRelations();

        List<Return> returns =
                returnRepository
                        .findAllWithRelations();

        List<ChartBucket> selectedBuckets =
                buildBuckets(
                        period,
                        selectedDate
                );

        ChartBucket selectedRange =
                new ChartBucket(
                        "",
                        selectedBuckets
                                .get(0)
                                .startInclusive(),
                        selectedBuckets
                                .get(
                                        selectedBuckets.size()
                                                - 1
                                )
                                .endExclusive()
                );

        LocalDateTime rangeStart =
                toVietnamLocalDateTime(
                        selectedRange.startInclusive()
                );

        LocalDateTime rangeEnd =
                toVietnamLocalDateTime(
                        selectedRange.endExclusive()
                );

        /*
         * Chỉ lấy các hóa đơn bán hàng còn hiệu lực,
         * tránh tính đồng thời hóa đơn cũ và hóa đơn thay thế.
         */
        BigDecimal periodRevenue =
                invoiceRepository
                        .findValidInPeriod(
                                rangeStart,
                                rangeEnd
                        )
                        .stream()
                        .map(Invoice::getTotal)
                        .map(this::safe)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        /*
         * Giá vốn được tính theo giá nhập thực tế của từng lô:
         * baseQtyDeducted × batch.importPricePerBase.
         */
        BigDecimal periodCostOfGoodsSold =
                safe(
                        invoicedetailRepository
                                .sumCostOfGoodsSoldInPeriod(
                                        rangeStart,
                                        rangeEnd
                                )
                );

        BigDecimal periodGrossProfit =
                periodRevenue.subtract(
                        periodCostOfGoodsSold
                );

        List<Income> effectiveIncomes =
                incomes
                        .stream()
                        .filter(this::isEffectiveIncome)
                        .toList();

        List<Expense> effectiveExpenses =
                expenses
                        .stream()
                        .filter(this::isEffectiveExpense)
                        .toList();

        long pendingExpenses =
                expenses
                        .stream()
                        .filter(expense ->
                                isStatus(
                                        expense.getStatus(),
                                        ExpenseStatus.PENDING
                                )
                        )
                        .count();

        BigDecimal invoiceDebt =
                invoices
                        .stream()
                        .filter(invoice ->
                                !isStatus(
                                        invoice.getStatus(),
                                        STATUS_CANCELLED
                                )
                        )
                        .map(Invoice::getDebtAmount)
                        .map(this::safe)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal purchaseDebt =
                purchaseInvoices
                        .stream()
                        .map(this::calculatePurchaseDebt)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal returnDebt =
                returns
                        .stream()
                        .filter(ret ->
                                ret.getInvoiceID()
                                        != null
                        )
                        .filter(ret ->
                                isStatus(
                                        ret.getStatus(),
                                        ReturnStatus.DEBT
                                )
                        )
                        .map(this::remainingCustomerRefund)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal totalOutstandingDebt =
                invoiceDebt
                        .add(purchaseDebt)
                        .add(returnDebt);

        long outstandingDocumentCount =
                countOutstandingDocuments(
                        invoices,
                        purchaseInvoices,
                        returns
                );

        BigDecimal periodIncome =
                effectiveIncomes
                        .stream()
                        .filter(income ->
                                isWithin(
                                        income.getDate(),
                                        selectedRange
                                )
                        )
                        .map(Income::getAmount)
                        .map(this::safe)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal periodExpense =
                effectiveExpenses
                        .stream()
                        .filter(expense ->
                                isWithin(
                                        expense.getDate(),
                                        selectedRange
                                )
                        )
                        .map(Expense::getPaid)
                        .map(this::safe)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        long unpaidInvoices =
                invoices
                        .stream()
                        .filter(invoice ->
                                !isStatus(
                                        invoice.getStatus(),
                                        STATUS_CANCELLED
                                )
                        )
                        .filter(invoice ->
                                safe(
                                        invoice.getDebtAmount()
                                ).compareTo(
                                        BigDecimal.ZERO
                                ) > 0
                        )
                        .count();

        return new AccountantDashboardResponse(
                defaultText(
                        currentAccountName,
                        "/owner".equals(basePath)
                                ? "Chủ nhà thuốc"
                                : "Kế toán"
                ),

                "Tổng quan kinh doanh và tài chính "
                        + "Nhà thuốc Hằng Ngọc • "
                        + today.format(DATE_DISPLAY),

                quickActions(basePath),

                List.of(
                        new AccountantDashboardResponse.MetricCard(
                                "Doanh thu",
                                money(periodRevenue),
                                periodDescription(
                                        period,
                                        selectedDate
                                ),
                                "ti ti-chart-line",
                                "success",
                                basePath + "/invoices"
                        ),

                        new AccountantDashboardResponse.MetricCard(
                                "Giá vốn hàng bán",
                                money(
                                        periodCostOfGoodsSold
                                ),
                                "Tính theo giá nhập thực tế "
                                        + "của từng lô",
                                "ti ti-packages",
                                "info",
                                basePath + "/invoices"
                        ),

                        new AccountantDashboardResponse.MetricCard(
                                "Lợi nhuận gộp",
                                money(periodGrossProfit),
                                "Doanh thu trừ giá vốn hàng bán",
                                "ti ti-coins",
                                periodGrossProfit.signum() < 0
                                        ? "danger"
                                        : "success",
                                basePath + "/invoices"
                        ),

                        new AccountantDashboardResponse.MetricCard(
                                "Phiếu chi chờ duyệt",
                                String.valueOf(
                                        pendingExpenses
                                ),
                                "Cần chủ nhà thuốc phê duyệt",
                                "ti ti-receipt",
                                "success",
                                basePath + "/expenses"
                        ),

                        new AccountantDashboardResponse.MetricCard(
                                "Công nợ còn lại",
                                money(
                                        totalOutstandingDebt
                                ),
                                outstandingDocumentCount
                                        + " chứng từ còn công nợ",
                                "ti ti-credit-card",
                                "warning",
                                basePath + "/debts"
                        ),

                        new AccountantDashboardResponse.MetricCard(
                                "Tổng thu",
                                money(periodIncome),
                                periodDescription(
                                        period,
                                        selectedDate
                                ),
                                "ti ti-cash",
                                "success",
                                basePath + "/incomes"
                        ),

                        new AccountantDashboardResponse.MetricCard(
                                "Tổng chi",
                                money(periodExpense),
                                periodDescription(
                                        period,
                                        selectedDate
                                ),
                                "ti ti-cash-banknote-off",
                                "danger",
                                basePath + "/expenses"
                        ),

                        new AccountantDashboardResponse.MetricCard(
                                "Hóa đơn chưa thanh toán",
                                String.valueOf(
                                        unpaidInvoices
                                ),
                                "Tổng công nợ "
                                        + money(invoiceDebt),
                                "ti ti-file-invoice",
                                "warning",
                                basePath + "/invoices"
                        )
                ),

                buildOverviewChart(
                        effectiveIncomes,
                        effectiveExpenses,
                        invoices,
                        purchaseInvoices,
                        returns,
                        period,
                        selectedDate,
                        selectedBuckets
                ),

                period.getValue(),

                selectedDate.toString(),

                periodDescription(
                        period,
                        selectedDate
                ),

                buildAlerts(
                        expenses,
                        invoices,
                        purchaseInvoices,
                        returns,
                        today,
                        basePath
                ),

                buildRecentActivities(
                        incomes,
                        expenses,
                        invoices,
                        purchaseInvoices,
                        returns,
                        basePath
                )
        );
    }

    private List<AccountantDashboardResponse.QuickAction>
    quickActions(
            String basePath
    ) {
        return List.of(
                new AccountantDashboardResponse.QuickAction(
                        "Tạo khoản thu",
                        basePath + "/incomes/create",
                        true
                ),

                new AccountantDashboardResponse.QuickAction(
                        "Xem công nợ",
                        basePath + "/debts",
                        false
                ),

                new AccountantDashboardResponse.QuickAction(
                        "Tạo khoản chi",
                        basePath + "/expenses/create",
                        false
                ),

                new AccountantDashboardResponse.QuickAction(
                        "Xem kỳ thuế",
                        basePath + "/tax-periods",
                        false
                )
        );
    }

    private long countOutstandingDocuments(
            List<Invoice> invoices,
            List<Purchaseinvoice> purchaseInvoices,
            List<Return> returns
    ) {
        long invoiceCount =
                invoices
                        .stream()
                        .filter(invoice ->
                                !isStatus(
                                        invoice.getStatus(),
                                        STATUS_CANCELLED
                                )
                        )
                        .filter(invoice ->
                                safe(
                                        invoice.getDebtAmount()
                                ).compareTo(
                                        BigDecimal.ZERO
                                ) > 0
                        )
                        .count();

        long purchaseCount =
                purchaseInvoices
                        .stream()
                        .filter(invoice ->
                                calculatePurchaseDebt(
                                        invoice
                                ).compareTo(
                                        BigDecimal.ZERO
                                ) > 0
                        )
                        .count();

        long returnCount =
                returns
                        .stream()
                        .filter(ret ->
                                ret.getInvoiceID()
                                        != null
                        )
                        .filter(ret ->
                                isStatus(
                                        ret.getStatus(),
                                        ReturnStatus.DEBT
                                )
                        )
                        .filter(ret ->
                                remainingCustomerRefund(
                                        ret
                                ).compareTo(
                                        BigDecimal.ZERO
                                ) > 0
                        )
                        .count();

        return invoiceCount
                + purchaseCount
                + returnCount;
    }

    private DashboardChart buildOverviewChart(
            List<Income> incomes,
            List<Expense> expenses,
            List<Invoice> invoices,
            List<Purchaseinvoice> purchaseInvoices,
            List<Return> returns,
            OverviewPeriod period,
            LocalDate selectedDate,
            List<ChartBucket> buckets
    ) {
        List<BigDecimal> revenueSeries =
                buckets
                        .stream()
                        .map(this::revenueWithin)
                        .toList();

        List<BigDecimal> costSeries =
                buckets
                        .stream()
                        .map(this::costOfGoodsSoldWithin)
                        .toList();

        List<BigDecimal> profitSeries =
                IntStream
                        .range(
                                0,
                                buckets.size()
                        )
                        .mapToObj(index ->
                                revenueSeries
                                        .get(index)
                                        .subtract(
                                                costSeries
                                                        .get(index)
                                        )
                        )
                        .toList();

        List<BigDecimal> incomeSeries =
                buckets
                        .stream()
                        .map(bucket ->
                                incomes
                                        .stream()
                                        .filter(income ->
                                                isWithin(
                                                        income.getDate(),
                                                        bucket
                                                )
                                        )
                                        .map(Income::getAmount)
                                        .map(this::safe)
                                        .reduce(
                                                BigDecimal.ZERO,
                                                BigDecimal::add
                                        )
                        )
                        .toList();

        List<BigDecimal> expenseSeries =
                buckets
                        .stream()
                        .map(bucket ->
                                expenses
                                        .stream()
                                        .filter(expense ->
                                                isWithin(
                                                        expense.getDate(),
                                                        bucket
                                                )
                                        )
                                        .map(Expense::getPaid)
                                        .map(this::safe)
                                        .reduce(
                                                BigDecimal.ZERO,
                                                BigDecimal::add
                                        )
                        )
                        .toList();

        List<BigDecimal> debtSeries =
                buckets
                        .stream()
                        .map(bucket ->
                                debtGeneratedWithin(
                                        bucket,
                                        invoices,
                                        purchaseInvoices,
                                        returns
                                )
                        )
                        .toList();

        return new DashboardChart(
                "Tổng quan kinh doanh và tài chính",
                "line",

                buckets
                        .stream()
                        .map(ChartBucket::label)
                        .toList(),

                List.of(
                        new ChartSeries(
                                "Doanh thu",
                                revenueSeries
                        ),

                        new ChartSeries(
                                "Giá vốn",
                                costSeries
                        ),

                        new ChartSeries(
                                "Lợi nhuận gộp",
                                profitSeries
                        ),

                        new ChartSeries(
                                "Thu",
                                incomeSeries
                        ),

                        new ChartSeries(
                                "Chi",
                                expenseSeries
                        ),

                        new ChartSeries(
                                "Công nợ phát sinh",
                                debtSeries
                        )
                )
        );
    }
    private BigDecimal revenueWithin(
            ChartBucket bucket
    ) {
        return invoiceRepository
                .findValidInPeriod(
                        toVietnamLocalDateTime(
                                bucket.startInclusive()
                        ),
                        toVietnamLocalDateTime(
                                bucket.endExclusive()
                        )
                )
                .stream()
                .map(Invoice::getTotal)
                .map(this::safe)
                .reduce(
                        BigDecimal.ZERO,
                        BigDecimal::add
                );
    }

    private BigDecimal costOfGoodsSoldWithin(
            ChartBucket bucket
    ) {
        return safe(
                invoicedetailRepository
                        .sumCostOfGoodsSoldInPeriod(
                                toVietnamLocalDateTime(
                                        bucket.startInclusive()
                                ),
                                toVietnamLocalDateTime(
                                        bucket.endExclusive()
                                )
                        )
        );
    }

    private LocalDateTime toVietnamLocalDateTime(
            Instant value
    ) {
        return LocalDateTime.ofInstant(
                value,
                VN_ZONE
        );
    }

    private BigDecimal debtGeneratedWithin(
            ChartBucket bucket,
            List<Invoice> invoices,
            List<Purchaseinvoice> purchaseInvoices,
            List<Return> returns
    ) {
        BigDecimal invoiceDebt =
                invoices
                        .stream()
                        .filter(invoice ->
                                !isStatus(
                                        invoice.getStatus(),
                                        STATUS_CANCELLED
                                )
                        )
                        .filter(invoice ->
                                isWithin(
                                        invoice.getDate(),
                                        bucket
                                )
                        )
                        .map(Invoice::getDebtAmount)
                        .map(this::safe)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal purchaseDebt =
                purchaseInvoices
                        .stream()
                        .filter(invoice ->
                                isWithin(
                                        invoice.getDate(),
                                        bucket
                                )
                        )
                        .map(this::calculatePurchaseDebt)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal returnDebt =
                returns
                        .stream()
                        .filter(ret ->
                                ret.getInvoiceID() != null
                        )
                        .filter(ret ->
                                isStatus(
                                        ret.getStatus(),
                                        ReturnStatus.DEBT
                                )
                        )
                        .filter(ret ->
                                isWithin(
                                        ret.getReturnDate(),
                                        bucket
                                )
                        )
                        .map(this::remainingCustomerRefund)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        return invoiceDebt
                .add(purchaseDebt)
                .add(returnDebt);
    }

    private List<ChartBucket> buildBuckets(
            OverviewPeriod period,
            LocalDate selectedDate
    ) {
        return switch (period) {
            case DAY ->
                    IntStream
                            .range(0, 24)
                            .mapToObj(hour -> {
                                ZonedDateTime start =
                                        selectedDate
                                                .atStartOfDay(
                                                        VN_ZONE
                                                )
                                                .plusHours(
                                                        hour
                                                );

                                return bucket(
                                        String.format(
                                                "%02d:00",
                                                hour
                                        ),
                                        start,
                                        start.plusHours(1)
                                );
                            })
                            .toList();

            case WEEK -> {
                LocalDate monday =
                        selectedDate.minusDays(
                                selectedDate
                                        .getDayOfWeek()
                                        .getValue()
                                        - 1L
                        );

                yield IntStream
                        .range(0, 7)
                        .mapToObj(index -> {
                            LocalDate date =
                                    monday.plusDays(
                                            index
                                    );

                            ZonedDateTime start =
                                    date.atStartOfDay(
                                            VN_ZONE
                                    );

                            return bucket(
                                    vnDayLabel(date),
                                    start,
                                    start.plusDays(1)
                            );
                        })
                        .toList();
            }

            case MONTH -> {
                LocalDate firstDay =
                        selectedDate
                                .withDayOfMonth(1);

                int totalDays =
                        firstDay.lengthOfMonth();

                yield IntStream
                        .range(
                                0,
                                totalDays
                        )
                        .mapToObj(index -> {
                            LocalDate date =
                                    firstDay.plusDays(
                                            index
                                    );

                            ZonedDateTime start =
                                    date.atStartOfDay(
                                            VN_ZONE
                                    );

                            return bucket(
                                    String.format(
                                            "%02d/%02d",
                                            date.getDayOfMonth(),
                                            date.getMonthValue()
                                    ),
                                    start,
                                    start.plusDays(1)
                            );
                        })
                        .toList();
            }

            case QUARTER -> {
                int firstMonth =
                        (
                                (
                                        selectedDate
                                                .getMonthValue()
                                                - 1
                                )
                                        / 3
                        )
                                * 3
                                + 1;

                LocalDate quarterStart =
                        LocalDate.of(
                                selectedDate.getYear(),
                                firstMonth,
                                1
                        );

                yield IntStream
                        .range(0, 3)
                        .mapToObj(index -> {
                            LocalDate month =
                                    quarterStart
                                            .plusMonths(
                                                    index
                                            );

                            ZonedDateTime start =
                                    month.atStartOfDay(
                                            VN_ZONE
                                    );

                            return bucket(
                                    "Tháng "
                                            + month
                                            .getMonthValue(),
                                    start,
                                    start.plusMonths(1)
                            );
                        })
                        .toList();
            }

            case YEAR ->
                    IntStream
                            .range(1, 13)
                            .mapToObj(monthValue -> {
                                LocalDate month =
                                        LocalDate.of(
                                                selectedDate
                                                        .getYear(),
                                                monthValue,
                                                1
                                        );

                                ZonedDateTime start =
                                        month.atStartOfDay(
                                                VN_ZONE
                                        );

                                return bucket(
                                        "Tháng "
                                                + monthValue,
                                        start,
                                        start.plusMonths(1)
                                );
                            })
                            .toList();
        };
    }

    private ChartBucket bucket(
            String label,
            ZonedDateTime start,
            ZonedDateTime end
    ) {
        return new ChartBucket(
                label,
                start.toInstant(),
                end.toInstant()
        );
    }

    private boolean isWithin(
            Instant value,
            ChartBucket bucket
    ) {
        return value != null
                && !value.isBefore(
                bucket.startInclusive()
        )
                && value.isBefore(
                bucket.endExclusive()
        );
    }

    private boolean isWithin(
            LocalDateTime value,
            ChartBucket bucket
    ) {
        return value != null
                && isWithin(
                value
                        .atZone(VN_ZONE)
                        .toInstant(),
                bucket
        );
    }

    private List<AccountantDashboardResponse.AlertItem>
    buildAlerts(
            List<Expense> expenses,
            List<Invoice> invoices,
            List<Purchaseinvoice> purchaseInvoices,
            List<Return> returns,
            LocalDate today,
            String basePath
    ) {
        List<AccountantDashboardResponse.AlertItem>
                alerts = new ArrayList<>();

        expenses
                .stream()
                .filter(expense ->
                        isStatus(
                                expense.getStatus(),
                                ExpenseStatus.PENDING
                        )
                )
                .sorted(
                        Comparator.comparing(
                                Expense::getDate,
                                Comparator.nullsLast(
                                        Comparator
                                                .reverseOrder()
                                )
                        )
                )
                .limit(2)
                .forEach(expense ->
                        alerts.add(
                                new AccountantDashboardResponse.AlertItem(
                                        "Phiếu chi "
                                                + displayCode(
                                                expense
                                                        .getExpenseCode()
                                        )
                                                + " đang chờ duyệt",
                                        "warning",
                                        basePath
                                                + "/expenses/"
                                                + expense.getId()
                                )
                        )
                );

        purchaseInvoices
                .stream()
                .filter(invoice ->
                        invoice.getDueDate() != null
                )
                .filter(invoice ->
                        invoice
                                .getDueDate()
                                .isBefore(today)
                )
                .filter(invoice ->
                        calculatePurchaseDebt(
                                invoice
                        ).compareTo(
                                BigDecimal.ZERO
                        ) > 0
                )
                .sorted(
                        Comparator.comparing(
                                Purchaseinvoice::getDueDate,
                                Comparator.nullsLast(
                                        Comparator
                                                .naturalOrder()
                                )
                        )
                )
                .limit(2)
                .forEach(invoice ->
                        alerts.add(
                                new AccountantDashboardResponse.AlertItem(
                                        "Phiếu nhập "
                                                + displayCode(
                                                invoice
                                                        .getPurchaseInvoiceCode()
                                        )
                                                + " đã quá hạn thanh toán",
                                        "danger",
                                        basePath
                                                + "/purchase-invoices/"
                                                + invoice.getId()
                                )
                        )
                );

        invoices
                .stream()
                .filter(
                        this::isInvoiceWaitingForProcessing
                )
                .sorted(
                        Comparator.comparing(
                                Invoice::getDate,
                                Comparator.nullsLast(
                                        Comparator
                                                .reverseOrder()
                                )
                        )
                )
                .limit(2)
                .forEach(invoice ->
                        alerts.add(
                                new AccountantDashboardResponse.AlertItem(
                                        "Hóa đơn "
                                                + displayCode(
                                                invoice
                                                        .getInvoiceNumber()
                                        )
                                                + " cần ký",
                                        "warning",
                                        basePath
                                                + "/invoices/"
                                                + invoice.getId()
                                )
                        )
                );

        invoices
                .stream()
                .filter(invoice ->
                        safe(
                                invoice.getDebtAmount()
                        ).compareTo(
                                BigDecimal.ZERO
                        ) > 0
                )
                .filter(invoice ->
                        invoice.getDate() != null
                )
                .filter(invoice ->
                        invoice
                                .getDate()
                                .toLocalDate()
                                .isBefore(
                                        today.minusDays(2)
                                )
                )
                .sorted(
                        Comparator.comparing(
                                Invoice::getDate,
                                Comparator.nullsLast(
                                        Comparator
                                                .naturalOrder()
                                )
                        )
                )
                .limit(2)
                .forEach(invoice ->
                        alerts.add(
                                new AccountantDashboardResponse.AlertItem(
                                        "Công nợ "
                                                + displayCode(
                                                invoice
                                                        .getInvoiceNumber()
                                        )
                                                + " còn "
                                                + money(
                                                invoice
                                                        .getDebtAmount()
                                        ),
                                        "danger",
                                        basePath
                                                + "/invoices/"
                                                + invoice.getId()
                                )
                        )
                );

        purchaseInvoices
                .stream()
                .filter(invoice ->
                        !isStatus(
                                invoice.getStatus(),
                                STATUS_CANCELLED
                        )
                )
                .filter(invoice ->
                        !purchaseinvoiceService
                                .isDeductible(invoice)
                )
                .sorted(
                        Comparator.comparing(
                                Purchaseinvoice::getDate,
                                Comparator.nullsLast(
                                        Comparator
                                                .reverseOrder()
                                )
                        )
                )
                .limit(2)
                .forEach(invoice ->
                        alerts.add(
                                new AccountantDashboardResponse.AlertItem(
                                        "Phiếu nhập "
                                                + displayCode(
                                                invoice
                                                        .getPurchaseInvoiceCode()
                                        )
                                                + " cần kiểm tra "
                                                + "điều kiện khấu trừ",
                                        "warning",
                                        basePath
                                                + "/purchase-invoices/"
                                                + invoice.getId()
                                )
                        )
                );

        returns
                .stream()
                .filter(ret ->
                        ret.getInvoiceID() != null
                )
                .filter(ret ->
                        isStatus(
                                ret.getStatus(),
                                ReturnStatus.DEBT
                        )
                )
                .sorted(
                        Comparator.comparing(
                                Return::getReturnDate,
                                Comparator.nullsLast(
                                        Comparator
                                                .reverseOrder()
                                )
                        )
                )
                .limit(2)
                .forEach(ret ->
                        alerts.add(
                                new AccountantDashboardResponse.AlertItem(
                                        "Phiếu trả "
                                                + displayCode(
                                                ret.getReturnCode()
                                        )
                                                + " đang chờ hoàn tiền",
                                        "warning",
                                        basePath
                                                + "/returns/"
                                                + ret.getId()
                                )
                        )
                );

        if (alerts.isEmpty()) {
            return List.of(
                    new AccountantDashboardResponse.AlertItem(
                            "Không có cảnh báo tài chính "
                                    + "cần xử lý",
                            "success",
                            basePath + "/dashboard"
                    )
            );
        }

        return alerts
                .stream()
                .limit(6)
                .toList();
    }

    private List<AccountantDashboardResponse.ActivityRow>
    buildRecentActivities(
            List<Income> incomes,
            List<Expense> expenses,
            List<Invoice> invoices,
            List<Purchaseinvoice> purchaseInvoices,
            List<Return> returns,
            String basePath
    ) {
        List<AccountantDashboardResponse.ActivityRow>
                rows = new ArrayList<>();

        incomes.forEach(income ->
                rows.add(
                        new AccountantDashboardResponse.ActivityRow(
                                displayCode(
                                        income.getIncomeCode()
                                ),
                                "Khoản thu",
                                formatInstant(
                                        income.getDate()
                                ),
                                relatedParty(income),
                                money(
                                        income.getAmount()
                                ),
                                paymentMethod(
                                        income.getPaidByCash(),
                                        income.getPaidByBanking(),
                                        income.getPaidByCredit()
                                ),
                                defaultText(
                                        income.getStatus(),
                                        "—"
                                ),
                                activityTone(
                                        income.getStatus()
                                ),
                                basePath
                                        + "/incomes/"
                                        + income.getId(),
                                epoch(
                                        income.getDate()
                                )
                        )
                )
        );

        expenses.forEach(expense ->
                rows.add(
                        new AccountantDashboardResponse.ActivityRow(
                                displayCode(
                                        expense.getExpenseCode()
                                ),
                                "Khoản chi",
                                formatInstant(
                                        expense.getDate()
                                ),
                                relatedParty(expense),
                                money(
                                        expense.getAmount()
                                ),
                                paymentMethod(
                                        expense.getPaidByCash(),
                                        expense.getPaidByBanking(),
                                        expense.getPaidByCredit()
                                ),
                                defaultText(
                                        expense.getStatus(),
                                        "—"
                                ),
                                activityTone(
                                        expense.getStatus()
                                ),
                                basePath
                                        + "/expenses/"
                                        + expense.getId(),
                                epoch(
                                        expense.getDate()
                                )
                        )
                )
        );

        invoices.forEach(invoice ->
                rows.add(
                        new AccountantDashboardResponse.ActivityRow(
                                displayCode(
                                        invoice.getInvoiceNumber()
                                ),
                                "Hóa đơn",
                                formatDateTime(
                                        invoice.getDate()
                                ),
                                invoice.getCustomerID() == null
                                        ? "Khách lẻ"
                                        : defaultText(
                                        invoice
                                                .getCustomerID()
                                                .getName(),
                                        "Khách lẻ"
                                ),
                                money(
                                        invoice.getTotal()
                                ),
                                invoicePaymentMethod(
                                        invoice
                                ),
                                defaultText(
                                        invoice.getStatus(),
                                        "—"
                                ),
                                activityTone(
                                        invoice.getStatus()
                                ),
                                basePath
                                        + "/invoices/"
                                        + invoice.getId(),
                                epoch(
                                        invoice.getDate()
                                )
                        )
                )
        );

        purchaseInvoices.forEach(invoice ->
                rows.add(
                        new AccountantDashboardResponse.ActivityRow(
                                displayCode(
                                        invoice
                                                .getPurchaseInvoiceCode()
                                ),
                                "Phiếu nhập",
                                formatInstant(
                                        invoice.getDate()
                                ),
                                invoice.getSupplierID() == null
                                        ? "—"
                                        : defaultText(
                                        invoice
                                                .getSupplierID()
                                                .getName(),
                                        "—"
                                ),
                                money(
                                        invoice.getTotalAmount()
                                ),
                                calculatePurchaseDebt(
                                        invoice
                                ).compareTo(
                                        BigDecimal.ZERO
                                ) > 0
                                        ? "Chưa thanh toán đủ"
                                        : "Đã thanh toán",
                                defaultText(
                                        invoice.getStatus(),
                                        "—"
                                ),
                                activityTone(
                                        invoice.getStatus()
                                ),
                                basePath
                                        + "/purchase-invoices/"
                                        + invoice.getId(),
                                epoch(
                                        invoice.getDate()
                                )
                        )
                )
        );

        returns
                .stream()
                .filter(ret ->
                        ret.getInvoiceID() != null
                )
                .forEach(ret ->
                        rows.add(
                                new AccountantDashboardResponse.ActivityRow(
                                        displayCode(
                                                ret.getReturnCode()
                                        ),
                                        "Trả hàng",
                                        formatInstant(
                                                ret.getReturnDate()
                                        ),
                                        relatedParty(ret),
                                        money(
                                                ret.getTotalRefund()
                                        ),
                                        safe(
                                                ret.getOffsetDebtAmount()
                                        ).compareTo(
                                                BigDecimal.ZERO
                                        ) > 0
                                                ? "Bù trừ công nợ"
                                                : "Chờ thanh toán",
                                        defaultText(
                                                ret.getStatus(),
                                                "—"
                                        ),
                                        activityTone(
                                                ret.getStatus()
                                        ),
                                        basePath
                                                + "/returns/"
                                                + ret.getId(),
                                        epoch(
                                                ret.getReturnDate()
                                        )
                                )
                        )
                );

        return rows
                .stream()
                .sorted(
                        Comparator
                                .comparingLong(
                                        AccountantDashboardResponse
                                                .ActivityRow
                                                ::getSortEpoch
                                )
                                .reversed()
                )
                .limit(8)
                .toList();
    }
    private boolean isInvoiceWaitingForProcessing(
            Invoice invoice
    ) {
        return invoice != null
                && !isStatus(
                invoice.getStatus(),
                INVOICE_STATUS_SIGNED
        )
                && !isStatus(
                invoice.getStatus(),
                STATUS_CANCELLED
        );
    }

    private boolean isEffectiveIncome(
            Income income
    ) {
        return income != null
                && !isStatus(
                income.getStatus(),
                INCOME_STATUS_DRAFT
        )
                && !isStatus(
                income.getStatus(),
                INCOME_STATUS_PENDING
        )
                && !isStatus(
                income.getStatus(),
                INCOME_STATUS_REJECTED
        );
    }

    private boolean isEffectiveExpense(
            Expense expense
    ) {
        return expense != null
                && !isStatus(
                expense.getStatus(),
                ExpenseStatus.DRAFT
        )
                && !isStatus(
                expense.getStatus(),
                ExpenseStatus.PENDING
        )
                && !isStatus(
                expense.getStatus(),
                ExpenseStatus.REJECTED
        )
                && !isStatus(
                expense.getStatus(),
                ExpenseStatus.CANCELLED
        );
    }

    private String relatedParty(
            Income income
    ) {
        if (income.getCustomerID() != null) {
            return defaultText(
                    income
                            .getCustomerID()
                            .getName(),
                    "Khách hàng"
            );
        }

        if (income.getSupplierID() != null) {
            return defaultText(
                    income
                            .getSupplierID()
                            .getName(),
                    "Nhà cung cấp"
            );
        }

        if (income.getAccountID() != null) {
            return defaultText(
                    income
                            .getAccountID()
                            .getName(),
                    "Nhân viên"
            );
        }

        if (income.getInvoiceID() != null
                && income
                .getInvoiceID()
                .getCustomerID() != null) {

            return defaultText(
                    income
                            .getInvoiceID()
                            .getCustomerID()
                            .getName(),
                    "Khách hàng"
            );
        }

        return defaultText(
                income.getReason(),
                "—"
        );
    }

    private String relatedParty(
            Expense expense
    ) {
        if (expense.getCustomerID() != null) {
            return defaultText(
                    expense
                            .getCustomerID()
                            .getName(),
                    "Khách hàng"
            );
        }

        if (expense.getSupplierID() != null) {
            return defaultText(
                    expense
                            .getSupplierID()
                            .getName(),
                    "Nhà cung cấp"
            );
        }

        if (expense.getAccountID() != null) {
            return defaultText(
                    expense
                            .getAccountID()
                            .getName(),
                    "Nhân viên"
            );
        }

        if (expense.getPurchaseID() != null
                && expense
                .getPurchaseID()
                .getSupplierID() != null) {

            return defaultText(
                    expense
                            .getPurchaseID()
                            .getSupplierID()
                            .getName(),
                    "Nhà cung cấp"
            );
        }

        if (expense.getReturnID() != null) {
            return relatedParty(
                    expense.getReturnID()
            );
        }

        return defaultText(
                expense.getReason(),
                "—"
        );
    }

    private String relatedParty(
            Return ret
    ) {
        if (ret.getInvoiceID() != null
                && ret
                .getInvoiceID()
                .getCustomerID() != null) {

            return defaultText(
                    ret
                            .getInvoiceID()
                            .getCustomerID()
                            .getName(),
                    "Khách hàng"
            );
        }

        if (ret.getPurchaseID() != null
                && ret
                .getPurchaseID()
                .getSupplierID() != null) {

            return defaultText(
                    ret
                            .getPurchaseID()
                            .getSupplierID()
                            .getName(),
                    "Nhà cung cấp"
            );
        }

        return "—";
    }

    private String paymentMethod(
            BigDecimal cash,
            BigDecimal banking,
            BigDecimal credit
    ) {
        List<String> methods =
                new ArrayList<>();

        if (safe(cash).compareTo(
                BigDecimal.ZERO
        ) > 0) {
            methods.add("Tiền mặt");
        }

        if (safe(banking).compareTo(
                BigDecimal.ZERO
        ) > 0) {
            methods.add("Chuyển khoản");
        }

        if (safe(credit).compareTo(
                BigDecimal.ZERO
        ) > 0) {
            methods.add("Bù trừ công nợ");
        }

        if (methods.isEmpty()) {
            return "—";
        }

        return methods.size() == 1
                ? methods.get(0)
                : "Hỗn hợp";
    }

    private String invoicePaymentMethod(
            Invoice invoice
    ) {
        List<String> methods =
                new ArrayList<>();

        if (safe(
                invoice.getPaidByCash()
        ).compareTo(
                BigDecimal.ZERO
        ) > 0) {
            methods.add("Tiền mặt");
        }

        if (safe(
                invoice.getPaidByBanking()
        ).compareTo(
                BigDecimal.ZERO
        ) > 0) {
            methods.add("Chuyển khoản");
        }

        if (safe(
                invoice.getDebtAmount()
        ).compareTo(
                BigDecimal.ZERO
        ) > 0) {
            methods.add("Công nợ");
        }

        if (methods.isEmpty()) {
            return "—";
        }

        return methods.size() == 1
                ? methods.get(0)
                : "Hỗn hợp";
    }

    private String activityTone(
            String status
    ) {
        if (status == null) {
            return "secondary";
        }

        String normalized =
                status
                        .trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (normalized.equals("hoàn thành")
                || normalized.equals(
                "đã hoàn thành"
        )
                || normalized.equals(
                "đã ký"
        )
                || normalized.equals(
                "đã duyệt"
        )) {

            return "success";
        }

        if (normalized.equals("từ chối")
                || normalized.equals(
                "đã hủy"
        )
                || normalized.equals("nợ")
                || normalized.contains(
                "còn nợ"
        )) {

            return "danger";
        }

        if (normalized.equals("chờ duyệt")
                || normalized.equals(
                "chờ thanh toán"
        )
                || normalized.equals("nháp")) {

            return "warning";
        }

        return "secondary";
    }

    private BigDecimal remainingCustomerRefund(
            Return ret
    ) {
        return safe(
                ret.getTotalRefund()
        )
                .subtract(
                        safe(
                                ret.getOffsetDebtAmount()
                        )
                )
                .max(
                        BigDecimal.ZERO
                );
    }

    private BigDecimal calculatePurchaseDebt(
            Purchaseinvoice invoice
    ) {
        if (invoice == null
                || isStatus(
                invoice.getStatus(),
                STATUS_CANCELLED
        )) {

            return BigDecimal.ZERO;
        }

        return safe(
                invoice.getTotalAmount()
        )
                .subtract(
                        safe(
                                invoice.getPaid()
                        )
                )
                .max(
                        BigDecimal.ZERO
                );
    }

    private boolean isDate(
            Instant instant,
            LocalDate date
    ) {
        return instant != null
                && instant
                .atZone(VN_ZONE)
                .toLocalDate()
                .equals(date);
    }

    private boolean isDate(
            LocalDateTime dateTime,
            LocalDate date
    ) {
        return dateTime != null
                && dateTime
                .toLocalDate()
                .equals(date);
    }

    private boolean isStatus(
            String actual,
            String expected
    ) {
        return actual != null
                && expected != null
                && actual
                .trim()
                .equalsIgnoreCase(
                        expected
                );
    }

    private LocalDate parseSelectedDate(
            String requestedDate,
            LocalDate fallback
    ) {
        if (requestedDate == null
                || requestedDate.isBlank()) {

            return fallback;
        }

        try {
            return LocalDate.parse(
                    requestedDate.trim()
            );
        } catch (
                DateTimeParseException exception
        ) {
            return fallback;
        }
    }

    /**
     * Chỉ chấp nhận hai base path hợp lệ.
     *
     * Nếu giá trị truyền vào không hợp lệ,
     * mặc định sử dụng đường dẫn Accountant.
     */
    private String normalizeDashboardBasePath(
            String value
    ) {
        return "/owner".equals(value)
                ? "/owner"
                : "/accountant";
    }

    private String periodDescription(
            OverviewPeriod period,
            LocalDate selectedDate
    ) {
        return switch (period) {
            case DAY ->
                    "Ngày "
                            + selectedDate.format(
                            DATE_DISPLAY
                    );

            case WEEK -> {
                LocalDate monday =
                        selectedDate.minusDays(
                                selectedDate
                                        .getDayOfWeek()
                                        .getValue()
                                        - 1L
                        );

                LocalDate sunday =
                        monday.plusDays(6);

                yield "Tuần "
                        + monday.format(
                        DATE_DISPLAY
                )
                        + " - "
                        + sunday.format(
                        DATE_DISPLAY
                );
            }

            case MONTH ->
                    String.format(
                            "Tháng %02d/%d",
                            selectedDate
                                    .getMonthValue(),
                            selectedDate
                                    .getYear()
                    );

            case QUARTER ->
                    "Quý "
                            + (
                            (
                                    selectedDate
                                            .getMonthValue()
                                            - 1
                            )
                                    / 3
                                    + 1
                    )
                            + "/"
                            + selectedDate
                            .getYear();

            case YEAR ->
                    "Năm "
                            + selectedDate
                            .getYear();
        };
    }

    private enum OverviewPeriod {
        DAY("day"),
        WEEK("week"),
        MONTH("month"),
        QUARTER("quarter"),
        YEAR("year");

        private final String value;

        OverviewPeriod(
                String value
        ) {
            this.value = value;
        }

        private String getValue() {
            return value;
        }

        private static OverviewPeriod from(
                String value
        ) {
            if (value != null) {
                for (OverviewPeriod period
                        : values()) {

                    if (period.value
                            .equalsIgnoreCase(
                                    value.trim()
                            )) {

                        return period;
                    }
                }
            }

            return WEEK;
        }
    }

    private record ChartBucket(
            String label,
            Instant startInclusive,
            Instant endExclusive
    ) {
    }

    private String vnDayLabel(
            LocalDate date
    ) {
        return switch (
                date.getDayOfWeek()
                ) {
            case MONDAY -> "Thứ 2";
            case TUESDAY -> "Thứ 3";
            case WEDNESDAY -> "Thứ 4";
            case THURSDAY -> "Thứ 5";
            case FRIDAY -> "Thứ 6";
            case SATURDAY -> "Thứ 7";
            case SUNDAY -> "Chủ Nhật";
        };
    }

    private String formatInstant(
            Instant instant
    ) {
        return instant == null
                ? "—"
                : DATE_TIME_DISPLAY.format(
                instant.atZone(VN_ZONE)
        );
    }

    private String formatDateTime(
            LocalDateTime value
    ) {
        return value == null
                ? "—"
                : value.format(
                DATE_TIME_DISPLAY
        );
    }

    private long epoch(
            Instant value
    ) {
        return value == null
                ? 0L
                : value.toEpochMilli();
    }

    private long epoch(
            LocalDateTime value
    ) {
        return value == null
                ? 0L
                : value
                .atZone(VN_ZONE)
                .toInstant()
                .toEpochMilli();
    }

    private BigDecimal safe(
            BigDecimal value
    ) {
        return value == null
                ? BigDecimal.ZERO
                : value;
    }

    private String money(
            BigDecimal value
    ) {
        NumberFormat formatter =
                NumberFormat.getNumberInstance(
                        new Locale(
                                "vi",
                                "VN"
                        )
                );

        formatter.setMaximumFractionDigits(0);

        return formatter.format(
                safe(value)
        ) + "đ";
    }

    private String displayCode(
            String code
    ) {
        return defaultText(
                code,
                "—"
        );
    }

    private String defaultText(
            String value,
            String fallback
    ) {
        return value == null
                || value.isBlank()
                ? fallback
                : value.trim();
    }
}
