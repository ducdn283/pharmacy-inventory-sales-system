package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ReturnStatus;
import com.example.project.constant.RoleConstants;
import com.example.project.constant.ShiftReportStatus;
import com.example.project.dto.response.ShiftReportDetailPageResponse;
import com.example.project.dto.response.ShiftReportListItemResponse;
import com.example.project.dto.response.ShiftReportStatsResponse;
import com.example.project.dto.response.IncomeTypeOptionResponse;
import com.example.project.entity.Account;
import com.example.project.entity.Expense;
import com.example.project.entity.Financialsetting;
import com.example.project.entity.Income;
import com.example.project.entity.Invoice;
import com.example.project.entity.Return;
import com.example.project.entity.Shiftreport;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.AccountpermissionRepository;
import com.example.project.repository.ExpenseRepository;
import com.example.project.repository.FinancialsettingRepository;
import com.example.project.repository.IncomeRepository;
import com.example.project.repository.InvoiceRepository;
import com.example.project.repository.ReturnRepository;
import com.example.project.repository.ShiftreportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shift reports are created lazily (see {@link #ensureOpenShiftFor}) at the moment a real
 * transaction happens (a Return is approved, or — once the Sales module hooks it — an Invoice is
 * saved), not at login. Only Owner/Pharmacist accounts get shifts; Accountant never triggers one.
 */
@Service
public class ShiftreportService {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    // Income.status vocabulary lives inline in IncomeService (teammate's module, no constant class
    // yet) — mirror just the two states excluded from register cash: drafts and rejected slips.
    private static final String INCOME_STATUS_DRAFT = "Nháp";
    private static final String INCOME_STATUS_REJECTED = "Từ chối";

    /** Giá trị lọc "thâm hụt quỹ": tiền mặt thực đếm ÍT hơn số dự kiến ({@code cashDiscrepancy < 0}). */
    public static final String DISCREPANCY_SHORTAGE = "SHORTAGE";
    /** Giá trị lọc "thừa quỹ": {@code cashDiscrepancy > 0}. */
    public static final String DISCREPANCY_SURPLUS = "SURPLUS";

    private final ShiftreportRepository shiftreportRepository;
    private final AccountRepository accountRepository;
    private final FinancialsettingRepository financialsettingRepository;
    private final ReturnRepository returnRepository;
    private final InvoiceRepository invoiceRepository;
    private final IncomeRepository incomeRepository;
    private final ExpenseRepository expenseRepository;
    private final AccountpermissionRepository accountpermissionRepository;
    private final WorkflowNotificationService workflowNotificationService;
    private final FinancialsettingService financialsettingService;

    public ShiftreportService(ShiftreportRepository shiftreportRepository,
                              AccountRepository accountRepository,
                              FinancialsettingRepository financialsettingRepository,
                              ReturnRepository returnRepository,
                              InvoiceRepository invoiceRepository,
                              IncomeRepository incomeRepository,
                              ExpenseRepository expenseRepository,
                              AccountpermissionRepository accountpermissionRepository,
                              WorkflowNotificationService workflowNotificationService,
                              FinancialsettingService financialsettingService) {
        this.shiftreportRepository = shiftreportRepository;
        this.accountRepository = accountRepository;
        this.financialsettingRepository = financialsettingRepository;
        this.returnRepository = returnRepository;
        this.invoiceRepository = invoiceRepository;
        this.incomeRepository = incomeRepository;
        this.expenseRepository = expenseRepository;
        this.accountpermissionRepository = accountpermissionRepository;
        this.workflowNotificationService = workflowNotificationService;
        this.financialsettingService = financialsettingService;
    }

    /**
     * Returns the account's currently open (Nháp) shift, creating one if none exists yet.
     * Called at the exact point a transaction (Return, Invoice, ...) is recorded — never from login.
     *
     * <p>Trả {@code null} cho tài khoản không trực quầy — chỉ Owner và Dược sĩ có ca; Kế toán không
     * cầm tiền mặt nên giao dịch của họ không mang {@code shiftReportID}. Guard đặt ở đây vì đây là
     * nơi DUY NHẤT ca được tạo, nên không caller nào lách được bằng cách quên kiểm role (Income của
     * Kế toán từng tạo ca như vậy, làm họ không đăng xuất được).</p>
     */
    @Transactional
    public Shiftreport ensureOpenShiftFor(Integer accountId) {
        // Chặn ở tầng dữ liệu, song song với PendingShiftInterceptor: còn ca ngày trước chưa chốt thì
        // KHÔNG được phát sinh giao dịch mới. Nếu thiếu chốt chặn này, ca cũ sẽ bị dùng lại và giao
        // dịch hôm nay rơi vào ca hôm qua — sai shiftDate lẫn số liệu ca. Interceptor lẽ ra đã chặn từ
        // tầng web nên bình thường không ai chạm tới đây; đây là lớp phòng thủ cho các đường không đi
        // qua điều hướng trang. Ca chạy qua nửa đêm không phải lo: nhà thuốc tắt máy trước 0h, không có
        // báo cáo ngày nhưng chốt ca thì chốt theo ngày.
        Optional<Shiftreport> stale = findStaleDraftShift(accountId);
        if (stale.isPresent()) {
            throw new IllegalArgumentException("Bạn còn báo cáo ca ngày "
                    + formatLocalDate(stale.get().getShiftDate())
                    + " chưa chốt — vui lòng chốt ca đó trước khi phát sinh giao dịch mới");
        }

        Optional<Shiftreport> existingDraft =
                shiftreportRepository.findFirstByCashierID_IdAndStatusOrderByStartTimeDesc(accountId, ShiftReportStatus.DRAFT);
        if (existingDraft.isPresent()) {
            return existingDraft.get();
        }

        if (!runsRegister(accountId)) {
            return null;
        }

        Account cashier = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        Shiftreport shift = new Shiftreport();
        shift.setShiftReportCode(temporaryCode());
        shift.setCashierID(cashier);
        shift.setShiftDate(LocalDate.now(VN_ZONE));
        shift.setShiftType(resolveShiftType());
        shift.setStartTime(nowVn());
        shift.setOpeningCash(resolveOpeningCash());
        shift.setTotalInvoices(0);
        shift.setTotalRevenue(BigDecimal.ZERO);
        shift.setTotalReturns(0);
        shift.setTotalReturnAmount(BigDecimal.ZERO);
        shift.setTotalDebtCollected(BigDecimal.ZERO);
        shift.setTotalCashIn(BigDecimal.ZERO);
        shift.setTotalBankingIn(BigDecimal.ZERO);
        shift.setTotalCashOut(BigDecimal.ZERO);
        shift.setTotalBankingOut(BigDecimal.ZERO);
        shift.setStatus(ShiftReportStatus.DRAFT);
        shift.setCreatedAt(nowVn());

        Shiftreport saved = shiftreportRepository.save(shift);
        // Mã thật = CA- + id do DB cấp, ghi ngay sau INSERT (cùng transaction).
        saved.setShiftReportCode(formatCode(saved.getId()));
        return saved;
    }

    /** Chỉ Owner và Dược sĩ trực quầy (có két) mới có báo cáo ca; Kế toán không. */
    private boolean runsRegister(Integer accountId) {
        return accountpermissionRepository.existsByAccountIdAndRole(accountId, RoleConstants.OWNER)
                || accountpermissionRepository.existsByAccountIdAndRole(accountId, RoleConstants.PHARMACIST);
    }

    /**
     * Ca Nháp của tài khoản còn tồn từ NGÀY TRƯỚC, nếu có.
     *
     * <p>Ca dở dang qua đêm phải chốt trước khi làm gì tiếp: để đó thì {@link #ensureOpenShiftFor}
     * dùng lại ca cũ và mọi giao dịch hôm nay bị dồn vào ca hôm qua — sai cả {@code shiftDate} lẫn số
     * liệu. {@code PendingShiftInterceptor} dùng hàm này để chặn thao tác sau khi đăng nhập.</p>
     *
     * <p>So theo NGÀY: ca mở sáng nay chưa chốt là bình thường (đang trực). Và lấy ca Nháp CŨ NHẤT
     * (khác {@link #findDraftShift}) — nếu lỡ có 2 ca Nháp song song thì nhìn ca mới nhất sẽ bỏ sót
     * đúng ca cần chốt.</p>
     */
    @Transactional(readOnly = true)
    public Optional<Shiftreport> findStaleDraftShift(Integer accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return shiftreportRepository
                .findFirstByCashierID_IdAndStatusOrderByStartTimeAsc(accountId, ShiftReportStatus.DRAFT)
                .filter(shift -> shift.getShiftDate() != null
                        && shift.getShiftDate().isBefore(LocalDate.now(VN_ZONE)));
    }

    /** The Nháp shift of an account, if any — also used by the logout guard. */
    @Transactional(readOnly = true)
    public Optional<Shiftreport> findDraftShift(Integer accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return shiftreportRepository.findFirstByCashierID_IdAndStatusOrderByStartTimeDesc(accountId, ShiftReportStatus.DRAFT);
    }

    /**
     * @param discrepancy lọc theo chênh lệch quỹ: {@link #DISCREPANCY_SHORTAGE} (thâm hụt — tiền thật
     *                    trong két ÍT hơn số dự kiến, tức còn phải thu lại của người trực),
     *                    {@link #DISCREPANCY_SURPLUS} (thừa quỹ), rỗng = không lọc
     */
    @Transactional(readOnly = true)
    public Page<ShiftReportListItemResponse> search(String keyword,
                                                     String fromDate,
                                                     String toDate,
                                                     String status,
                                                     String discrepancy,
                                                     Pageable pageable) {
        String normalizedKeyword = normalize(keyword);
        LocalDate from = parseDate(fromDate);
        LocalDate to = parseDate(toDate);

        List<ShiftReportListItemResponse> rows = shiftreportRepository.findAllWithRelations()
                .stream()
                .filter(shift -> matchesKeyword(shift, normalizedKeyword))
                .filter(shift -> matchesDate(shift, from, to))
                .filter(shift -> status == null || status.isBlank() || isStatus(shift.getStatus(), status))
                .filter(shift -> matchesDiscrepancy(shift, discrepancy))
                .map(this::toListItem)
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), rows.size());

        List<ShiftReportListItemResponse> content = start >= rows.size()
                ? List.of()
                : rows.subList(start, end);

        return new PageImpl<>(content, pageable, rows.size());
    }

    @Transactional(readOnly = true)
    public ShiftReportStatsResponse getStats() {
        List<Shiftreport> shifts = shiftreportRepository.findAllWithRelations();

        return new ShiftReportStatsResponse(
                shifts.size(),
                countByStatus(shifts, ShiftReportStatus.DRAFT),
                countByStatus(shifts, ShiftReportStatus.PENDING),
                countByStatus(shifts, ShiftReportStatus.APPROVED),
                countByStatus(shifts, ShiftReportStatus.REJECTED)
        );
    }

    public List<String> listStatuses() {
        return ShiftReportStatus.ALL;
    }

    @Transactional(readOnly = true)
    public ShiftReportDetailPageResponse getDetail(Integer shiftReportId) {
        Shiftreport shift = shiftreportRepository.findByIdWithRelations(shiftReportId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy báo cáo ca"));

        boolean canClose = isStatus(shift.getStatus(), ShiftReportStatus.DRAFT)
                || isStatus(shift.getStatus(), ShiftReportStatus.REJECTED);

        // A Nháp shift has never gone through closeShift, so its persisted totals are still all-zero —
        // preview them live instead (Chờ duyệt/Đã duyệt/Từ chối already carry the real, closed numbers).
        boolean live = isStatus(shift.getStatus(), ShiftReportStatus.DRAFT);
        TransactionTotals totals = live ? computeTransactionTotals(shift.getId()) : null;

        // Live estimate for the "Đối chiếu tiền mặt" panel while the shift is still open; the value
        // is recomputed (with any openingCash override) and persisted for real at close time.
        BigDecimal expectedClosingCash = live
                ? nz(shift.getOpeningCash()).add(totals.totalCashIn()).subtract(totals.totalCashOut())
                : shift.getExpectedClosingCash();

        // Chuyển khoản ròng — thuần thông tin để người chốt ca tự soát với thông báo ngân hàng.
        // KHÔNG có đối chiếu như tiền mặt: không ai "đếm" được số dư tài khoản lúc giao ca.
        BigDecimal totalBankingIn = live ? totals.totalBankingIn() : shift.getTotalBankingIn();
        BigDecimal totalBankingOut = live ? totals.totalBankingOut() : shift.getTotalBankingOut();
        BigDecimal totalBankingNet = nz(totalBankingIn).subtract(nz(totalBankingOut));

        // Thâm hụt quỹ = phần tiền mặt thật còn thiếu so với số dự kiến, tức khoản phải thu lại của
        // người trực ca. Ca còn Nháp thì chưa đếm tiền nên chưa có gì để thu.
        BigDecimal cashShortage = live ? BigDecimal.ZERO : shortageOf(shift);
        Income shortageIncome = cashShortage.signum() > 0 ? findShortageIncome(shift.getId()) : null;

        return new ShiftReportDetailPageResponse(
                shift.getId(),
                shift.getShiftReportCode(),
                shift.getCashierID() != null ? shift.getCashierID().getId() : null,
                shift.getCashierID() != null ? shift.getCashierID().getName() : "Không rõ",
                formatLocalDate(shift.getShiftDate()),
                shift.getShiftType(),
                formatInstant(shift.getStartTime()),
                formatInstant(shift.getEndTime()),
                shift.getOpeningCash(),
                live ? totals.totalInvoices() : shift.getTotalInvoices(),
                live ? totals.totalRevenue() : shift.getTotalRevenue(),
                live ? totals.totalReturns() : shift.getTotalReturns(),
                live ? totals.totalReturnAmount() : shift.getTotalReturnAmount(),
                live ? totals.totalDebtCollected() : shift.getTotalDebtCollected(),
                live ? totals.totalCashIn() : shift.getTotalCashIn(),
                totalBankingIn,
                live ? totals.totalCashOut() : shift.getTotalCashOut(),
                totalBankingOut,
                totalBankingNet,
                expectedClosingCash,
                shift.getActualClosingCash(),
                shift.getCashDiscrepancy(),
                shift.getNoteDiscrepancy(),
                shift.getStatus(),
                statusCssClass(shift.getStatus()),
                formatInstant(shift.getApprovedAt()),
                shift.getNote(),
                canClose,
                cashShortage,
                shortageIncome != null ? shortageIncome.getId() : null,
                shortageIncome != null ? shortageIncome.getIncomeCode() : null,
                shortageIncome != null ? shortageIncome.getStatus() : null,
                cashShortage.signum() > 0 && shortageIncome == null
        );
    }

    /** Phần quỹ còn thiếu của một ca: {@code |cashDiscrepancy|} khi âm, ngược lại 0. */
    private BigDecimal shortageOf(Shiftreport shift) {
        BigDecimal discrepancy = shift.getCashDiscrepancy();
        return discrepancy != null && discrepancy.signum() < 0 ? discrepancy.abs() : BigDecimal.ZERO;
    }

    /**
     * Phiếu thu (nếu có) đang gắn vào ca này qua {@code Income.shiftReportOfAccountID}. Bỏ qua phiếu
     * đã bị Từ chối — cùng luật với {@code IncomeService.linkedShiftReportOfAccountIds()}, để một ca
     * bị từ chối phiếu thu vẫn lập lại được thay vì kẹt vĩnh viễn.
     *
     * <p>Chỉ ĐỌC bảng income, không đụng gì vào module Thu/Chi.</p>
     */
    private Income findShortageIncome(Integer shiftReportId) {
        return incomeRepository.findAllWithRelations().stream()
                .filter(income -> income.getShiftReportOfAccountID() != null
                        && shiftReportId.equals(income.getShiftReportOfAccountID().getId()))
                .filter(income -> !isStatus(income.getStatus(), INCOME_STATUS_REJECTED))
                .findFirst()
                .orElse(null);
    }

    /** "Nộp" / "Chốt ca" — applies to a fresh Nháp shift and to a Từ chối shift being resubmitted. */
    @Transactional
    public void closeShift(Integer shiftReportId,
                           BigDecimal openingCashOverride,
                           BigDecimal actualClosingCash,
                           String noteDiscrepancy,
                           String note,
                           Integer currentAccountId,
                           boolean isOwner) {
        Shiftreport shift = shiftreportRepository.findByIdWithRelations(shiftReportId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy báo cáo ca"));

        // Only the shift's own cashier reconciles their own physical cash count — an Owner reviewing
        // someone else's shift approves/rejects it (separate action) instead of closing it for them.
        if (shift.getCashierID() == null || !shift.getCashierID().getId().equals(currentAccountId)) {
            throw new IllegalArgumentException("Chỉ người trực ca mới có thể nộp báo cáo ca này");
        }

        boolean closable = isStatus(shift.getStatus(), ShiftReportStatus.DRAFT)
                || isStatus(shift.getStatus(), ShiftReportStatus.REJECTED);
        if (!closable) {
            throw new IllegalArgumentException("Chỉ có thể nộp báo cáo ca đang ở trạng thái nháp hoặc bị từ chối");
        }

        if (actualClosingCash == null) {
            throw new IllegalArgumentException("Vui lòng nhập số tiền mặt thực đếm cuối ca");
        }

        if (openingCashOverride != null) {
            shift.setOpeningCash(openingCashOverride);
        }

        applyTransactionTotals(shift);

        BigDecimal expectedClosingCash = nz(shift.getOpeningCash())
                .add(nz(shift.getTotalCashIn()))
                .subtract(nz(shift.getTotalCashOut()));

        shift.setExpectedClosingCash(expectedClosingCash);
        shift.setActualClosingCash(actualClosingCash);
        shift.setCashDiscrepancy(actualClosingCash.subtract(expectedClosingCash));
        shift.setNoteDiscrepancy(trimToNull(noteDiscrepancy));
        shift.setNote(trimToNull(note));
        shift.setEndTime(nowVn());

        if (isOwner) {
            shift.setStatus(ShiftReportStatus.APPROVED);
            shift.setApprovedAt(nowVn());
            creditCashSafe(shift);
        } else {
            shift.setStatus(ShiftReportStatus.PENDING);
        }

        shiftreportRepository.save(shift);
        if (!isOwner) {
            workflowNotificationService
                    .shiftReportPending(shift);
        }
    }

    @Transactional
    public void approve(Integer shiftReportId, Integer ownerAccountId) {
        Shiftreport shift = shiftreportRepository.findByIdWithRelations(shiftReportId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy báo cáo ca"));

        if (!isStatus(shift.getStatus(), ShiftReportStatus.PENDING)) {
            throw new IllegalArgumentException("Chỉ có thể duyệt báo cáo ca đang chờ duyệt");
        }

        shift.setStatus(ShiftReportStatus.APPROVED);
        shift.setApprovedAt(nowVn());
        creditCashSafe(shift);

        shiftreportRepository.save(shift);
        workflowNotificationService.shiftReportApproved(shift);
    }

    /**
     * Nộp tiền mặt của ca vào QUỸ ({@code Financialsetting.cashSafeBalance}) đúng lúc ca được duyệt.
     *
     * <p><strong>Doanh thu và quỹ là hai con số khác nhau.</strong> Bán hàng ghi nhận DOANH THU ngay
     * lúc lập hóa đơn (số đó đi vào kỳ tính thuế); còn QUỸ chỉ tăng khi người trực ca giao lại tiền
     * mặt thật lúc kết ca. Bán 1.200.000 mà két thiếu 200.000 thì ca vẫn ghi doanh thu 1.200.000,
     * quỹ chỉ nhận 1.000.000 — phần thiếu là khoản phải THU LẠI của người trực (phiếu thu riêng,
     * loại {@code SHIFT_SHORTAGE}), không phải khoản giảm doanh thu.</p>
     *
     * <p><strong>Chốt ca nộp đúng phần CHÊNH LỆCH THỰC ĐẾM</strong> ({@code cashDiscrepancy}), không
     * nộp lại toàn bộ tiền của ca. Lý do: tiền mặt thu trong ca ĐÃ được cộng vào quỹ ngay lúc phát
     * sinh — {@code InvoiceService.createSaleInvoice} và {@code IncomeService} đều gọi
     * {@code applyFundDelta(paidByCash, paidByBanking)}. Cộng thêm {@code thực đếm − đầu ca} ở đây
     * nữa là đếm cùng một tờ tiền hai lần.</p>
     *
     * <p>Cộng chênh lệch thì kết quả cuối cùng đúng bằng tiền thật đếm được:</p>
     * <pre>
     * quỹ 1.000.000
     *   + 199.000  (bán hàng — quỹ nhận theo số "đáng lẽ phải có")
     *   −  99.000  (chốt ca — thực đếm thiếu 99.000 so với dự kiến)
     *   = 1.100.000  ← đúng số tiền mặt đang thật sự nằm trong két
     * </pre>
     *
     * <p>Phần 99.000 thiếu là khoản phải THU LẠI của người trực; khi lập phiếu thu
     * {@code SHIFT_SHORTAGE} và phiếu đó hoàn thành, {@code IncomeService} cộng nốt vào quỹ →
     * 1.199.000. Doanh thu của ca thì KHÔNG đổi (vẫn 199.000, số của kỳ tính thuế) — thâm hụt quỹ
     * không bao giờ là khoản giảm doanh thu.</p>
     *
     * <p>Gọi đúng tại bước chuyển sang {@code Đã duyệt} — trạng thái này là ĐIỂM CUỐI (chỉ tới được
     * một lần: {@code approve()} chỉ nhận ca Chờ duyệt, {@code closeShift()} chỉ nhận Nháp/Từ chối)
     * nên không có đường nào trừ quỹ hai lần. Ca bị từ chối chưa từng trừ nên nộp lại vẫn đúng.</p>
     *
     * <p><strong>⚠️ Phụ thuộc ngầm cần nhớ:</strong> công thức này đúng vì bên bán hàng/phiếu thu tự
     * cộng quỹ lúc lập. Nếu sau này module đó bỏ {@code applyFundDelta}, chỗ này phải đổi thành
     * {@code thực đếm − đầu ca}. Ngoài ra tiền mặt CHI ra trong ca ({@code totalCashOut}) hiện chưa
     * có nơi nào trừ khỏi quỹ — thiếu sót sẵn có của module Phiếu chi, không xử lý ở đây để không
     * giành việc của họ rồi trừ hai lần khi họ làm.</p>
     */
    private void creditCashSafe(Shiftreport shift) {
        // Chỉ đụng quỹ TIỀN MẶT: chuyển khoản không qua ngăn kéo nên không có gì để đối chiếu lúc
        // chốt ca, quỹ ngân hàng đã nhận đủ ngay lúc lập hóa đơn/phiếu thu.
        financialsettingService.applyFundDelta(nz(shift.getCashDiscrepancy()), BigDecimal.ZERO);
    }

    @Transactional
    public void reject(Integer shiftReportId, Integer ownerAccountId) {
        Shiftreport shift = shiftreportRepository.findByIdWithRelations(shiftReportId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy báo cáo ca"));

        if (!isStatus(shift.getStatus(), ShiftReportStatus.PENDING)) {
            throw new IllegalArgumentException("Chỉ có thể từ chối báo cáo ca đang chờ duyệt");
        }

        shift.setStatus(ShiftReportStatus.REJECTED);
        shift.setApprovedAt(nowVn());

        shiftreportRepository.save(shift);
        workflowNotificationService.shiftReportRejected(shift);
    }

    /** Pure (non-persisting) computation of a shift's totals from the Invoice/Return FKs — used both to
     *  persist at close time and to preview live numbers on a still-open (Nháp) shift. */
    private record TransactionTotals(int totalInvoices,
                                     BigDecimal totalRevenue,
                                     BigDecimal totalCashIn,
                                     BigDecimal totalBankingIn,
                                     int totalReturns,
                                     BigDecimal totalReturnAmount,
                                     BigDecimal totalCashOut,
                                     BigDecimal totalBankingOut,
                                     BigDecimal totalDebtCollected) {
    }

    private TransactionTotals computeTransactionTotals(Integer shiftId) {
        List<Invoice> invoices = invoiceRepository.findAll()
                .stream()
                .filter(inv -> inv.getShiftReportID() != null && shiftId.equals(inv.getShiftReportID().getId()))
                .toList();

        int totalInvoices = invoices.size();
        BigDecimal totalRevenue = invoices.stream()
                .map(Invoice::getTotal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCashIn = invoices.stream()
                .map(Invoice::getPaidByCash)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalBankingIn = invoices.stream()
                .map(Invoice::getPaidByBanking)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Return> returns = returnRepository.findAllWithRelations()
                .stream()
                .filter(ret -> ret.getShiftReportID() != null && shiftId.equals(ret.getShiftReportID().getId()))
                .filter(ret -> ret.getInvoiceID() != null)
                .filter(ret -> isStatus(ret.getStatus(), ReturnStatus.DEBT))
                .toList();

        int totalReturns = returns.size();
        BigDecimal totalReturnAmount = returns.stream()
                .map(Return::getTotalRefund)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Tiền chi trong ca KHÔNG lấy từ phiếu trả: phiếu trả chỉ TÍNH nghĩa vụ phải hoàn (totalRefund),
        // tiền chỉ thật sự rời két khi Kế toán lập phiếu chi. Đã bỏ hẳn refundCash/
        // refundBanking/refundCredit khỏi bảng `return`, nên nguồn duy nhất còn lại là Expense.
        // Cùng nguyên tắc với phiếu thu ở dưới: bỏ phiếu Nháp/Từ chối/Đã hủy, phiếu Chờ duyệt VẪN tính
        // vì tiền đã ra khỏi két lúc chi, trước khi Owner review.
        List<Expense> expenses = expenseRepository.findAll()
                .stream()
                .filter(exp -> exp.getShiftReportID() != null && shiftId.equals(exp.getShiftReportID().getId()))
                .filter(exp -> !isStatus(exp.getStatus(), ExpenseStatus.DRAFT)
                        && !isStatus(exp.getStatus(), ExpenseStatus.REJECTED)
                        && !isStatus(exp.getStatus(), ExpenseStatus.CANCELLED))
                .toList();

        BigDecimal totalCashOut = expenses.stream()
                .map(Expense::getPaidByCash)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Chuyển khoản chi ra — cùng nguồn Expense, nhưng KHÔNG vào panel "Đối chiếu tiền mặt":
        // panel đó đối chiếu số đếm được trong két, tiền chuyển khoản không đi qua két.
        BigDecimal totalBankingOut = expenses.stream()
                .map(Expense::getPaidByBanking)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Incomes (phiếu thu) attached to this shift by IncomeService's hook. Drafts never get
        // attached; rejected slips are excluded. A still-pending slip DOES count: the cash already
        // physically entered the register when the pharmacist recorded it — the register
        // reconciliation must reflect that even before the Owner reviews the slip.
        List<Income> incomes = incomeRepository.findAll()
                .stream()
                .filter(inc -> inc.getShiftReportID() != null && shiftId.equals(inc.getShiftReportID().getId()))
                .filter(inc -> !isStatus(inc.getStatus(), INCOME_STATUS_DRAFT)
                        && !isStatus(inc.getStatus(), INCOME_STATUS_REJECTED))
                .toList();

        for (Income income : incomes) {
            totalCashIn = totalCashIn.add(nz(income.getPaidByCash()));
            totalBankingIn = totalBankingIn.add(nz(income.getPaidByBanking()));
        }

        BigDecimal totalDebtCollected = incomes.stream()
                .filter(inc -> IncomeTypeOptionResponse.isCustomer(inc.getIncomeType())
                        || inc.getCustomerID() != null)
                .map(Income::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Tiền nhân viên đền bù thất thoát kho KHÔNG tách thành dòng riêng (BA chốt 28/07): nó là một
        // phiếu thu như mọi phiếu thu khác, đã được cộng vào totalCashIn/totalBankingIn ở vòng lặp
        // trên. Ca không cần biết khoản thu đó đến từ chênh lệch kho hay từ đâu — chỉ cần đúng số.
        return new TransactionTotals(totalInvoices, totalRevenue, totalCashIn, totalBankingIn,
                totalReturns, totalReturnAmount, totalCashOut, totalBankingOut, totalDebtCollected);
    }

    /** Recomputes and persists the shift's transaction totals — called right before closing it. */
    private void applyTransactionTotals(Shiftreport shift) {
        TransactionTotals totals = computeTransactionTotals(shift.getId());

        shift.setTotalInvoices(totals.totalInvoices());
        shift.setTotalRevenue(totals.totalRevenue());
        shift.setTotalCashIn(totals.totalCashIn());
        shift.setTotalBankingIn(totals.totalBankingIn());
        shift.setTotalReturns(totals.totalReturns());
        shift.setTotalReturnAmount(totals.totalReturnAmount());
        shift.setTotalCashOut(totals.totalCashOut());
        shift.setTotalBankingOut(totals.totalBankingOut());
        shift.setTotalDebtCollected(totals.totalDebtCollected());
    }

    /**
     * Tiền đầu ca = khoản quỹ CỐ ĐỊNH cấp cho mỗi ca, lấy từ {@code Financialsetting.openingCashDefault}.
     *
     * <p>Mỗi ca có phần quỹ riêng và luôn được cấp cùng một khoản, nên ca sau KHÔNG kế thừa số cuối ca
     * của ca trước — kế thừa sẽ làm số đầu ca phụ thuộc vào việc Owner đã duyệt ca cũ hay chưa. Người
     * trực vẫn sửa được số này lúc chốt ({@code openingCashOverride}) nếu thực nhận khác mức cấp.</p>
     */
    private BigDecimal resolveOpeningCash() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(Financialsetting::getOpeningCashDefault)
                .orElse(BigDecimal.ZERO);
    }

    private String resolveShiftType() {
        int hour = LocalTime.now(VN_ZONE).getHour();
        if (hour < 12) {
            return "Sáng";
        }
        if (hour < 18) {
            return "Chiều";
        }
        return "Tối";
    }

    private ShiftReportListItemResponse toListItem(Shiftreport shift) {
        boolean live = isStatus(shift.getStatus(), ShiftReportStatus.DRAFT);
        TransactionTotals totals = live ? computeTransactionTotals(shift.getId()) : null;

        return new ShiftReportListItemResponse(
                shift.getId(),
                shift.getShiftReportCode(),
                shift.getCashierID() != null ? shift.getCashierID().getName() : "Không rõ",
                formatLocalDate(shift.getShiftDate()),
                shift.getShiftType(),
                formatInstant(shift.getStartTime()),
                formatInstant(shift.getEndTime()),
                shift.getOpeningCash(),
                live ? totals.totalRevenue() : shift.getTotalRevenue(),
                live ? totals.totalReturnAmount() : shift.getTotalReturnAmount(),
                shift.getCashDiscrepancy(),
                shift.getStatus(),
                statusCssClass(shift.getStatus())
        );
    }

    private boolean matchesKeyword(Shiftreport shift, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        return containsNormalized(shift.getShiftReportCode(), keyword)
                || containsNormalized(shift.getStatus(), keyword)
                || containsNormalized(shift.getCashierID() != null ? shift.getCashierID().getName() : null, keyword);
    }

    /**
     * Ca chưa chốt ({@code cashDiscrepancy} còn null) KHÔNG lọt vào bất kỳ nhóm chênh lệch nào —
     * chưa đếm tiền thì chưa biết thừa hay thiếu, xếp nó vào "thâm hụt" là vu oan cho người trực.
     */
    private boolean matchesDiscrepancy(Shiftreport shift, String discrepancy) {
        if (discrepancy == null || discrepancy.isBlank()) {
            return true;
        }
        BigDecimal value = shift.getCashDiscrepancy();
        if (value == null) {
            return false;
        }
        return switch (discrepancy) {
            case DISCREPANCY_SHORTAGE -> value.signum() < 0;
            case DISCREPANCY_SURPLUS -> value.signum() > 0;
            default -> true;
        };
    }

    private boolean matchesDate(Shiftreport shift, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        LocalDate shiftDate = shift.getShiftDate();
        if (shiftDate == null) {
            return false;
        }
        if (from != null && shiftDate.isBefore(from)) {
            return false;
        }
        return to == null || !shiftDate.isAfter(to);
    }

    private long countByStatus(List<Shiftreport> shifts, String status) {
        return shifts.stream()
                .filter(shift -> isStatus(shift.getStatus(), status))
                .count();
    }

    private String statusCssClass(String status) {
        if (isStatus(status, ShiftReportStatus.APPROVED)) {
            return "status-approved";
        }
        if (isStatus(status, ShiftReportStatus.PENDING)) {
            return "status-pending";
        }
        if (isStatus(status, ShiftReportStatus.REJECTED)) {
            return "status-rejected";
        }
        if (isStatus(status, ShiftReportStatus.DRAFT)) {
            return "status-draft";
        }
        return "status-default";
    }

    /**
     * Mã tạm dùng đúng một lần, chỉ để qua được ràng buộc {@code NOT NULL UNIQUE} của
     * {@code shiftReportCode} tại thời điểm INSERT — lúc đó chưa biết id nên chưa dựng được mã thật.
     * Ngay sau khi lưu, mã được ghi lại theo id do DB cấp. Không bao giờ commit ra ngoài: cả hai bước
     * nằm trong cùng một transaction.
     *
     * <p>Trước đây mã sinh bằng {@code max(id) + 1} <em>trước khi</em> lưu — đọc rồi mới ghi, nên hai
     * người phát sinh giao dịch đầu ca cùng lúc nhận cùng một số; cột mã có UNIQUE nên người thứ hai ăn
     * lỗi 500 thay vì được cấp mã kế tiếp. AUTO_INCREMENT của DB thì không bao giờ cấp trùng.</p>
     */
    private String temporaryCode() {
        return "TMP-" + UUID.randomUUID();
    }

    private String formatCode(Integer id) {
        return id == null ? "CA-000000" : "CA-" + String.format("%06d", id);
    }

    private Instant nowVn() {
        return LocalDateTime.now(VN_ZONE).toInstant(ZoneOffset.UTC);
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

    private String formatLocalDate(LocalDate date) {
        if (date == null) {
            return "";
        }
        return date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    // nowVn() stores VN wall-clock digits inside a UTC-labelled Instant (see its own doc) — so reading
    // it back must use ZoneOffset.UTC, not VN_ZONE, or the +7h gets applied twice (matches ReturnService).
    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneOffset.UTC)
                .format(instant);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
