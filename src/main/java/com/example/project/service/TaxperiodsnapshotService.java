package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ExpenseType;
import com.example.project.constant.ReturnPurchaseStatus;
import com.example.project.constant.ReturnStatus;
import com.example.project.constant.RoleConstants;
import com.example.project.constant.StockAdjustmentStatus;
import com.example.project.constant.TaxRevenueGroup;
import com.example.project.dto.request.TaxPeriodCloseRequest;
import com.example.project.dto.request.TaxPeriodUpdateRequest;
import com.example.project.dto.response.IncomeTypeOptionResponse;
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
import com.example.project.repository.IncomeRepository;
import com.example.project.repository.InvoiceRepository;
import com.example.project.repository.InvoicedetailRepository;
import com.example.project.repository.PurchaseinvoiceRepository;
import com.example.project.repository.ReturnRepository;
import com.example.project.repository.ReturndetailRepository;
import com.example.project.repository.StockadjustmentdetailRepository;
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
 * Kỳ thuế ("kỳ thuế") — các kỳ khai thuế GTGT/TNCN của nhà thuốc và snapshot ghi lại khi chốt kỳ.
 *
 * <p><strong>Một kỳ luôn là một quý.</strong> Nhóm 4 (khai theo tháng) không thuộc phạm vi hệ thống,
 * nên kỳ luôn là quý. Kỳ Nhóm 1 vẫn được tạo và chốt để giữ chuỗi kỳ liên tục, dù không kê khai gì.</p>
 *
 * <p><strong>Mọi thứ dựa trên một chuỗi kỳ, vì mỗi dòng không lưu nhóm của chính nó.</strong>
 * {@code Taxperiodsnapshot} chỉ có {@code periodTaxType} — nhóm áp dụng cho kỳ SAU nó:</p>
 * <pre>
 *   nhóm(kỳ N)                = periodTaxType(kỳ N-1)
 *   vatCarryforwardIn(N)      = vatCarryforwardOut(N-1)
 *   nhóm(kỳ đầu tiên)         = Financialsetting.revenueGroup   &lt;- điểm khởi đầu duy nhất
 * </pre>
 * <p>Vì vậy việc chốt kỳ phải theo đúng thứ tự, và một kỳ đã chốt bị khóa (chỉ kỳ mới nhất được sửa).</p>
 *
 * <p><strong>Nhóm không bao giờ tự suy ra từ doanh thu ngay lập tức</strong> — vượt ngưỡng chỉ cảnh
 * báo, {@code periodTaxType} mặc định giữ nguyên nhóm hiện tại cho tới khi có người/logic tự động
 * đổi (xem {@link #autoNextGroup}).</p>
 *
 * <p><strong>Ba kiểu lưu ngày, một kỳ.</strong> {@code Invoice.date} là {@code LocalDateTime} giờ
 * Việt Nam, còn {@code Purchaseinvoice.date}/{@code Return.returnDate} là {@code Instant} thật — nên
 * ranh giới quý phải biểu diễn hai kiểu (xem {@link #localStart}/{@link #instantStart}...). Cả hai
 * đều là khoảng nửa mở {@code [start, end)}.</p>
 */
@Service
public class TaxperiodsnapshotService {

    /** Mọi mốc thời gian trong service này đều theo giờ Việt Nam, không dùng zone mặc định của server. */
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    /** Loại chi phí được trừ vào thu nhập chịu thuế — chỉ chi phí vận hành (chi phí gắn phiếu nhập đã tính vào giá vốn). */
    private static final List<String> DEDUCTIBLE_EXPENSE_TYPES = List.of(ExpenseType.OPERATIONAL);

    /** Trạng thái Expense mà tiền đã thực chi. */
    private static final List<String> DISBURSED_EXPENSE_STATUSES =
            List.of(ExpenseStatus.AWAITING_PAYMENT, ExpenseStatus.COMPLETED);

    /** Loại điều chỉnh kho khiến hàng ra khỏi kho mà không phải bán (biếu tặng/dùng nội bộ/hàng mẫu) — tính là doanh thu. */
    private static final List<String> GIVEN_AWAY_ADJUSTMENT_TYPES = List.of("INTERNAL_USE", "GIFT", "SAMPLE");

    /** Trạng thái Income coi là đã thu tiền thật — gồm nhãn hiện tại và nhãn cũ trước khi đổi tên. */
    private static final List<String> INCOME_COMPLETED_STATUSES = List.of("Hoàn thành", "Duyệt");

    private final TaxperiodsnapshotRepository taxperiodsnapshotRepository;
    private final InvoiceRepository invoiceRepository;
    private final ReturnRepository returnRepository;
    private final PurchaseinvoiceRepository purchaseinvoiceRepository;
    private final FinancialsettingRepository financialsettingRepository;
    private final AccountpermissionRepository accountpermissionRepository;
    private final InvoicedetailRepository invoicedetailRepository;
    private final IncomeRepository incomeRepository;
    private final StockadjustmentdetailRepository stockadjustmentdetailRepository;
    private final ExpenseRepository expenseRepository;
    private final PurchaseinvoiceService purchaseinvoiceService;
    private final ReturndetailRepository returndetailRepository;

    public TaxperiodsnapshotService(TaxperiodsnapshotRepository taxperiodsnapshotRepository,
                                    InvoiceRepository invoiceRepository,
                                    ReturnRepository returnRepository,
                                    PurchaseinvoiceRepository purchaseinvoiceRepository,
                                    FinancialsettingRepository financialsettingRepository,
                                    AccountpermissionRepository accountpermissionRepository,
                                    InvoicedetailRepository invoicedetailRepository,
                                    IncomeRepository incomeRepository,
                                    StockadjustmentdetailRepository stockadjustmentdetailRepository,
                                    ExpenseRepository expenseRepository,
                                    PurchaseinvoiceService purchaseinvoiceService,
                                    ReturndetailRepository returndetailRepository) {
        this.taxperiodsnapshotRepository = taxperiodsnapshotRepository;
        this.invoiceRepository = invoiceRepository;
        this.returnRepository = returnRepository;
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.financialsettingRepository = financialsettingRepository;
        this.accountpermissionRepository = accountpermissionRepository;
        this.invoicedetailRepository = invoicedetailRepository;
        this.incomeRepository = incomeRepository;
        this.stockadjustmentdetailRepository = stockadjustmentdetailRepository;
        this.expenseRepository = expenseRepository;
        this.purchaseinvoiceService = purchaseinvoiceService;
        this.returndetailRepository = returndetailRepository;
    }

    // ------------------------------------------------------------------ định nghĩa kỳ thuế

    /** Một kỳ khai thuế. {@code label} là khóa nghiệp vụ lưu ở {@code Taxperiodsnapshot.periodLabel}, dạng {@code "2026-Q3"}. */
    public record TaxPeriod(String label, LocalDate startDate, LocalDate endDate) {

        // Kiểm tra một ngày có nằm trong khoảng [startDate, endDate] của kỳ này không.
        public boolean contains(LocalDate date) {
            return date != null && !date.isBefore(startDate) && !date.isAfter(endDate);
        }
    }

    /** Quý chứa một ngày cho trước. */
    public static TaxPeriod quarterOf(LocalDate date) {
        int quarter = (date.getMonthValue() - 1) / 3 + 1;
        return quarter(date.getYear(), quarter);
    }

    /** Quý {@code 1..4} của {@code year}, kèm nhãn và khoảng ngày. */
    public static TaxPeriod quarter(int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new IllegalArgumentException("Quý phải nằm trong khoảng 1–4");
        }
        LocalDate start = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
        LocalDate end = start.plusMonths(3).minusDays(1);
        return new TaxPeriod(year + "-Q" + quarter, start, end);
    }

    /** Quý liền sau quý kết thúc tại {@code endDate}. */
    public static TaxPeriod quarterAfter(LocalDate endDate) {
        return quarterOf(endDate.plusDays(1));
    }

    /** Quý hiện tại, theo giờ Việt Nam. */
    public TaxPeriod currentQuarter() {
        return quarterOf(LocalDate.now(VN_ZONE));
    }

    /** Kỳ cần chốt tiếp theo: kỳ liền sau kỳ đã chốt gần nhất, hoặc quý hiện tại nếu chưa từng chốt kỳ nào. */
    @Transactional(readOnly = true)
    public TaxPeriod nextPeriodToClose() {
        return taxperiodsnapshotRepository.findFirstByOrderByStartDateDescIdDesc()
                .map(Taxperiodsnapshot::getEndDate)
                .map(TaxperiodsnapshotService::quarterAfter)
                .orElseGet(this::currentQuarter);
    }

    // ------------------------------------------------------------------ ranh giới kỳ

    /** Mốc dưới (bao gồm) cho cột kiểu {@code LocalDateTime} ({@code Invoice.date}). */
    static LocalDateTime localStart(TaxPeriod period) {
        return period.startDate().atStartOfDay();
    }

    /** Mốc trên (không bao gồm): 0h ngày kế tiếp sau ngày cuối kỳ. */
    static LocalDateTime localEndExclusive(TaxPeriod period) {
        return period.endDate().plusDays(1).atStartOfDay();
    }

    /** Mốc dưới (bao gồm) cho cột kiểu {@code Instant}, tại 0h giờ Việt Nam. */
    static Instant instantStart(TaxPeriod period) {
        return period.startDate().atStartOfDay(VN_ZONE).toInstant();
    }

    /** Mốc trên (không bao gồm) cho cột kiểu {@code Instant}, tại 0h giờ Việt Nam. */
    static Instant instantEndExclusive(TaxPeriod period) {
        return period.endDate().plusDays(1).atStartOfDay(VN_ZONE).toInstant();
    }

    // ------------------------------------------------------------------ chuỗi kỳ

    /** Kỳ đã chốt liền trước {@code period} — nguồn cung cấp nhóm doanh thu và số khấu trừ chuyển tiếp. */
    @Transactional(readOnly = true)
    public Optional<Taxperiodsnapshot> previousSnapshot(TaxPeriod period) {
        return taxperiodsnapshotRepository.findAllOldestFirst().stream()
                .filter(snapshot -> snapshot.getEndDate() != null)
                .filter(snapshot -> snapshot.getEndDate().isBefore(period.startDate()))
                .max(Comparator.comparing(Taxperiodsnapshot::getEndDate));
    }

    /** Nhóm doanh thu áp dụng cho một kỳ: lấy từ kỳ liền trước, mặc định theo Financialsetting nếu là kỳ đầu tiên. */
    @Transactional(readOnly = true)
    public Integer groupForPeriod(TaxPeriod period) {
        return previousSnapshot(period)
                .map(Taxperiodsnapshot::getPeriodTaxType)
                .orElseGet(this::settingRevenueGroup);
    }

    /** Nhóm doanh thu hiện tại của nhà thuốc, tức nhóm của quý hiện tại. */
    @Transactional(readOnly = true)
    public Integer currentRevenueGroup() {
        return groupForPeriod(currentQuarter());
    }

    /** Nhóm đã cấu hình, mặc định {@link TaxRevenueGroup#DIRECT} nếu chưa có Financial Setting nào. */
    private Integer settingRevenueGroup() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(Financialsetting::getRevenueGroup)
                .orElse(TaxRevenueGroup.DIRECT);
    }

    // ------------------------------------------------------------------ tự động chuyển nhóm

    /**
     * Nhóm áp dụng sau {@code period}, dựa trên nhóm kỳ đó đang chịu thuế.
     *
     * <p><strong>1→2 áp dụng ngay</strong>: quý vượt ngưỡng 1 đã phải tính thuế theo Nhóm 2, bất kể
     * là quý nào trong năm.</p>
     *
     * <p><strong>2→3 giữ nguyên đến hết năm</strong>: vượt ngưỡng 2 giữa năm vẫn tính Nhóm 2 đến hết
     * năm đó, Nhóm 3 chỉ bắt đầu từ tháng 1 năm sau — nên chỉ trả về {@link TaxRevenueGroup#DEDUCTION}
     * khi {@code period} là quý cuối năm (tháng 12).</p>
     */
    private Integer autoNextGroup(TaxPeriod period, Integer groupOfPeriod) {
        int year = period.startDate().getYear();

        if (Integer.valueOf(TaxRevenueGroup.EXEMPT).equals(groupOfPeriod)) {
            if (revenueForYear(year).compareTo(TaxRevenueGroup.THRESHOLD_1) >= 0) {
                return TaxRevenueGroup.DIRECT;
            }
        } else if (Integer.valueOf(TaxRevenueGroup.DIRECT).equals(groupOfPeriod)
                && period.endDate().getMonthValue() == 12) {
            if (revenueForYear(year).compareTo(TaxRevenueGroup.THRESHOLD_2) >= 0) {
                return TaxRevenueGroup.DEDUCTION;
            }
        }
        return groupOfPeriod;
    }

    /** Nhóm mà {@link #autoNextGroup} sẽ quyết định cho {@code period} — để màn xem trước hiển thị. */
    @Transactional(readOnly = true)
    public Integer previewAutoNextGroup(TaxPeriod period) {
        return autoNextGroup(period, groupForPeriod(period));
    }

    /** Kết quả của {@link #applyAutomaticGroupTransition()} — có thay đổi không, và đổi thành nhóm nào. */
    public record GroupTransitionResult(boolean changed, Integer fromGroup, Integer toGroup) {
    }

    /**
     * Sửa hồi tố việc chuyển 1→2 ngay khi phát hiện, để quý đang diễn ra được tính thuế theo Nhóm 2
     * trọn vẹn. Việc chuyển 2→3 không cần xử lý ở đây — được quyết định trong
     * {@link #closePeriod}/{@link #updateLatest} khi chốt đúng quý cuối năm.
     *
     * <p>Được gọi mỗi khi mở màn Kỳ thuế hoặc chốt kỳ — không có scheduler nền, nên việc sửa chỉ xảy
     * ra vào lần truy cập tiếp theo.</p>
     *
     * <p>Idempotent: một khi nhóm không còn là {@link TaxRevenueGroup#EXEMPT}, gọi lại không có tác
     * dụng gì — an toàn để gọi mỗi lần tải trang.</p>
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

        // Chưa có kỳ nào từng chốt thì không có gì trên chuỗi để sửa hồi tố — sửa thẳng ở seed
        // (Financialsetting.revenueGroup).
        previousSnapshot(current).ifPresent(snapshot -> {
            snapshot.setPeriodTaxType(TaxRevenueGroup.DIRECT);
            taxperiodsnapshotRepository.save(snapshot);
        });
        syncFinancialSettingRevenueGroup(TaxRevenueGroup.DIRECT);

        return new GroupTransitionResult(true, TaxRevenueGroup.EXEMPT, TaxRevenueGroup.DIRECT);
    }

    /**
     * Đồng bộ {@code Financialsetting.revenueGroup} theo nhóm đang thực sự áp dụng — {@code
     * ReturnPurchaseService} đọc trực tiếp cột này nên cần cập nhật, dù {@link #groupForPeriod} của
     * service này luôn theo chuỗi kỳ chứ không đọc cột đó.
     */
    private void syncFinancialSettingRevenueGroup(Integer group) {
        Financialsetting setting = financialsettingRepository.findFirstByOrderByIdAsc().orElse(null);
        if (setting == null || group.equals(setting.getRevenueGroup())) {
            return;
        }
        setting.setRevenueGroup(group);
        financialsettingRepository.save(setting);
    }

    // ------------------------------------------------------------------ tính toán trực tiếp

    // ------------------------------------------------------------------ doanh thu

    /** Doanh thu giữa hai ngày (bao gồm cả hai đầu) — định nghĩa doanh thu duy nhất, dùng chung cho tính kỳ và cảnh báo vượt ngưỡng theo năm. */
    @Transactional(readOnly = true)
    public BigDecimal revenueBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Khoảng thời gian tính doanh thu không hợp lệ");
        }
        TaxPeriod span = new TaxPeriod(from + " → " + to, from, to);
        List<Invoice> invoices = invoiceRepository.findValidInPeriod(localStart(span), localEndExclusive(span));
        return scaled(revenueOf(invoices, span));
    }

    /** Doanh thu cả năm — dùng so sánh với {@link TaxRevenueGroup#THRESHOLD_1}/{@link TaxRevenueGroup#THRESHOLD_2} (ngưỡng tính theo năm). */
    @Transactional(readOnly = true)
    public BigDecimal revenueForYear(int year) {
        return revenueBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    /**
     * Doanh thu tính thuế GTGT: hóa đơn "còn hiệu lực" (xem {@link
     * InvoiceRepository#findValidInPeriod}) cộng giá trị (đã gồm VAT) của hàng cho đi thay vì bán
     * (biếu tặng/dùng nội bộ/hàng mẫu). Không bao giờ âm.
     */
    private BigDecimal revenueOf(List<Invoice> validInvoices, TaxPeriod period) {
        BigDecimal invoiceRevenue = sum(validInvoices, Invoice::getTotal);
        BigDecimal givenAwayGrossValue = safe(stockadjustmentdetailRepository.sumGrossValueInPeriod(
                GIVEN_AWAY_ADJUSTMENT_TYPES, StockAdjustmentStatus.COMPLETED,
                instantStart(period), instantEndExclusive(period)));
        return invoiceRevenue.add(givenAwayGrossValue).max(BigDecimal.ZERO);
    }

    /**
     * Doanh thu tính thuế TNCN — chỉ dùng cho TNCN, không dùng cho GTGT. Bằng {@link #revenueOf}
     * cộng thêm ba khoản riêng: tiền đền bù của nhân viên (Income {@code EMPLOYEE}), giá vốn hàng
     * thừa kiểm kê không rõ nguồn gốc, và phần nhà thuốc giữ lại khi hoàn tiền khách &lt;100% (xem
     * {@link #customerReturnRetainedOf}).
     */
    private BigDecimal taxableIncomeRevenueOf(BigDecimal revenue, BigDecimal customerReturnRetained,
                                              TaxPeriod period) {
        BigDecimal employeeIncome = safe(incomeRepository.sumByTypeInPeriod(
                IncomeTypeOptionResponse.labelOf(IncomeTypeOptionResponse.EMPLOYEE),
                INCOME_COMPLETED_STATUSES, instantStart(period), instantEndExclusive(period)));
        BigDecimal unknownOriginSurplusCost = safe(stockadjustmentdetailRepository
                .sumUnknownOriginIncreaseCostInPeriod(StockAdjustmentStatus.COMPLETED,
                        instantStart(period), instantEndExclusive(period)));
        return revenue.add(employeeIncome).add(unknownOriginSurplusCost).add(customerReturnRetained);
    }

    /**
     * "Thu nhập phát sinh" từ trả hàng một phần: khi một phiếu trả khách hoàn ở tỷ lệ &lt;100%, phần
     * nhà thuốc KHÔNG hoàn lại ({@code Σ(originalLineValue − lineRefund)}) là một khoản thu nhập
     * thật, tính riêng cho mục đích TNCN.
     *
     * <p>Phải tính trực tiếp từ {@code Returndetail.originalLineValue}/{@code .lineRefund}
     * ({@link ReturndetailRepository#sumCustomerReturnRetainedInPeriod}) — <strong>không được</strong>
     * suy từ {@code Invoice(gốc).total − Invoice(thay thế).total}, vì hiệu đó luôn đúng bằng
     * {@code Return.totalRefund}, không phải phần giữ lại.</p>
     */
    private BigDecimal customerReturnRetainedOf(TaxPeriod period) {
        return safe(returndetailRepository.sumCustomerReturnRetainedInPeriod(
                ReturnStatus.DEBT, ReturnStatus.COMPLETED,
                instantStart(period), instantEndExclusive(period)));
    }

    /** Tính {@link #nextPeriodToClose()} mà không lưu gì. */
    @Transactional(readOnly = true)
    public TaxPeriodComputationResponse computeNextPeriod() {
        return computePeriod(nextPeriodToClose());
    }

    /** Tính một quý được chọn cụ thể, hoặc {@link #nextPeriodToClose()} nếu thiếu tham số — dùng cho màn xem trước. */
    @Transactional(readOnly = true)
    public TaxPeriodComputationResponse computeQuarter(Integer year, Integer quarter) {
        return computePeriod(resolveQuarter(year, quarter));
    }

    /** Cùng logic fallback với {@link #computeQuarter}, tách riêng để caller lấy nhãn cho picker. */
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

    /** Các năm cho picker chọn, mới nhất trước. */
    @Transactional(readOnly = true)
    public List<Integer> selectableYears() {
        int pivot = nextPeriodToClose().startDate().getYear();
        return List.of(pivot + 1, pivot, pivot - 1, pivot - 2);
    }

    /**
     * Tính số liệu một kỳ trực tiếp từ chứng từ, không lưu gì — dùng cho "xem trước" và điền sẵn khi chốt kỳ.
     *
     * <p><strong>GTGT:</strong> cả Nhóm 2 và Nhóm 3 đều nộp {@link TaxRevenueGroup#DIRECT_VAT_RATE}
     * (1%) trên doanh thu, không khấu trừ đầu vào — nên {@code vatInput}/{@code vatCarryforwardIn/Out}
     * luôn bằng 0.</p>
     *
     * <p><strong>TNCN:</strong> Nhóm 3 luôn tính theo lợi nhuận ({@link TaxRevenueGroup#GROUP3_PIT_RATE}
     * = 17% trên thu nhập chịu thuế); Nhóm 1 miễn thuế. Nhóm 2 chọn qua
     * {@code Financialsetting.taxCalculationMethod} giữa Cách 1 (tỷ lệ {@link
     * TaxRevenueGroup#DIRECT_PIT_RATE} = 0,5% trên doanh thu, có trừ ngưỡng) và Cách 2 (theo lợi
     * nhuận, {@link TaxRevenueGroup#DEDUCTION_PIT_RATE} = 15%).</p>
     */
    @Transactional(readOnly = true)
    public TaxPeriodComputationResponse computePeriod(TaxPeriod period) {
        Integer group = groupForPeriod(period);
        boolean exempt = TaxRevenueGroup.isTaxExempt(group);
        // "group == 3" — không còn nghĩa GTGT khấu trừ, chỉ dùng để chọn thuế suất/phương pháp TNCN.
        boolean group3 = TaxRevenueGroup.isDeductionGroup(group);

        Optional<Taxperiodsnapshot> previous = previousSnapshot(period);

        List<Invoice> invoices = invoiceRepository.findValidInPeriod(localStart(period), localEndExclusive(period));
        List<Return> returns = returnRepository.findInPeriod(instantStart(period), instantEndExclusive(period));
        List<Purchaseinvoice> purchases =
                purchaseinvoiceRepository.findInPeriod(instantStart(period), instantEndExclusive(period));

        // Chỉ còn dùng để đếm hiển thị — không còn trừ vào doanh thu hay ảnh hưởng số liệu GTGT nào.
        List<Return> customerReturns = returns.stream()
                .filter(TaxperiodsnapshotService::isApprovedCustomerReturn)
                .toList();
        List<Return> supplierReturns = returns.stream()
                .filter(TaxperiodsnapshotService::isApprovedSupplierReturn)
                .toList();
        List<Purchaseinvoice> deductiblePurchases = purchases.stream()
                .filter(purchaseinvoiceService::isDeductible)
                .toList();

        BigDecimal revenue = exempt ? BigDecimal.ZERO : revenueOf(invoices, period);
        // Tính riêng để hiển thị trên tax-period/preview.html, không tính lại lần hai trong taxableIncomeRevenueOf.
        BigDecimal customerReturnRetained = exempt ? BigDecimal.ZERO : customerReturnRetainedOf(period);
        BigDecimal taxableIncomeRevenue = exempt ? BigDecimal.ZERO
                : taxableIncomeRevenueOf(revenue, customerReturnRetained, period);
        // "Thu nhập khác" trong bảng lợi nhuận — xem TaxPeriodComputationResponse.otherIncome.
        BigDecimal otherIncome = taxableIncomeRevenue.subtract(revenue);
        BigDecimal revenueDeduction = BigDecimal.ZERO;

        // --- GTGT = doanh thu × 1%, áp dụng cả Nhóm 2 và Nhóm 3, không khấu trừ đầu vào.
        BigDecimal vatOutput = exempt ? BigDecimal.ZERO : revenue.multiply(TaxRevenueGroup.DIRECT_VAT_RATE);
        BigDecimal vatOutputFromSales = vatOutput;
        BigDecimal vatOutputReturnDeduction = BigDecimal.ZERO;
        BigDecimal vatInputFromPurchases = BigDecimal.ZERO;
        BigDecimal vatInputReturnReversal = BigDecimal.ZERO;
        BigDecimal vatInput = BigDecimal.ZERO;
        BigDecimal carryIn = BigDecimal.ZERO;
        BigDecimal vatPayable = vatPayable(vatOutput, vatInput, carryIn);
        BigDecimal carryOut = carryForwardOut(vatOutput, vatInput, carryIn);

        // --- TNCN: Nhóm 3 luôn theo lợi nhuận; Nhóm 1 miễn; Nhóm 2 chọn qua taxCalculationMethod.
        boolean pitCostMethod = !exempt && (group3 || Integer.valueOf(2).equals(taxCalculationMethod()));

        BigDecimal costOfGoodsSold = BigDecimal.ZERO;
        BigDecimal operatingCost = BigDecimal.ZERO;
        BigDecimal supplierReturnShortfall = BigDecimal.ZERO;
        BigDecimal taxableIncome = BigDecimal.ZERO;
        BigDecimal incomeTax = BigDecimal.ZERO;
        BigDecimal incomeTaxRate = BigDecimal.ZERO;
        // Lợi nhuận trước/sau thuế — chỉ có ý nghĩa khi tính TNCN theo lợi nhuận.
        BigDecimal profitBeforeTax = BigDecimal.ZERO;
        BigDecimal netProfitAfterTax = BigDecimal.ZERO;

        if (pitCostMethod) {
            costOfGoodsSold = safe(invoicedetailRepository
                    .sumCostOfGoodsSoldInPeriod(localStart(period), localEndExclusive(period)));
            operatingCost = safe(expenseRepository.sumOperatingCostInPeriod(
                    instantStart(period), instantEndExclusive(period),
                    DEDUCTIBLE_EXPENSE_TYPES, DISBURSED_EXPENSE_STATUSES));
            // NCC không hoàn đủ tiền khi trả hàng — một chi phí hợp lý thật sự.
            supplierReturnShortfall = safe(returndetailRepository.sumSupplierReturnShortfallInPeriod(
                    ReturnPurchaseStatus.APPROVED, instantStart(period), instantEndExclusive(period)));
            // Không floor tại 0 — một khoản lỗ thật phải hiện âm, không ẩn thành 0đ.
            profitBeforeTax = taxableIncomeRevenue.subtract(costOfGoodsSold).subtract(operatingCost)
                    .subtract(supplierReturnShortfall);
            // Quý lỗ thì không phải nộp thuế, không tạo ra số thuế âm.
            taxableIncome = profitBeforeTax.max(BigDecimal.ZERO);
            incomeTaxRate = group3 ? TaxRevenueGroup.GROUP3_PIT_RATE : TaxRevenueGroup.DEDUCTION_PIT_RATE;
            incomeTax = taxableIncome.multiply(incomeTaxRate);
            netProfitAfterTax = profitBeforeTax.subtract(incomeTax);
        } else if (!exempt) {
            // TNCN Cách 1 (Nhóm 2, theo doanh thu): trừ ngưỡng 1 trước khi nhân tỷ lệ, floor tại 0.
            incomeTaxRate = TaxRevenueGroup.DIRECT_PIT_RATE;
            BigDecimal taxableRevenueAfterThreshold =
                    taxableIncomeRevenue.subtract(TaxRevenueGroup.THRESHOLD_1).max(BigDecimal.ZERO);
            incomeTax = taxableRevenueAfterThreshold.multiply(incomeTaxRate);
        }

        return new TaxPeriodComputationResponse(
                period.label(),
                DATE.format(period.startDate()),
                DATE.format(period.endDate()),
                group,
                TaxRevenueGroup.label(group),
                group3,
                exempt,
                !exempt,
                scaled(revenue),
                scaled(taxableIncomeRevenue),
                scaled(customerReturnRetained),
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
                scaled(supplierReturnShortfall),
                scaled(taxableIncome),
                scaled(incomeTax),
                percent(incomeTaxRate),
                scaled(revenueDeduction),
                scaled(otherIncome),
                scaled(profitBeforeTax),
                scaled(netProfitAfterTax),
                scaled(vatPayable.add(incomeTax)),
                invoices.size(),
                customerReturns.size(),
                deductiblePurchases.size(),
                supplierReturns.size(),
                purchases.size() - deductiblePurchases.size(),
                previous.map(Taxperiodsnapshot::getPeriodLabel).orElse(null),
                taxperiodsnapshotRepository.existsByPeriodLabel(period.label()),
                pitCostMethod);
    }

    /** {@code Financialsetting.taxCalculationMethod}: 1 = theo doanh thu (Cách 1), 2 = theo lợi nhuận (Cách 2), mặc định 1. */
    private Integer taxCalculationMethod() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(Financialsetting::getTaxCalculationMethod)
                .orElse(1);
    }

    /** Trả hàng khách: có {@code invoiceID}, đã duyệt là {@link ReturnStatus#DEBT} ("Nợ"). */
    private static boolean isApprovedCustomerReturn(Return ret) {
        return ret.getInvoiceID() != null && ReturnStatus.DEBT.equals(ret.getStatus());
    }

    /** Trả hàng NCC: có {@code purchaseID}, không có {@code invoiceID}; đã duyệt là "Đã duyệt". */
    private static boolean isApprovedSupplierReturn(Return ret) {
        return ret.getInvoiceID() == null
                && ret.getPurchaseID() != null
                && ReturnPurchaseStatus.APPROVED.equals(ret.getStatus());
    }

    // ------------------------------------------------------------------ ai được chốt kỳ

    /** Owner và Accountant đều luôn được chốt kỳ. */
    @Transactional(readOnly = true)
    public boolean canClose(String role) {
        return RoleConstants.ACCOUNTANT.equals(role) || RoleConstants.OWNER.equals(role);
    }

    /** Lý do nút chốt kỳ bị vô hiệu hóa, hoặc {@code null} nếu không bị chặn. */
    @Transactional(readOnly = true)
    public String closeBlockedReason(String role, TaxPeriod period) {
        if (!canClose(role)) {
            return "Chỉ Kế toán hoặc Chủ nhà thuốc mới được chốt kỳ thuế.";
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

    // ------------------------------------------------------------------ chốt kỳ

    /**
     * Ghi snapshot cho kỳ đang cần chốt. Số liệu GTGT/TNCN luôn lấy từ {@link #computePeriod}, không
     * bao giờ lấy từ request đã post lên — một bản khai thuế phải suy từ sổ sách, không phải số
     * người dùng gõ. Nhóm áp dụng kỳ sau cũng được suy tự động qua {@link #autoNextGroup}.
     *
     * @return id của snapshot vừa tạo
     */
    @Transactional
    public Integer closePeriod(TaxPeriodCloseRequest request, String actorRole) {
        if (request == null || request.getPeriodLabel() == null || request.getPeriodLabel().isBlank()) {
            throw new IllegalArgumentException("Thiếu thông tin kỳ thuế cần chốt");
        }

        // Sửa hồi tố việc chuyển 1→2 (nếu có) trước khi xác định kỳ cần chốt.
        applyAutomaticGroupTransition();

        TaxPeriod due = nextPeriodToClose();
        if (!due.label().equals(request.getPeriodLabel().trim())) {
            throw new IllegalArgumentException(
                    "Kỳ cần chốt tiếp theo là " + due.label() + ", không phải "
                            + request.getPeriodLabel().trim() + ". Vui lòng tải lại trang.");
        }

        String blocked = closeBlockedReason(actorRole, due);
        if (blocked != null) {
            throw new IllegalArgumentException(blocked);
        }

        // cashBalanceAtPeriodEnd chỉ được validate, không lưu vào đâu — xem ghi chú ở
        // quarterlyRevenue/vatRevenue bên dưới.
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
        snapshot.setIncomeTax(computed.getIncomeTax());
        snapshot.setPeriodTaxType(nextGroup);
        // quarterlyRevenue = "Doanh thu TNCN", vatRevenue = "Doanh thu GTGT" — lấy thẳng từ
        // computePeriod(), không suy ra từ đâu khác.
        snapshot.setQuarterlyRevenue(computed.getTaxableIncomeRevenue());
        snapshot.setVatRevenue(computed.getPeriodRevenue());
        snapshot.setNote(trimToNull(request.getNote()));
        snapshot.setRecordedAt(LocalDateTime.now(VN_ZONE));

        Integer newId = taxperiodsnapshotRepository.save(snapshot).getId();
        syncFinancialSettingRevenueGroup(nextGroup);
        return newId;
    }

    /**
     * Điều chỉnh kỳ mới nhất đã chốt — sửa số sai. Kỳ cũ hơn không sửa được, vì mọi kỳ sau nó đã
     * được xây từ số khấu trừ chuyển tiếp và nhóm của nó.
     *
     * <p>{@code vatCarryforwardOut} luôn được tính lại, không nhận trực tiếp từ request, để một kỳ
     * không thể vừa còn phải nộp vừa còn khấu trừ chuyển tiếp.</p>
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

        // Tính lại giống closePeriod(), không nhận từ request — điều chỉnh cũng không được tự chọn nhóm.
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

        // Kỳ dưới ngưỡng 1 không nợ thuế gì cả — mọi giá trị post lên đều bị bỏ qua, chỉ giữ nhóm và ghi chú.
        if (TaxRevenueGroup.isTaxExempt(storedGroup(snapshot))) {
            vatOutput = BigDecimal.ZERO;
            vatInput = BigDecimal.ZERO;
            carryIn = BigDecimal.ZERO;
            incomeTax = BigDecimal.ZERO;
        }

        // cashBalanceAtPeriodEnd chỉ validate, không lưu — giống closePeriod(). quarterlyRevenue/
        // vatRevenue không nằm trong các trường được sửa ở đây, giữ nguyên như lúc closePeriod() lưu.
        BigDecimal cashBalance = request.getCashBalanceAtPeriodEnd();
        if (cashBalance != null && cashBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Số dư quỹ tiền mặt cuối kỳ không được âm");
        }

        snapshot.setVatOutput(vatOutput);
        snapshot.setIncomeTax(incomeTax);
        snapshot.setPeriodTaxType(nextGroup);
        snapshot.setNote(trimToNull(request.getNote()));

        taxperiodsnapshotRepository.save(snapshot);
        syncFinancialSettingRevenueGroup(nextGroup);
    }

    /** Bỏ trống nghĩa là 0, nhưng số âm luôn là lỗi nhập — một chỉ tiêu khai thuế không thể âm. */
    private static BigDecimal requireNonNegative(BigDecimal value, String label) {
        BigDecimal safe = safe(value);
        if (safe.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(label + " không được âm");
        }
        return scaled(safe);
    }

    // ------------------------------------------------------------------ màn hình đọc

    /** Các kỳ đã chốt, mới nhất trước. */
    @Transactional(readOnly = true)
    public List<TaxPeriodListItemResponse> listPeriods() {
        List<Taxperiodsnapshot> snapshots = taxperiodsnapshotRepository.findAllNewestFirst();
        Integer newestId = snapshots.stream().findFirst().map(Taxperiodsnapshot::getId).orElse(null);

        return snapshots.stream()
                .map(snapshot -> toListItem(snapshot, newestId))
                .toList();
    }

    // Chuyển một snapshot đã chốt thành dòng hiển thị cho màn danh sách.
    private TaxPeriodListItemResponse toListItem(Taxperiodsnapshot snapshot, Integer newestId) {
        BigDecimal vatOutput = safe(snapshot.getVatOutput());
        Integer group = storedGroup(snapshot);

        return new TaxPeriodListItemResponse(
                snapshot.getId(),
                snapshot.getPeriodLabel(),
                formatDate(snapshot.getStartDate()),
                formatDate(snapshot.getEndDate()),
                group,
                TaxRevenueGroup.shortLabel(group),
                scaled(vatPayable(vatOutput, BigDecimal.ZERO, BigDecimal.ZERO)),
                scaled(snapshot.getIncomeTax()),
                formatDateTime(snapshot.getRecordedAt()),
                snapshot.getId() != null && snapshot.getId().equals(newestId));
    }

    /** Một kỳ đã chốt, kèm bối cảnh chuỗi kỳ cần để đọc số liệu. */
    @Transactional(readOnly = true)
    public TaxPeriodDetailResponse getDetail(Integer id) {
        Taxperiodsnapshot snapshot = taxperiodsnapshotRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kỳ thuế"));

        Integer group = storedGroup(snapshot);
        BigDecimal vatOutput = safe(snapshot.getVatOutput());

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
                scaled(vatPayable(vatOutput, BigDecimal.ZERO, BigDecimal.ZERO)),
                scaled(snapshot.getIncomeTax()),
                snapshot.getQuarterlyRevenue() == null
                        ? null
                        : scaled(snapshot.getQuarterlyRevenue()),
                snapshot.getPeriodTaxType(),
                TaxRevenueGroup.label(snapshot.getPeriodTaxType()),
                formatDateTime(snapshot.getRecordedAt()),
                snapshot.getNote(),
                previous.map(Taxperiodsnapshot::getPeriodLabel).orElse(null),
                editable);
    }

    /** Nhóm mà một kỳ đã lưu được khai — không có cột riêng, đọc lại từ chuỗi kỳ liền trước. */
    private Integer storedGroup(Taxperiodsnapshot snapshot) {
        if (snapshot.getStartDate() == null) {
            return settingRevenueGroup();
        }
        return groupForPeriod(new TaxPeriod(
                snapshot.getPeriodLabel(), snapshot.getStartDate(), snapshot.getEndDate()));
    }

    // ------------------------------------------------------------------ hàm phụ trợ

    /** Thuế GTGT thực phải nộp của kỳ, floor tại 0. Hai vế của cùng phép trừ với {@link #carryForwardOut}. */
    private static BigDecimal vatPayable(BigDecimal vatOutput, BigDecimal vatInput, BigDecimal carryIn) {
        return balance(vatOutput, vatInput, carryIn).max(BigDecimal.ZERO);
    }

    /** Số khấu trừ GTGT chuyển sang kỳ sau, floor tại 0. Xem {@link #vatPayable}. */
    private static BigDecimal carryForwardOut(BigDecimal vatOutput, BigDecimal vatInput, BigDecimal carryIn) {
        return balance(vatOutput, vatInput, carryIn).negate().max(BigDecimal.ZERO);
    }

    // Số dư GTGT còn lại = đầu ra − đầu vào − khấu trừ chuyển từ kỳ trước (chưa floor).
    private static BigDecimal balance(BigDecimal vatOutput, BigDecimal vatInput, BigDecimal carryIn) {
        return safe(vatOutput).subtract(safe(vatInput)).subtract(safe(carryIn));
    }

    // Cộng dồn một trường BigDecimal trên danh sách item, coi null là 0.
    private static <T> BigDecimal sum(List<T> items, java.util.function.Function<T, BigDecimal> field) {
        return items.stream()
                .map(field)
                .map(TaxperiodsnapshotService::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Trả về 0 nếu giá trị null, dùng cho mọi phép cộng/trừ tiền trong service này.
    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Tiền trong app dùng 2 chữ số thập phân (trừ Price Settings). */
    private static BigDecimal scaled(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Một tỷ lệ đã lưu ({@code 0.005}) thành số hiển thị trên màn hình ({@code 0.50}). */
    private static BigDecimal percent(BigDecimal rate) {
        return safe(rate).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    // Chuẩn hóa chuỗi ghi chú: bỏ khoảng trắng thừa, chuỗi rỗng thành null.
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // Định dạng ngày dd/MM/yyyy cho hiển thị, null thành "—".
    private static String formatDate(LocalDate date) {
        return date == null ? "—" : DATE.format(date);
    }

    // Định dạng ngày giờ dd/MM/yyyy HH:mm cho hiển thị, null thành "—".
    private static String formatDateTime(LocalDateTime dateTime) {
        return dateTime == null ? "—" : DATE_TIME.format(dateTime);
    }
}
