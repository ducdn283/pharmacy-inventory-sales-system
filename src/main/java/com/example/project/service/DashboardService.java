package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ReturnStatus;
import com.example.project.constant.ShiftReportStatus;
import com.example.project.constant.StockAdjustmentStatus;
import com.example.project.constant.StockCountStatus;
import com.example.project.dto.response.ApprovalItemResponse;
import com.example.project.dto.response.DashboardView.MetricCard;
import com.example.project.dto.response.DashboardView.PerformanceRow;
import com.example.project.dto.response.DashboardView.QuickAction;
import com.example.project.dto.response.DashboardView.RecentInvoice;
import com.example.project.dto.response.DashboardView.RoleDashboard;
import com.example.project.dto.response.DashboardView.TodoItem;
import com.example.project.entity.Account;
import com.example.project.entity.Batch;
import com.example.project.entity.Customer;
import com.example.project.entity.Invoice;
import com.example.project.entity.Invoicedetail;
import com.example.project.entity.Product;
import com.example.project.entity.Purchaseinvoice;
import com.example.project.entity.Return;
import com.example.project.entity.Shiftreport;
import com.example.project.entity.Stockadjustment;
import com.example.project.entity.Stockcount;
import com.example.project.repository.BatchRepository;
import com.example.project.repository.CustomerRepository;
import com.example.project.repository.InvoiceRepository;
import com.example.project.repository.InvoicedetailRepository;
import com.example.project.repository.ProductRepository;
import com.example.project.repository.PurchaseinvoiceRepository;
import com.example.project.repository.ReturnRepository;
import com.example.project.repository.ShiftreportRepository;
import com.example.project.repository.StockadjustmentRepository;
import com.example.project.repository.StockcountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class DashboardService {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_DISPLAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME_DISPLAY = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter TIME_DISPLAY = DateTimeFormatter.ofPattern("HH:mm");

    private final InvoiceRepository invoiceRepository;
    private final InvoicedetailRepository invoicedetailRepository;
    private final ReturnRepository returnRepository;
    private final ProductRepository productRepository;
    private final BatchRepository batchRepository;
    private final CustomerRepository customerRepository;
    private final PurchaseinvoiceRepository purchaseinvoiceRepository;
    private final StockcountRepository stockcountRepository;
    private final StockadjustmentRepository stockadjustmentRepository;
    private final ShiftreportRepository shiftreportRepository;
    private final ApprovalService approvalService;

    public DashboardService(InvoiceRepository invoiceRepository,
                            InvoicedetailRepository invoicedetailRepository,
                            ReturnRepository returnRepository,
                            ProductRepository productRepository,
                            BatchRepository batchRepository,
                            CustomerRepository customerRepository,
                            PurchaseinvoiceRepository purchaseinvoiceRepository,
                            StockcountRepository stockcountRepository,
                            StockadjustmentRepository stockadjustmentRepository,
                            ShiftreportRepository shiftreportRepository,
                            ApprovalService approvalService) {
        this.invoiceRepository = invoiceRepository;
        this.invoicedetailRepository = invoicedetailRepository;
        this.returnRepository = returnRepository;
        this.productRepository = productRepository;
        this.batchRepository = batchRepository;
        this.customerRepository = customerRepository;
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.stockcountRepository = stockcountRepository;
        this.stockadjustmentRepository = stockadjustmentRepository;
        this.shiftreportRepository = shiftreportRepository;
        this.approvalService = approvalService;
    }

    @Transactional(readOnly = true)
    public RoleDashboard ownerDashboard(String currentAccountName) {
        LocalDate today = LocalDate.now(VN_ZONE);
        LocalDate yesterday = today.minusDays(1);

        List<Invoice> invoices = invoiceRepository.findAllWithRelations();
        List<Product> products = productRepository.findAllWithRelations();
        List<Purchaseinvoice> purchaseInvoices = purchaseinvoiceRepository.findAllWithRelations();

        BigDecimal todayRevenue = sumInvoiceTotal(invoices, invoice -> isDate(invoice.getDate(), today));
        BigDecimal yesterdayRevenue = sumInvoiceTotal(invoices, invoice -> isDate(invoice.getDate(), yesterday));
        long todayInvoiceCount = invoices.stream().filter(invoice -> isDate(invoice.getDate(), today)).count();

        BigDecimal customerDebt = invoices.stream()
                .map(Invoice::getDebtAmount)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal purchaseDebt = purchaseInvoices.stream()
                .map(this::calculatePurchaseDebt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long pendingApprovals = approvalService.getStats().getTotalCount();
        long monthlyVatInvoices = invoices.stream()
                .filter(invoice -> isCurrentMonth(invoice.getDate(), today))
                .filter(invoice -> safe(invoice.getTotalVATOutput()).compareTo(BigDecimal.ZERO) > 0)
                .count();

        long lowStockProducts = countLowStockProducts(products);
        List<ApprovalItemResponse> pendingApprovalItems = approvalService.list(null).stream()
                .filter(ApprovalItemResponse::isPending)
                .limit(5)
                .toList();

        return new RoleDashboard(
                "OWNER",
                "Tổng quan",
                currentAccountName,
                "Tổng quan vận hành nhà thuốc hôm nay • Ngày " + today.format(DATE_DISPLAY),
                List.of(
                        new QuickAction("Xem phê duyệt", "/owner/approvals", true),
                        new QuickAction("Xem công nợ", "/owner/debts", false),
                        new QuickAction("Xem tồn kho", "/owner/stock-counts", false),
                        new QuickAction("Xem hóa đơn VAT", "/owner/invoices", false)
                ),
                List.of(
                        new MetricCard(
                                "Doanh thu hôm nay",
                                moneyShort(todayRevenue),
                                compareRevenue(todayRevenue, yesterdayRevenue) + " so với hôm qua",
                                "ti ti-trending-up",
                                "success",
                                "/owner/invoices"
                        ),
                        new MetricCard(
                                "Hóa đơn hôm nay",
                                String.valueOf(todayInvoiceCount),
                                "Giao dịch bán hàng trong ngày",
                                "ti ti-file-invoice",
                                "info",
                                "/owner/invoices"
                        ),
                        new MetricCard(
                                "Công nợ còn lại",
                                moneyShort(customerDebt.add(purchaseDebt)),
                                "Cần theo dõi thu/chi",
                                "ti ti-currency-dollar",
                                "warning",
                                "/owner/debts"
                        ),
                        new MetricCard(
                                "Yêu cầu chờ phê duyệt",
                                String.valueOf(pendingApprovals),
                                "Báo cáo, phiếu chi, xuất kho",
                                "ti ti-clipboard-check",
                                "orange",
                                "/owner/approvals"
                        ),
                        new MetricCard(
                                "Hóa đơn VAT tháng này",
                                String.valueOf(monthlyVatInvoices),
                                "Có phát sinh VAT đầu ra",
                                "ti ti-receipt-tax",
                                "success",
                                "/owner/invoices"
                        ),
                        new MetricCard(
                                "Sản phẩm sắp hết tồn",
                                String.valueOf(lowStockProducts),
                                "Cần nhập bổ sung",
                                "ti ti-alert-triangle",
                                "danger",
                                "/owner/products"
                        )
                ),
                "Doanh thu 7 ngày gần nhất",
                "line",
                lastSevenDayLabels(today),
                lastSevenDayRevenue(invoices, today),
                "Việc cần xử lý",
                ownerTodoItems(pendingApprovalItems),
                "Hóa đơn gần đây",
                recentInvoices(invoices, null, "/owner/invoices"),
                "Hiệu suất nhân viên hôm nay",
                ownerPerformanceRows(invoices, today),
                "Phê duyệt gần đây",
                pendingApprovalItems
        );
    }

    @Transactional(readOnly = true)
    public RoleDashboard pharmacistDashboard(Integer accountId, String currentAccountName) {
        LocalDate today = LocalDate.now(VN_ZONE);

        List<Invoice> invoices = invoiceRepository.findAllWithRelations();
        List<Invoicedetail> invoiceDetails = invoicedetailRepository.findAll();
        List<Customer> customers = customerRepository.findAll();
        List<Product> products = productRepository.findAllWithRelations();
        List<Shiftreport> shiftReports = shiftreportRepository.findAllWithRelations();
        List<Stockcount> stockCounts = stockcountRepository.findAllWithRelations();
        List<Return> returns = returnRepository.findAllWithRelations();

        Predicate<Invoice> ownedByCurrentUser = invoice -> sameAccount(invoice.getEmployeeID(), accountId);
        List<Invoice> myInvoices = invoices.stream()
                .filter(ownedByCurrentUser)
                .toList();

        BigDecimal todayRevenue = sumInvoiceTotal(myInvoices, invoice -> isDate(invoice.getDate(), today));
        long todayInvoiceCount = myInvoices.stream()
                .filter(invoice -> isDate(invoice.getDate(), today))
                .count();

        BigDecimal debtFromMyInvoices = myInvoices.stream()
                .map(Invoice::getDebtAmount)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String shiftStatus = latestShiftStatus(shiftReports, accountId);
        String topProductName = topSellingProductName(invoiceDetails, accountId, today);
        long needCheckProducts = countLowStockProducts(products);

        return new RoleDashboard(
                "PHARMACIST",
                "Tổng quan",
                currentAccountName,
                "Tổng quan hoạt động tư vấn và bán hàng tại Nhà thuốc Hằng Ngọc hôm nay • "
                        + today.format(DATE_DISPLAY),
                List.of(
                        new QuickAction("Bán hàng", "/pharmacist/selling", true),
                        new QuickAction("Tạo khách hàng", "/customer/create", false),
                        new QuickAction("Xem hóa đơn", "/pharmacist/invoices", false),
                        new QuickAction("Xem hàng hóa", "/pharmacist/products", false),
                        new QuickAction("Tạo báo cáo ca", "/pharmacist/shift-reports", false)
                ),
                List.of(
                        new MetricCard(
                                "Doanh thu hôm nay",
                                money(todayRevenue),
                                "Doanh thu của bạn trong ngày",
                                "ti ti-shield-check",
                                "success",
                                "/pharmacist/invoices"
                        ),
                        new MetricCard(
                                "Hóa đơn hôm nay",
                                String.valueOf(todayInvoiceCount),
                                "Số hóa đơn đã tạo",
                                "ti ti-file-invoice",
                                "info",
                                "/pharmacist/invoices"
                        ),
                        new MetricCard(
                                "Khách hàng hiện có",
                                String.valueOf(customers.size()),
                                "Có thể tạo khách hàng mới",
                                "ti ti-users",
                                "success",
                                "/customer"
                        ),
                        new MetricCard(
                                "Báo cáo ca hiện tại",
                                shiftStatus,
                                "Cần gửi trước khi kết ca",
                                "ti ti-report",
                                "orange",
                                "/pharmacist/shift-reports"
                        ),
                        new MetricCard(
                                "Sản phẩm bán nhiều",
                                topProductName,
                                "Trong ca hôm nay",
                                "ti ti-activity",
                                "success",
                                "/pharmacist/products"
                        ),
                        new MetricCard(
                                "Cần kiểm tra tồn",
                                String.valueOf(needCheckProducts),
                                "Sản phẩm dưới mức tồn tối thiểu",
                                "ti ti-alert-triangle",
                                "danger",
                                "/pharmacist/stock-counts"
                        )
                ),
                "Doanh thu theo giờ trong ngày",
                "bar",
                workingHourLabels(),
                hourlyRevenue(myInvoices),
                "Việc cần xử lý",
                pharmacistTodoItems(shiftReports, myInvoices, stockCounts, returns, accountId),
                "Hóa đơn gần đây",
                recentInvoices(myInvoices, accountId, "/pharmacist/invoices"),
                "",
                List.of(),
                "",
                List.of()
        );
    }

    private List<TodoItem> ownerTodoItems(List<ApprovalItemResponse> approvalItems) {
        if (approvalItems.isEmpty()) {
            return List.of(new TodoItem(
                    "Không có yêu cầu chờ duyệt",
                    "Hiện chưa có phiếu nào cần xử lý",
                    "Ổn định",
                    "success",
                    "/owner/approvals"
            ));
        }

        return approvalItems.stream()
                .map(item -> new TodoItem(
                        item.getType() + " " + item.getCode(),
                        item.getRequesterName() + " • " + item.getSummary(),
                        item.getStatus(),
                        "warning",
                        item.getDetailUrl()
                ))
                .toList();
    }

    private List<TodoItem> pharmacistTodoItems(List<Shiftreport> shifts,
                                               List<Invoice> myInvoices,
                                               List<Stockcount> stockCounts,
                                               List<Return> returns,
                                               Integer accountId) {
        List<TodoItem> items = new ArrayList<>();

        shifts.stream()
                .filter(shift -> sameAccount(shift.getCashierID(), accountId))
                .filter(shift -> isStatus(shift.getStatus(), ShiftReportStatus.DRAFT)
                        || isStatus(shift.getStatus(), ShiftReportStatus.REJECTED))
                .sorted(Comparator.comparing(Shiftreport::getStartTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(2)
                .forEach(shift -> items.add(new TodoItem(
                        "Báo cáo ca chưa gửi",
                        shift.getShiftReportCode() + " • " + formatInstant(shift.getStartTime()),
                        shift.getStatus(),
                        "warning",
                        "/pharmacist/shift-reports/" + shift.getId()
                )));

        myInvoices.stream()
                .filter(invoice -> safe(invoice.getDebtAmount()).compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(Invoice::getDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(2)
                .forEach(invoice -> items.add(new TodoItem(
                        "Hóa đơn còn nợ",
                        invoice.getInvoiceNumber() + " • " + money(invoice.getDebtAmount()),
                        "Còn nợ",
                        "orange",
                        "/pharmacist/invoices/" + invoice.getId()
                )));

        stockCounts.stream()
                .filter(count -> sameAccount(count.getCreatedBy(), accountId))
                .filter(count -> isStatus(count.getStatus(), StockCountStatus.DRAFT)
                        || isStatus(count.getStatus(), StockCountStatus.REJECTED))
                .sorted(Comparator.comparing(Stockcount::getCountDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(2)
                .forEach(count -> items.add(new TodoItem(
                        "Phiếu kiểm kê cần xử lý",
                        count.getStockCountCode() + " • " + formatInstant(count.getCountDate()),
                        count.getStatus(),
                        "warning",
                        "/pharmacist/stock-counts/" + count.getId()
                )));

        returns.stream()
                .filter(ret -> sameAccount(ret.getReturnedBy(), accountId))
                .filter(ret -> isStatus(ret.getStatus(), ReturnStatus.DRAFT)
                        || isStatus(ret.getStatus(), ReturnStatus.REJECTED))
                .sorted(Comparator.comparing(Return::getReturnDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(2)
                .forEach(ret -> items.add(new TodoItem(
                        "Phiếu trả hàng cần xử lý",
                        ret.getReturnCode() + " • " + formatInstant(ret.getReturnDate()),
                        ret.getStatus(),
                        "danger",
                        "/pharmacist/returns/" + ret.getId()
                )));

        if (items.isEmpty()) {
            items.add(new TodoItem(
                    "Không có việc cần xử lý",
                    "Bạn chưa có phiếu nháp hoặc công việc tồn đọng",
                    "Ổn định",
                    "success",
                    "/pharmacist/dashboard"
            ));
        }

        return items.stream().limit(5).toList();
    }

    private List<RecentInvoice> recentInvoices(List<Invoice> invoices, Integer accountId, String basePath) {
        return invoices.stream()
                .filter(invoice -> accountId == null || sameAccount(invoice.getEmployeeID(), accountId))
                .sorted(Comparator.comparing(Invoice::getDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(invoice -> new RecentInvoice(
                        invoice.getInvoiceNumber(),
                        invoice.getDate() == null ? "-" : invoice.getDate().format(TIME_DISPLAY),
                        invoice.getCustomerID() == null ? "Khách lẻ" : invoice.getCustomerID().getName(),
                        money(invoice.getTotal()),
                        invoice.getStatus(),
                        invoiceTone(invoice.getStatus()),
                        basePath + "/" + invoice.getId()
                ))
                .toList();
    }

    private List<PerformanceRow> ownerPerformanceRows(List<Invoice> invoices, LocalDate today) {
        Map<Integer, List<Invoice>> byEmployee = invoices.stream()
                .filter(invoice -> invoice.getEmployeeID() != null)
                .filter(invoice -> isDate(invoice.getDate(), today))
                .collect(Collectors.groupingBy(invoice -> invoice.getEmployeeID().getId()));

        return byEmployee.values().stream()
                .sorted(Comparator.comparing((List<Invoice> employeeInvoices) ->
                        employeeInvoices.stream()
                                .map(Invoice::getTotal)
                                .map(this::safe)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                ).reversed())
                .limit(5)
                .map(employeeInvoices -> {
                    Account employee = employeeInvoices.get(0).getEmployeeID();

                    BigDecimal revenue = employeeInvoices.stream()
                            .map(Invoice::getTotal)
                            .map(this::safe)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    BigDecimal debt = employeeInvoices.stream()
                            .map(Invoice::getDebtAmount)
                            .map(this::safe)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    String status = debt.compareTo(BigDecimal.ZERO) > 0 ? "Cần theo dõi" : "Tốt";
                    String tone = debt.compareTo(BigDecimal.ZERO) > 0 ? "warning" : "success";

                    return new PerformanceRow(
                            employee.getName(),
                            money(revenue),
                            employeeInvoices.size(),
                            money(debt),
                            status,
                            tone
                    );
                })
                .toList();
    }

    private List<String> lastSevenDayLabels(LocalDate today) {
        return IntStream.rangeClosed(0, 6)
                .mapToObj(offset -> today.minusDays(6L - offset))
                .map(this::vnDayLabel)
                .toList();
    }

    private List<BigDecimal> lastSevenDayRevenue(List<Invoice> invoices, LocalDate today) {
        return IntStream.rangeClosed(0, 6)
                .mapToObj(offset -> today.minusDays(6L - offset))
                .map(date -> sumInvoiceTotal(invoices, invoice -> isDate(invoice.getDate(), date)))
                .toList();
    }

    private String vnDayLabel(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case MONDAY -> "Thứ 2";
            case TUESDAY -> "Thứ 3";
            case WEDNESDAY -> "Thứ 4";
            case THURSDAY -> "Thứ 5";
            case FRIDAY -> "Thứ 6";
            case SATURDAY -> "Thứ 7";
            case SUNDAY -> "Chủ Nhật";
        };
    }

    private List<String> workingHourLabels() {
        return IntStream.rangeClosed(7, 16)
                .mapToObj(hour -> String.format("%02d:00", hour))
                .toList();
    }

    private List<BigDecimal> hourlyRevenue(List<Invoice> invoices) {
        LocalDate today = LocalDate.now(VN_ZONE);

        Map<Integer, BigDecimal> revenueByHour = new HashMap<>();
        invoices.stream()
                .filter(invoice -> isDate(invoice.getDate(), today))
                .forEach(invoice -> {
                    int hour = invoice.getDate().getHour();
                    revenueByHour.merge(hour, safe(invoice.getTotal()), BigDecimal::add);
                });

        return IntStream.rangeClosed(7, 16)
                .mapToObj(hour -> revenueByHour.getOrDefault(hour, BigDecimal.ZERO))
                .toList();
    }

    private String topSellingProductName(List<Invoicedetail> details, Integer accountId, LocalDate today) {
        Map<String, Integer> quantityByProduct = new HashMap<>();

        for (Invoicedetail detail : details) {
            Invoice invoice = detail.getInvoiceID();
            Product product = detail.getProductID();

            if (invoice == null || product == null) {
                continue;
            }

            if (!sameAccount(invoice.getEmployeeID(), accountId) || !isDate(invoice.getDate(), today)) {
                continue;
            }

            quantityByProduct.merge(product.getName(), safeInt(detail.getQuantity()), Integer::sum);
        }

        return quantityByProduct.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Chưa có");
    }

    private long countLowStockProducts(List<Product> products) {
        Map<Integer, Long> stockByProduct = batchRepository.sumStorageGroupedByProduct().stream()
                .filter(row -> row[0] != null)
                .collect(Collectors.toMap(
                        row -> ((Number) row[0]).intValue(),
                        row -> row[1] == null ? 0L : ((Number) row[1]).longValue()
                ));

        return products.stream()
                .filter(product -> Boolean.TRUE.equals(product.getStatus()))
                .filter(product -> safeInt(product.getMinStock()) > 0)
                .filter(product -> stockByProduct.getOrDefault(product.getProductID(), 0L) <= safeInt(product.getMinStock()))
                .count();
    }

    private BigDecimal calculatePurchaseDebt(Purchaseinvoice invoice) {
        BigDecimal total = safe(invoice.getTotalAmount());
        BigDecimal paid = safe(invoice.getPaid());
        BigDecimal debt = total.subtract(paid);
        return debt.compareTo(BigDecimal.ZERO) > 0 ? debt : BigDecimal.ZERO;
    }

    private BigDecimal sumInvoiceTotal(List<Invoice> invoices, Predicate<Invoice> filter) {
        return invoices.stream()
                .filter(filter)
                .map(Invoice::getTotal)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isDate(LocalDateTime dateTime, LocalDate date) {
        return dateTime != null && dateTime.toLocalDate().equals(date);
    }

    private boolean isCurrentMonth(LocalDateTime dateTime, LocalDate today) {
        return dateTime != null
                && dateTime.getYear() == today.getYear()
                && dateTime.getMonth() == today.getMonth();
    }

    private boolean sameAccount(Account account, Integer accountId) {
        return account != null && accountId != null && Objects.equals(account.getId(), accountId);
    }

    private boolean isStatus(String actual, String expected) {
        return actual != null && actual.trim().equalsIgnoreCase(expected);
    }

    private String latestShiftStatus(List<Shiftreport> shifts, Integer accountId) {
        return shifts.stream()
                .filter(shift -> sameAccount(shift.getCashierID(), accountId))
                .sorted(Comparator.comparing(Shiftreport::getStartTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(Shiftreport::getStatus)
                .findFirst()
                .orElse("Chưa gửi");
    }

    private String invoiceTone(String status) {
        if (status == null) {
            return "secondary";
        }

        if (status.contains("Hoàn thành") || status.contains("Đã kí")) {
            return "success";
        }

        if (status.contains("nợ") || status.contains("Nợ")) {
            return "warning";
        }

        if (status.contains("trả hàng")) {
            return "orange";
        }

        return "secondary";
    }

    private String compareRevenue(BigDecimal today, BigDecimal yesterday) {
        BigDecimal safeToday = safe(today);
        BigDecimal safeYesterday = safe(yesterday);

        if (safeYesterday.compareTo(BigDecimal.ZERO) == 0) {
            return safeToday.compareTo(BigDecimal.ZERO) > 0 ? "+100%" : "0%";
        }

        BigDecimal percent = safeToday.subtract(safeYesterday)
                .multiply(BigDecimal.valueOf(100))
                .divide(safeYesterday, 0, RoundingMode.HALF_UP);

        return (percent.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + percent + "%";
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "-";
        }
        return DATE_TIME_DISPLAY.format(instant.atZone(VN_ZONE));
    }

    private String money(BigDecimal value) {
        NumberFormat formatter = NumberFormat.getNumberInstance(new Locale("vi", "VN"));
        formatter.setMaximumFractionDigits(0);
        return formatter.format(safe(value)) + "đ";
    }

    private String moneyShort(BigDecimal value) {
        BigDecimal safeValue = safe(value);
        BigDecimal abs = safeValue.abs();

        if (abs.compareTo(BigDecimal.valueOf(1_000_000)) >= 0) {
            BigDecimal million = safeValue.divide(BigDecimal.valueOf(1_000_000), 1, RoundingMode.HALF_UP);
            return million.stripTrailingZeros().toPlainString() + "Mđ";
        }

        return money(safeValue);
    }
}