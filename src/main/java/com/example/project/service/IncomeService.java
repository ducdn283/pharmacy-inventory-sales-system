package com.example.project.service;

import com.example.project.constant.ReturnPurchaseStatus;
import com.example.project.constant.ShiftReportStatus;
import com.example.project.constant.StockAdjustmentStatus;
import com.example.project.dto.request.IncomeCreateRequest;
import com.example.project.dto.response.CustomerOptionResponse;
import com.example.project.dto.response.IncomeDetailResponse;
import com.example.project.dto.response.IncomeListItemResponse;
import com.example.project.dto.response.IncomeReferenceOptionResponse;
import com.example.project.dto.response.IncomeTypeOptionResponse;
import com.example.project.entity.Account;
import com.example.project.entity.Customer;
import com.example.project.entity.Income;
import com.example.project.entity.Invoice;
import com.example.project.entity.Purchaseinvoice;
import com.example.project.entity.Return;
import com.example.project.entity.Productunit;
import com.example.project.entity.Shiftreport;
import com.example.project.entity.Stockadjustment;
import com.example.project.entity.Stockadjustmentdetail;
import com.example.project.entity.Supplier;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.CustomerRepository;
import com.example.project.repository.IncomeRepository;
import com.example.project.repository.InvoiceRepository;
import com.example.project.repository.PurchaseinvoiceRepository;
import com.example.project.repository.ReturnRepository;
import com.example.project.repository.ShiftreportRepository;
import com.example.project.repository.StockadjustmentRepository;
import com.example.project.repository.StockadjustmentdetailRepository;
import com.example.project.repository.SupplierRepository;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Nghiệp vụ phiếu thu ({@link Income}) phục vụ màn Chủ nhà thuốc / Dược sĩ / Kế toán
 * ({@code /owner/incomes/**}, {@code /pharmacist/incomes/**}, {@code /accountant/incomes/**}).
 */
@Service
public class IncomeService {

    private static final String STATUS_DRAFT = "Nháp";
    private static final String STATUS_PENDING = "Chờ duyệt";
    private static final String STATUS_COMPLETED = "Hoàn thành";
    private static final String STATUS_COMPLETED_LEGACY = "Duyệt";
    private static final String STATUS_REJECTED = "Từ chối";
    private static final String STATUS_CANCELLED = "Đã hủy";

    private static final String INVOICE_STATUS_DEBT = "Còn nợ";
    private static final String INVOICE_STATUS_COMPLETED = "Hoàn thành";
    /** Trạng thái phiếu điều chỉnh kho legacy trước khi bỏ bước duyệt (2026-07-27). */
    private static final String STOCK_ADJUSTMENT_STATUS_COMPLETED_LEGACY = "Duyệt";

    /**
     * Hai loại phiếu điều chỉnh được phép liên kết phiếu thu "Thu tiền nhân viên làm hỏng hàng"
     * ({@code Dac_ta_Income_StockAdjustment.xlsx} sheet 03).
     */
    private static final Set<String> EMPLOYEE_LIABLE_ADJUSTMENT_TYPES =
            Set.of("DESTROY_EMPLOYEE_FAULT", "COUNT_DECREASE");

    private static final String PAYMENT_CASH = "CASH";
    private static final String PAYMENT_BANKING = "BANKING";
    private static final String PAYMENT_MIXED = "MIXED";
    private static final String PAYMENT_CREDIT = "CREDIT";
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final IncomeRepository incomeRepository;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final InvoiceRepository invoiceRepository;
    private final ReturnRepository returnRepository;
    private final PurchaseinvoiceRepository purchaseinvoiceRepository;
    private final StockadjustmentRepository stockadjustmentRepository;
    private final StockadjustmentdetailRepository stockadjustmentdetailRepository;
    private final ShiftreportRepository shiftreportRepository;
    /** Mở hoặc tái sử dụng ca của người thu tiền khi ghi nhận phiếu thu — cùng cơ chế với {@link InvoiceService}. */
    private final ShiftreportService shiftreportService;
    private final InvoiceService invoiceService;
    private final FinancialsettingService financialsettingService;
    private final WorkflowNotificationService workflowNotificationService;

    public IncomeService(IncomeRepository incomeRepository,
                         AccountRepository accountRepository,
                         CustomerRepository customerRepository,
                         SupplierRepository supplierRepository,
                         InvoiceRepository invoiceRepository,
                         ReturnRepository returnRepository,
                         PurchaseinvoiceRepository purchaseinvoiceRepository,
                         StockadjustmentRepository stockadjustmentRepository,
                         StockadjustmentdetailRepository stockadjustmentdetailRepository,
                         ShiftreportRepository shiftreportRepository,
                         ShiftreportService shiftreportService,
                         InvoiceService invoiceService,
                         FinancialsettingService financialsettingService,
                         WorkflowNotificationService workflowNotificationService) {
        this.incomeRepository = incomeRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
        this.invoiceRepository = invoiceRepository;
        this.returnRepository = returnRepository;
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.stockadjustmentRepository = stockadjustmentRepository;
        this.stockadjustmentdetailRepository = stockadjustmentdetailRepository;
        this.shiftreportRepository = shiftreportRepository;
        this.shiftreportService = shiftreportService;
        this.invoiceService = invoiceService;
        this.financialsettingService = financialsettingService;
        this.workflowNotificationService = workflowNotificationService;
    }

    /** Danh sách phiếu thu có phân trang, lọc theo mã, ngày, loại, trạng thái, hình thức thu và người lập. */
    @Transactional(readOnly = true)
    public Page<IncomeListItemResponse> list(String search,
                                       String fromDate,
                                       String toDate,
                                       String incomeType,
                                       String status,
                                       String paymentType,
                                       Integer applicantId,
                                       Pageable pageable) {
        String normalizedKeyword = normalize(search);
        LocalDate from = parseDate(fromDate);
        LocalDate to = parseDate(toDate);
        String normalizedStatus = status == null ? "" : status.trim();

        List<IncomeListItemResponse> filtered = incomeRepository.findAllWithRelations().stream()
                .filter(income -> matchesKeyword(income, normalizedKeyword))
                .filter(income -> matchesDate(income, from, to))
                .filter(income -> incomeType == null || incomeType.isBlank()
                        || resolveIncomeType(income).equals(IncomeTypeOptionResponse.codeOf(incomeType)))
                .filter(income -> applicantId == null || matchesApplicant(income, applicantId))
                .filter(income -> normalizedStatus.isEmpty() || matchesStatus(income, normalizedStatus))
                .filter(income -> matchesPaymentType(income, paymentType))
                .sorted(Comparator.comparing(Income::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Income::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toListItem)
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<IncomeListItemResponse> content = start >= filtered.size() ? List.of() : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    /** Các trạng thái hợp lệ — dropdown lọc trên màn danh sách. */
    @Transactional(readOnly = true)
    public List<String> listStatuses() {
        return List.of(STATUS_DRAFT, STATUS_PENDING, STATUS_COMPLETED, STATUS_REJECTED, STATUS_CANCELLED);
    }

    /** Danh sách người đã từng lập phiếu thu — dropdown lọc theo người lập. */
    @Transactional(readOnly = true)
    public List<CustomerOptionResponse> listApplicants() {
        Map<Integer, String> byId = new LinkedHashMap<>();
        for (Income income : incomeRepository.findAllWithRelations()) {
            Account applicant = income.getApplicantID();
            if (applicant != null && applicant.getId() != null) {
                byId.putIfAbsent(applicant.getId(), applicant.getName());
            }
        }
        return byId.entrySet().stream()
                .map(entry -> new CustomerOptionResponse(entry.getKey(), entry.getValue(), null, null))
                .sorted(Comparator.comparing(CustomerOptionResponse::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /** Tất cả loại phiếu thu cho dropdown form tạo phiếu. */
    public List<IncomeTypeOptionResponse> listIncomeTypes() {
        return IncomeTypeOptionResponse.all();
    }

    /** Nhãn tiếng Việt cho mã hình thức thu (CASH, BANKING, MIXED, CREDIT). */
    public Map<String, String> paymentTypeLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PAYMENT_CASH, "Tiền mặt");
        labels.put(PAYMENT_BANKING, "Chuyển khoản");
        labels.put(PAYMENT_MIXED, "TM + CK");
        labels.put(PAYMENT_CREDIT, "Cấn trừ công nợ");
        return labels;
    }

    /** Danh sách khách hàng cho dropdown form tạo phiếu thu nợ khách. */
    @Transactional(readOnly = true)
    public List<CustomerOptionResponse> listCustomers() {
        return customerRepository.findAll().stream()
                .sorted(Comparator.comparing(Customer::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(customer -> new CustomerOptionResponse(
                        customer.getId(), customer.getName(), customer.getPhoneNumber(), customer.getCustomerType()))
                .toList();
    }

    /** Danh sách nhà cung cấp cho dropdown form tạo phiếu thu nợ NCC. */
    @Transactional(readOnly = true)
    public List<CustomerOptionResponse> listSuppliers() {
        return supplierRepository.findAll().stream()
                .sorted(Comparator.comparing(Supplier::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(supplier -> new CustomerOptionResponse(supplier.getId(), supplier.getName(), null, null))
                .toList();
    }

    /** Danh sách nhân viên đang hoạt động — chọn người chịu trách nhiệm trên form. */
    @Transactional(readOnly = true)
    public List<CustomerOptionResponse> listEmployees() {
        return accountRepository.findAll().stream()
                .filter(account -> Boolean.TRUE.equals(account.getStatus()))
                .sorted(Comparator.comparing(Account::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(account -> new CustomerOptionResponse(account.getId(), account.getName(), null, null))
                .toList();
    }

    /** Hóa đơn bán còn nợ của khách — chọn chứng từ thu nợ khách hàng. */
    @Transactional(readOnly = true)
    public List<IncomeReferenceOptionResponse> listDebtInvoices(Integer customerId) {
        if (customerId == null) {
            return List.of();
        }
        return invoiceRepository.findAllWithRelations().stream()
                .filter(invoice -> invoice.getCustomerID() != null
                        && customerId.equals(invoice.getCustomerID().getId()))
                .filter(this::isDebtInvoice)
                .sorted(Comparator.comparing(Invoice::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Invoice::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(invoice -> new IncomeReferenceOptionResponse(
                        invoice.getId(),
                        invoice.getInvoiceNumber(),
                        formatInvoiceDate(invoice.getDate()),
                        invoice.getDebtAmount(),
                        "Tổng HĐ: " + formatMoney(invoice.getTotal())))
                .toList();
    }

    /** Phiếu trả NCC còn tiền phải hoàn — chọn chứng từ thu nợ NCC. */
    @Transactional(readOnly = true)
    public List<IncomeReferenceOptionResponse> listSupplierReturns(Integer supplierId) {
        if (supplierId == null) {
            return List.of();
        }
        Map<Integer, Purchaseinvoice> purchasesById = purchaseinvoiceRepository.findAllWithRelations().stream()
                .collect(Collectors.toMap(Purchaseinvoice::getId, purchase -> purchase, (a, b) -> a));
        Map<Integer, BigDecimal> accounted = accountedByReturnId();

        return returnRepository.findAllWithRelations().stream()
                .filter(this::isApprovedSupplierReturn)
                .filter(ret -> {
                    Purchaseinvoice purchase = purchasesById.get(
                            ret.getPurchaseID() != null ? ret.getPurchaseID().getId() : null);
                    return purchase != null && purchase.getSupplierID() != null
                            && supplierId.equals(purchase.getSupplierID().getId());
                })
                .map(ret -> Map.entry(ret, remainingCollectibleFromSupplier(ret, accounted)))
                .filter(entry -> isPositive(entry.getValue()))
                .sorted(Comparator.comparing((Map.Entry<Return, BigDecimal> entry) -> entry.getKey().getReturnDate(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(entry -> entry.getKey().getId(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(entry -> {
                    Return ret = entry.getKey();
                    Purchaseinvoice purchase = purchasesById.get(ret.getPurchaseID().getId());
                    String purchaseCode = purchase != null ? purchase.getPurchaseInvoiceCode() : "—";
                    return new IncomeReferenceOptionResponse(
                            ret.getId(),
                            ret.getReturnCode(),
                            formatInstant(ret.getReturnDate()),
                            entry.getValue(),
                            "Phiếu nhập: " + purchaseCode);
                })
                .toList();
    }

    /** Phiếu điều chỉnh kho nhân viên phải đền bù — chọn chứng từ thu đền bù. */
    @Transactional(readOnly = true)
    public List<IncomeReferenceOptionResponse> listStockAdjustments(Integer accountId) {
        if (accountId == null) {
            return List.of();
        }
        Set<Integer> linkedAdjustmentIds = linkedStockAdjustmentIds();

        List<Stockadjustment> eligible = stockadjustmentRepository.findAllWithRelations().stream()
                .filter(this::isCompletedStockAdjustment)
                .filter(this::isEmployeeLiableStockAdjustment)
                .filter(adjustment -> !linkedAdjustmentIds.contains(adjustment.getId()))
                .sorted(Comparator.comparing(Stockadjustment::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Stockadjustment::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        Set<Integer> eligibleIds = eligible.stream()
                .map(Stockadjustment::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Integer, BigDecimal> reimbursementById = reimbursementByAdjustmentIds(eligibleIds);

        return eligible.stream()
                .map(adjustment -> new IncomeReferenceOptionResponse(
                        adjustment.getId(),
                        adjustment.getStockAdjustmentCode(),
                        formatInstant(adjustment.getDate()),
                        reimbursementById.getOrDefault(adjustment.getId(), BigDecimal.ZERO),
                        adjustment.getReason()))
                .toList();
    }

    /** Báo cáo ca còn thiếu tiền mặt của nhân viên — chọn chứng từ thu thất thoát ca. */
    @Transactional(readOnly = true)
    public List<IncomeReferenceOptionResponse> listShiftReportsWithShortage(Integer accountId) {
        if (accountId == null) {
            return List.of();
        }
        Set<Integer> linkedShiftIds = linkedShiftReportOfAccountIds();

        return shiftreportRepository.findAllWithRelations().stream()
                .filter(shift -> shift.getCashierID() != null
                        && accountId.equals(shift.getCashierID().getId()))
                .filter(this::isShiftWithCollectibleShortage)
                .filter(shift -> !linkedShiftIds.contains(shift.getId()))
                .sorted(Comparator.comparing(Shiftreport::getShiftDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Shiftreport::getStartTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Shiftreport::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(shift -> new IncomeReferenceOptionResponse(
                        shift.getId(),
                        shift.getShiftReportCode(),
                        formatShiftReferenceDate(shift),
                        collectibleShortageAmount(shift),
                        "Ca " + nullToEmpty(shift.getShiftType())
                                + " — Thiếu: " + formatMoney(collectibleShortageAmount(shift))))
                .toList();
    }

    /** Chi tiết phiếu thu — không kiểm tra quyền hủy. */
    @Transactional(readOnly = true)
    public IncomeDetailResponse getDetail(Integer incomeId) {
        return getDetail(incomeId, null);
    }

    /** Chi tiết phiếu thu — kèm cờ {@code canCancel} theo tài khoản đang đăng nhập. */
    @Transactional(readOnly = true)
    public IncomeDetailResponse getDetail(Integer incomeId, Integer currentAccountId) {
        Income income = incomeRepository.findByIdWithRelations(incomeId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu thu"));
        return toDetail(income, currentAccountId);
    }

    /** Tiền NCC còn phải hoàn trên phiếu trả hàng, sau các phiếu thu đã hoàn thành. */
    @Transactional(readOnly = true)
    public BigDecimal remainingCollectibleForSupplierReturn(Integer returnId) {
        if (returnId == null) {
            return BigDecimal.ZERO;
        }
        Return ret = returnRepository.findById(returnId).orElse(null);
        if (!isApprovedSupplierReturn(ret)) {
            return BigDecimal.ZERO;
        }
        return remainingCollectibleFromSupplier(ret, accountedByReturnId());
    }

    /** Tổng tiền đã thu qua phiếu thu SUPPLIER hoàn thành, gom theo id phiếu trả NCC. */
    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> collectedAmountBySupplierReturnId() {
        return accountedByReturnId();
    }

    /**
     * Tạo phiếu thu thủ công.
     * {@code asDraft = true}: lưu nháp; ngược lại hoàn thành ngay (phiếu thu không cần duyệt).
     */
    @Transactional
    public Integer createIncome(IncomeCreateRequest request, Integer currentAccountId, boolean asDraft) {
        validateCreateRequest(request);

        Account applicant = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        String incomeTypeCode = resolveIncomeType(request.getIncomeType());
        BigDecimal[] split = resolveSplit(request);

        Income income = new Income();
        income.setApplicantID(applicant);
        income.setIncomeType(IncomeTypeOptionResponse.storageLabelOf(incomeTypeCode));
        income.setDate(nowVn());
        income.setReason(request.getReason() != null ? request.getReason().trim() : "");
        income.setAmount(request.getAmount());
        income.setPaidByCash(split[0]);
        income.setPaidByBanking(split[1]);
        // Cột paidByCredit NOT NULL — gán 0 khi không dùng cấn trừ (giống ExpenseService.create).
        income.setPaidByCredit(BigDecimal.ZERO);
        income.setNote(trimToNull(request.getNote()));
        applyPartyLinks(income, incomeTypeCode, request);
        applyReferenceLinks(income, incomeTypeCode, request);

        if (asDraft) {
            income.setStatus(STATUS_DRAFT);
        } else {
            income.setStatus(STATUS_COMPLETED);
        }

        // Phiếu hoàn thành là giao dịch thật → gắn ca đang mở; nháp chưa gắn ca.
        if (!asDraft) {
            income.setShiftReportID(shiftreportService.ensureOpenShiftFor(currentAccountId));
        }

        income.setIncomeCode(generateCode());
        Income saved = incomeRepository.save(income);
        saved.setIncomeCode(formatCode(saved.getId()));
        saved = incomeRepository.save(saved);
        if (!asDraft && IncomeTypeOptionResponse.CUSTOMER.equals(incomeTypeCode)) {
            applyCustomerDebtPayment(saved, split[0], split[1]);
        }
        if (!asDraft && IncomeTypeOptionResponse.SUPPLIER.equals(incomeTypeCode)) {
            applySupplierOffsetDebtPayment(saved);
        }
        if (!asDraft) {
            workflowNotificationService
                    .incomeCreated(saved);
        }

        creditFundOnCompletion(saved);

        return saved.getId();
    }

    /**
     * Hủy phiếu thu nhập sai — tương tự {@link ExpenseService#cancel}.
     * Phiếu đã hoàn thành vẫn hủy được vì không có luồng sửa sau khi tạo.
     */
    @Transactional
    public void cancel(Integer incomeId, String reason) {
        cancel(incomeId, reason, null);
    }

    /** Hủy phiếu thu — chỉ người lập mới được hủy. */
    @Transactional
    public void cancel(Integer incomeId, String reason, Integer currentAccountId) {
        Income income = incomeRepository.findByIdWithRelations(incomeId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu thu"));
        ensureApplicantAccess(income, currentAccountId);

        if (isStatus(income.getStatus(), STATUS_CANCELLED)) {
            throw new IllegalArgumentException("Phiếu thu này đã bị hủy trước đó");
        }
        DebtOffsetService.assertIncomeNotCancellable(income);

        boolean wasCompleted = isCompletedStatus(income.getStatus());
        if (wasCompleted && IncomeTypeOptionResponse.CUSTOMER.equals(resolveIncomeType(income))) {
            reverseCustomerDebtPayment(income);
        }
        if (wasCompleted) {
            financialsettingService.applyFundDelta(
                    nullToZero(income.getPaidByCash()).negate(),
                    nullToZero(income.getPaidByBanking()).negate());
        }

        income.setStatus(STATUS_CANCELLED);
        String trimmedReason = trimToNull(reason);
        if (trimmedReason != null) {
            String existingNote = income.getNote();
            income.setNote(existingNote == null || existingNote.isBlank()
                    ? "Lý do hủy: " + trimmedReason
                    : existingNote + " | Lý do hủy: " + trimmedReason);
        }

        incomeRepository.save(income);
    }

    /** Cộng tiền mặt / chuyển khoản vào quỹ khi phiếu thu đã hoàn thành (không áp dụng cấn trừ công nợ). */
    private void creditFundOnCompletion(Income income) {
        if (!isCompletedStatus(income.getStatus())) {
            return;
        }
        financialsettingService.applyFundDelta(income.getPaidByCash(), income.getPaidByBanking());
    }

    /** Tổng số phiếu thu — thẻ thống kê màn danh sách. */
    @Transactional(readOnly = true)
    public long countAll() {
        return incomeRepository.count();
    }

    /** Số phiếu thu lập trong ngày hôm nay — thẻ thống kê. */
    @Transactional(readOnly = true)
    public long countToday() {
        LocalDate today = LocalDate.now(VN_ZONE);
        return incomeRepository.findAll().stream()
                .filter(income -> income.getDate() != null && toLocalDate(income.getDate()).equals(today))
                .count();
    }

    /** Tổng tiền thu trong ngày hôm nay — thẻ thống kê. */
    @Transactional(readOnly = true)
    public BigDecimal sumTodayAmount() {
        LocalDate today = LocalDate.now(VN_ZONE);
        return incomeRepository.findAll().stream()
                .filter(income -> income.getDate() != null && toLocalDate(income.getDate()).equals(today))
                .map(Income::getAmount)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Số phiếu thu đã hoàn thành — alias thống kê dashboard. */
    @Transactional(readOnly = true)
    public long countApproved() {
        return countCompleted();
    }

    /** Tổng tiền phiếu thu đã hoàn thành — alias thống kê dashboard. */
    @Transactional(readOnly = true)
    public BigDecimal sumApprovedAmount() {
        return sumCompletedAmount();
    }

    /** Đếm phiếu thu trạng thái hoàn thành (kể cả legacy "Duyệt"). */
    private long countCompleted() {
        return incomeRepository.findAll().stream()
                .filter(income -> isCompletedStatus(income.getStatus()))
                .count();
    }

    /** Tổng tiền phiếu thu trạng thái hoàn thành. */
    private BigDecimal sumCompletedAmount() {
        return incomeRepository.findAll().stream()
                .filter(income -> isCompletedStatus(income.getStatus()))
                .map(Income::getAmount)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Map entity sang DTO một dòng trên bảng danh sách. */
    private IncomeListItemResponse toListItem(Income income) {
        String statusName = income.getStatus() != null ? displayStatus(income.getStatus()) : "Không rõ";
        return new IncomeListItemResponse(
                income.getId(),
                income.getIncomeCode(),
                income.getDate(),
                formatInstant(income.getDate()),
                formatIncomeType(resolveIncomeType(income)),
                displayReason(income),
                income.getApplicantID() != null ? income.getApplicantID().getName() : "Không rõ",
                income.getAmount(),
                paymentDisplay(income.getPaidByCash(), income.getPaidByBanking(), income.getPaidByCredit()),
                statusName,
                statusCssClass(statusName));
    }

    /** Map entity sang DTO trang chi tiết phiếu thu. */
    private IncomeDetailResponse toDetail(Income income, Integer currentAccountId) {
        String typeCode = resolveIncomeType(income);
        String statusName = income.getStatus() != null ? displayStatus(income.getStatus()) : "Không rõ";

        String partyName = "—";
        if (income.getCustomerID() != null && income.getCustomerID().getName() != null) {
            partyName = income.getCustomerID().getName();
        } else if (income.getSupplierID() != null && income.getSupplierID().getName() != null) {
            partyName = income.getSupplierID().getName();
        } else if (income.getAccountID() != null && income.getAccountID().getName() != null) {
            partyName = income.getAccountID().getName();
        }

        String partyTypeDisplay = switch (typeCode) {
            case IncomeTypeOptionResponse.CUSTOMER -> "Khách hàng";
            case IncomeTypeOptionResponse.SUPPLIER -> "Nhà cung cấp";
            case IncomeTypeOptionResponse.EMPLOYEE, IncomeTypeOptionResponse.SHIFT_SHORTAGE -> "Người chịu trách nhiệm";
            default -> "—";
        };

        String referenceTypeDisplay = null;
        Integer invoiceId = null;
        Integer returnId = null;
        Integer stockAdjustmentId = null;
        Integer shiftReportOfAccountId = null;
        if (income.getInvoiceID() != null) {
            referenceTypeDisplay = "Hóa đơn bán hàng";
            invoiceId = income.getInvoiceID().getId();
        } else if (income.getReturnID() != null) {
            referenceTypeDisplay = "Phiếu trả hàng NCC";
            returnId = income.getReturnID().getId();
        } else if (income.getStockAdjustmentID() != null) {
            referenceTypeDisplay = "Phiếu điều chỉnh kho";
            stockAdjustmentId = income.getStockAdjustmentID().getId();
        } else if (income.getShiftReportOfAccountID() != null) {
            referenceTypeDisplay = "Báo cáo ca";
            shiftReportOfAccountId = income.getShiftReportOfAccountID().getId();
        }

        String cancelBlockedReason = cancelBlockedReason(income, currentAccountId);
        return new IncomeDetailResponse(
                income.getId(),
                income.getIncomeCode(),
                formatInstant(income.getDate()),
                formatIncomeType(typeCode),
                income.getApplicantID() != null ? income.getApplicantID().getName() : "Không rõ",
                displayReason(income),
                income.getAmount(),
                income.getPaidByCash(),
                income.getPaidByBanking(),
                income.getPaidByCredit(),
                paymentDisplay(income.getPaidByCash(), income.getPaidByBanking(), income.getPaidByCredit()),
                statusName,
                statusCssClass(statusName),
                partyTypeDisplay,
                partyName,
                referenceTypeDisplay,
                referenceCode(income),
                invoiceId,
                returnId,
                stockAdjustmentId,
                shiftReportOfAccountId,
                income.getNote(),
                cancelBlockedReason == null,
                cancelBlockedReason);
    }

    /**
     * Vì sao phiếu này không hủy được — dùng cho màn hình, để ẩn nút Hủy kèm lời giải thích thay vì
     * để người dùng bấm rồi mới ăn lỗi. {@code null} nghĩa là hủy được.
     */
    private String cancelBlockedReason(Income income, Integer currentAccountId) {
        if (isStatus(income.getStatus(), STATUS_CANCELLED)) {
            return null;
        }
        if (DebtOffsetService.isDebtOffsetIncome(income)) {
            return "Phiếu thu bù trừ công nợ không thể hủy.";
        }
        if (!belongsToApplicant(income, currentAccountId)) {
            return "Chỉ người lập phiếu mới có thể hủy.";
        }
        return null;
    }

    /** Kiểm tra tài khoản hiện tại có phải người lập phiếu không. */
    private boolean belongsToApplicant(Income income, Integer applicantAccountId) {
        return applicantAccountId != null
                && income.getApplicantID() != null
                && applicantAccountId.equals(income.getApplicantID().getId());
    }

    /** Ném lỗi nếu không phải người lập phiếu. */
    private void ensureApplicantAccess(Income income, Integer requiredApplicantAccountId) {
        if (!belongsToApplicant(income, requiredApplicantAccountId)) {
            throw new IllegalArgumentException("Chỉ người lập phiếu mới có thể hủy phiếu thu này");
        }
    }

    /** Nội dung thu hiển thị — trả "—" nếu trống. */
    private String displayReason(Income income) {
        if (income.getReason() == null || income.getReason().isBlank()) {
            return "—";
        }
        return income.getReason();
    }

    /** Suy ra mã loại nội bộ từ cột lưu hoặc liên kết đối tượng/chứng từ. */
    private String resolveIncomeType(Income income) {
        String stored = income.getIncomeType();
        if (stored != null && IncomeTypeOptionResponse.isValid(stored)) {
            return IncomeTypeOptionResponse.codeOf(stored);
        }
        if (income.getSupplierID() != null) {
            return IncomeTypeOptionResponse.SUPPLIER;
        }
        if (income.getCustomerID() != null) {
            return IncomeTypeOptionResponse.CUSTOMER;
        }
        if (income.getShiftReportOfAccountID() != null) {
            return IncomeTypeOptionResponse.SHIFT_SHORTAGE;
        }
        if (income.getAccountID() != null && income.getStockAdjustmentID() != null) {
            return IncomeTypeOptionResponse.EMPLOYEE;
        }
        if (income.getAccountID() != null) {
            return IncomeTypeOptionResponse.EMPLOYEE;
        }
        return IncomeTypeOptionResponse.OTHER;
    }

    /** Mã chứng từ liên quan (HĐ, phiếu trả, điều chỉnh kho, báo cáo ca). */
    private String referenceCode(Income income) {
        if (income.getInvoiceID() != null && income.getInvoiceID().getInvoiceNumber() != null) {
            return income.getInvoiceID().getInvoiceNumber();
        }
        if (income.getReturnID() != null && income.getReturnID().getReturnCode() != null) {
            return income.getReturnID().getReturnCode();
        }
        if (income.getShiftReportOfAccountID() != null
                && income.getShiftReportOfAccountID().getShiftReportCode() != null) {
            return income.getShiftReportOfAccountID().getShiftReportCode();
        }
        if (income.getStockAdjustmentID() != null && income.getStockAdjustmentID().getStockAdjustmentCode() != null) {
            return income.getStockAdjustmentID().getStockAdjustmentCode();
        }
        return "—";
    }

    /** Chuỗi hiển thị hình thức thu từ các cột paidByCash / paidByBanking / paidByCredit. */
    private String paymentDisplay(BigDecimal paidByCash, BigDecimal paidByBanking, BigDecimal paidByCredit) {
        boolean hasCash = isPositive(paidByCash);
        boolean hasBanking = isPositive(paidByBanking);
        boolean hasCredit = isPositive(paidByCredit);
        if (hasCredit && !hasCash && !hasBanking) {
            return paymentTypeLabels().get(PAYMENT_CREDIT);
        }
        if (hasCash && hasBanking && !hasCredit) {
            return paymentTypeLabels().get(PAYMENT_MIXED);
        }
        if (hasBanking && !hasCash && !hasCredit) {
            return paymentTypeLabels().get(PAYMENT_BANKING);
        }
        if (hasCash && !hasBanking && !hasCredit) {
            return paymentTypeLabels().get(PAYMENT_CASH);
        }
        StringBuilder parts = new StringBuilder();
        if (hasCash) {
            parts.append(paymentTypeLabels().get(PAYMENT_CASH));
        }
        if (hasBanking) {
            appendPaymentPart(parts, paymentTypeLabels().get(PAYMENT_BANKING));
        }
        if (hasCredit) {
            appendPaymentPart(parts, paymentTypeLabels().get(PAYMENT_CREDIT));
        }
        return parts.isEmpty() ? "—" : parts.toString();
    }

    /** Nối thêm một phần hình thức thu vào chuỗi hiển thị. */
    private void appendPaymentPart(StringBuilder parts, String label) {
        if (!parts.isEmpty()) {
            parts.append(" + ");
        }
        parts.append(label);
    }

    /** So khớp từ khóa tìm kiếm với mã phiếu thu (đã chuẩn hóa). */
    private boolean matchesKeyword(Income income, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        String code = income.getIncomeCode();
        if (code == null || code.isBlank()) {
            code = formatCode(income.getId());
        }
        return containsNormalized(code, normalizedKeyword);
    }

    /** Kiểm tra ngày phiếu thu nằm trong khoảng lọc. */
    private boolean matchesDate(Income income, LocalDate from, LocalDate to) {
        if (income.getDate() == null) {
            return from == null && to == null;
        }
        LocalDate date = toLocalDate(income.getDate());
        if (from != null && date.isBefore(from)) {
            return false;
        }
        return to == null || !date.isAfter(to);
    }

    /** Kiểm tra phiếu thu thuộc người lập đang lọc. */
    private boolean matchesApplicant(Income income, Integer applicantId) {
        return income.getApplicantID() != null && applicantId.equals(income.getApplicantID().getId());
    }

    /** Kiểm tra hình thức thu khớp bộ lọc (CASH, BANKING, MIXED, CREDIT). */
    private boolean matchesPaymentType(Income income, String paymentType) {
        if (paymentType == null || paymentType.isBlank()) {
            return true;
        }
        boolean cash = isPositive(income.getPaidByCash());
        boolean banking = isPositive(income.getPaidByBanking());
        boolean credit = isPositive(income.getPaidByCredit());
        return switch (paymentType.toUpperCase(Locale.ROOT)) {
            case PAYMENT_CASH -> cash && !banking && !credit;
            case PAYMENT_BANKING -> banking && !cash && !credit;
            case PAYMENT_MIXED -> cash && banking && !credit;
            case PAYMENT_CREDIT -> credit && !cash && !banking;
            default -> true;
        };
    }

    /** Kiểm tra trạng thái khớp bộ lọc — "Hoàn thành" gom cả legacy "Duyệt". */
    private boolean matchesStatus(Income income, String filterStatus) {
        if (isStatus(filterStatus, STATUS_COMPLETED)) {
            return isCompletedStatus(income.getStatus());
        }
        return isStatus(income.getStatus(), filterStatus);
    }

    /** So sánh hai chuỗi trạng thái sau khi chuẩn hóa. */
    private boolean isStatus(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    /** Trạng thái coi là hoàn thành (kể cả legacy "Duyệt"). */
    private boolean isCompletedStatus(String status) {
        return isStatus(status, STATUS_COMPLETED) || isStatus(status, STATUS_COMPLETED_LEGACY);
    }

    /** Nhãn trạng thái hiển thị — chuẩn hóa legacy về "Hoàn thành". */
    private String displayStatus(String status) {
        if (isCompletedStatus(status)) {
            return STATUS_COMPLETED;
        }
        if (isStatus(status, STATUS_CANCELLED)) {
            return STATUS_CANCELLED;
        }
        return status;
    }

    /** CSS class badge trạng thái trên giao diện. */
    private String statusCssClass(String statusName) {
        if (isCompletedStatus(statusName)) {
            return "status-completed";
        }
        if (isStatus(statusName, STATUS_CANCELLED)) {
            return "status-cancelled";
        }
        if (isStatus(statusName, STATUS_REJECTED)) {
            return "status-rejected";
        }
        if (isStatus(statusName, STATUS_PENDING)) {
            return "status-pending";
        }
        if (isStatus(statusName, STATUS_DRAFT)) {
            return "status-draft";
        }
        return "status-default";
    }

    /** Nhãn tiếng Việt loại phiếu thu từ mã nội bộ. */
    private String formatIncomeType(String typeCode) {
        if (typeCode == null) {
            return "Không rõ";
        }
        String label = IncomeTypeOptionResponse.labelOf(typeCode);
        return label.isBlank() ? typeCode : label;
    }

    /** Định dạng thời gian phiếu thu (dd/MM/yyyy HH:mm). */
    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        // Lưu bằng nowVn() — đọc lại theo UTC (cùng quy ước ReturnService).
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneOffset.UTC)
                .format(instant);
    }

    /** Chuyển {@link Instant} sang {@link LocalDate} theo quy ước UTC. */
    private LocalDate toLocalDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** Thời điểm hiện tại theo giờ VN, lưu dạng Instant UTC (giống ReturnService). */
    private Instant nowVn() {
        return LocalDateTime.now(VN_ZONE).toInstant(ZoneOffset.UTC);
    }

    /** Parse chuỗi ngày lọc (yyyy-MM-dd) — trả null nếu rỗng hoặc sai định dạng. */
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /** Kiểm tra chuỗi chứa từ khóa đã chuẩn hóa (bỏ dấu). */
    private boolean containsNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).contains(normalizedKeyword);
    }

    /** Chuẩn hóa chuỗi tìm kiếm — bỏ dấu tiếng Việt, chữ thường. */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replace("Đ", "D").replace("đ", "d");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    /** Kiểm tra dữ liệu form tạo phiếu thu trước khi lưu. */
    private void validateCreateRequest(IncomeCreateRequest request) {
        if (request.getIncomeType() == null || request.getIncomeType().isBlank()) {
            throw new IllegalArgumentException("Vui lòng chọn loại phiếu thu");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập nội dung thu");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền thu phải lớn hơn 0");
        }
        String incomeType = resolveIncomeType(request.getIncomeType());
        if (IncomeTypeOptionResponse.SUPPLIER.equals(incomeType) && request.getSupplierId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn nhà cung cấp");
        }
        if (IncomeTypeOptionResponse.SUPPLIER.equals(incomeType) && request.getReturnId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn phiếu trả hàng nhà cung cấp");
        }
        if (IncomeTypeOptionResponse.CUSTOMER.equals(incomeType) && request.getCustomerId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn khách hàng");
        }
        if (IncomeTypeOptionResponse.CUSTOMER.equals(incomeType) && request.getInvoiceId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn hóa đơn bán hàng còn nợ");
        }
        if (IncomeTypeOptionResponse.EMPLOYEE.equals(incomeType) && request.getAccountId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn người chịu trách nhiệm");
        }
        if (IncomeTypeOptionResponse.EMPLOYEE.equals(incomeType) && request.getStockAdjustmentId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn phiếu điều chỉnh kho");
        }
        if (IncomeTypeOptionResponse.SHIFT_SHORTAGE.equals(incomeType) && request.getAccountId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn người chịu trách nhiệm");
        }
        if (IncomeTypeOptionResponse.SHIFT_SHORTAGE.equals(incomeType) && request.getShiftReportOfAccountId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn báo cáo ca");
        }
        validateReferenceSelection(incomeType, request);
    }

    /** Kiểm tra chứng từ liên quan và số tiền thu theo từng loại phiếu. */
    private void validateReferenceSelection(String incomeType, IncomeCreateRequest request) {
        if (IncomeTypeOptionResponse.CUSTOMER.equals(incomeType)) {
            Invoice invoice = invoiceRepository.findById(request.getInvoiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn bán hàng"));
            if (invoice.getCustomerID() == null || !request.getCustomerId().equals(invoice.getCustomerID().getId())) {
                throw new IllegalArgumentException("Hóa đơn không thuộc khách hàng đã chọn");
            }
            if (!isDebtInvoice(invoice)) {
                throw new IllegalArgumentException("Hóa đơn không còn ở trạng thái nợ");
            }
            validateCustomerPaymentAmount(invoice, request.getAmount());
        }
        if (IncomeTypeOptionResponse.SUPPLIER.equals(incomeType)) {
            Return ret = returnRepository.findById(request.getReturnId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng nhà cung cấp"));
            if (!isApprovedSupplierReturn(ret)) {
                throw new IllegalArgumentException("Chỉ có thể thu tiền từ phiếu trả NCC đã duyệt");
            }
            if (!hasCollectibleCashFromSupplier(ret)) {
                throw new IllegalArgumentException("Phiếu trả hàng không còn khoản NCC cần hoàn");
            }
            Purchaseinvoice purchase = purchaseinvoiceRepository.findById(ret.getPurchaseID().getId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập liên quan"));
            if (purchase.getSupplierID() == null || !request.getSupplierId().equals(purchase.getSupplierID().getId())) {
                throw new IllegalArgumentException("Phiếu trả hàng không thuộc nhà cung cấp đã chọn");
            }
            validateSupplierPaymentAmount(ret, request.getAmount());
        }
        if (IncomeTypeOptionResponse.EMPLOYEE.equals(incomeType)) {
            Stockadjustment adjustment = stockadjustmentRepository.findById(request.getStockAdjustmentId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu điều chỉnh kho"));
            if (!isCompletedStockAdjustment(adjustment)) {
                throw new IllegalArgumentException("Chỉ có thể liên kết phiếu điều chỉnh đã hoàn thành");
            }
            if (!isEmployeeLiableStockAdjustment(adjustment)) {
                throw new IllegalArgumentException(
                        "Chỉ có thể liên kết phiếu hủy hàng (lỗi nhân viên) hoặc giảm theo kiểm kê");
            }
            if (linkedStockAdjustmentIds().contains(adjustment.getId())) {
                throw new IllegalArgumentException("Phiếu điều chỉnh này đã được liên kết với phiếu thu khác");
            }
            validateEmployeePaymentAmount(adjustment, request.getAmount());
        }
        if (IncomeTypeOptionResponse.SHIFT_SHORTAGE.equals(incomeType)) {
            Shiftreport shift = shiftreportRepository.findByIdWithRelations(request.getShiftReportOfAccountId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy báo cáo ca"));
            if (!isShiftWithCollectibleShortage(shift)) {
                throw new IllegalArgumentException("Báo cáo ca không còn khoản thiếu tiền mặt cần thu");
            }
            if (shift.getCashierID() == null || !request.getAccountId().equals(shift.getCashierID().getId())) {
                throw new IllegalArgumentException("Báo cáo ca không thuộc người chịu trách nhiệm đã chọn");
            }
            if (linkedShiftReportOfAccountIds().contains(shift.getId())) {
                throw new IllegalArgumentException("Báo cáo ca này đã được liên kết với phiếu thu khác");
            }
            validateShiftShortagePaymentAmount(shift, request.getAmount());
        }
    }

    /** Chuẩn hóa và kiểm tra mã loại phiếu thu từ form. */
    private String resolveIncomeType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw new IllegalArgumentException("Vui lòng chọn loại phiếu thu");
        }
        String trimmed = rawType.trim();
        if (!IncomeTypeOptionResponse.isValid(trimmed)) {
            throw new IllegalArgumentException("Loại phiếu thu không hợp lệ");
        }
        return IncomeTypeOptionResponse.codeOf(trimmed);
    }

    /** Gắn liên kết đối tượng (khách, NCC, nhân viên) theo loại phiếu. */
    private void applyPartyLinks(Income income, String incomeType, IncomeCreateRequest request) {
        income.setSupplierID(null);
        income.setCustomerID(null);
        income.setAccountID(null);

        if (IncomeTypeOptionResponse.SUPPLIER.equals(incomeType)) {
            income.setSupplierID(supplierRepository.findById(request.getSupplierId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp")));
        } else if (IncomeTypeOptionResponse.CUSTOMER.equals(incomeType)) {
            income.setCustomerID(customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khách hàng")));
        } else if (IncomeTypeOptionResponse.EMPLOYEE.equals(incomeType)) {
            income.setAccountID(accountRepository.findById(request.getAccountId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người chịu trách nhiệm")));
        } else if (IncomeTypeOptionResponse.SHIFT_SHORTAGE.equals(incomeType)) {
            income.setAccountID(accountRepository.findById(request.getAccountId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người chịu trách nhiệm")));
        }
    }

    /** Gắn liên kết chứng từ (HĐ, phiếu trả, điều chỉnh kho, báo cáo ca) theo loại phiếu. */
    private void applyReferenceLinks(Income income, String incomeType, IncomeCreateRequest request) {
        income.setInvoiceID(null);
        income.setReturnID(null);
        income.setStockAdjustmentID(null);
        income.setShiftReportOfAccountID(null);

        if (IncomeTypeOptionResponse.CUSTOMER.equals(incomeType)) {
            income.setInvoiceID(invoiceRepository.findById(request.getInvoiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn bán hàng")));
        } else if (IncomeTypeOptionResponse.SUPPLIER.equals(incomeType)) {
            income.setReturnID(returnRepository.findById(request.getReturnId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng nhà cung cấp")));
        } else if (IncomeTypeOptionResponse.EMPLOYEE.equals(incomeType)) {
            income.setStockAdjustmentID(stockadjustmentRepository.findById(request.getStockAdjustmentId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu điều chỉnh kho")));
        } else if (IncomeTypeOptionResponse.SHIFT_SHORTAGE.equals(incomeType)) {
            income.setShiftReportOfAccountID(shiftreportRepository.findById(request.getShiftReportOfAccountId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy báo cáo ca")));
        }
    }

    /** Trả {@code [paidByCash, paidByBanking]} — mặc định toàn bộ vào tiền mặt nếu chưa tách. */
    private BigDecimal[] resolveSplit(IncomeCreateRequest request) {
        BigDecimal amount = request.getAmount();
        BigDecimal cash = request.getPaidByCash();
        BigDecimal banking = request.getPaidByBanking();
        if (cash == null && banking == null) {
            return new BigDecimal[]{amount, BigDecimal.ZERO};
        }
        cash = nullToZero(cash);
        banking = nullToZero(banking);
        if (cash.add(banking).setScale(2, RoundingMode.HALF_UP)
                .compareTo(amount.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalArgumentException("Tiền mặt + chuyển khoản phải bằng tổng số tiền thu");
        }
        if (cash.compareTo(BigDecimal.ZERO) < 0 || banking.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Số tiền thanh toán không hợp lệ");
        }
        return new BigDecimal[]{cash, banking};
    }

    /** Sinh mã phiếu thu dạng PT-000001 từ id. */
    private String formatCode(Integer id) {
        if (id == null) {
            return "PT-000000";
        }
        return "PT-" + String.format("%06d", id);
    }

    /** Mã phiếu tạm trước khi lưu — cập nhật lại sau khi có id thật. */
    private String generateCode() {
        int nextId = incomeRepository.findAll().stream()
                .map(Income::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        return formatCode(nextId);
    }

    /** Trim chuỗi — trả null nếu rỗng. */
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** Null-safe — trả {@link BigDecimal#ZERO} nếu null. */
    private BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /** Hóa đơn còn nợ — trạng thái "Còn nợ" hoặc debtAmount > 0. */
    private boolean isDebtInvoice(Invoice invoice) {
        if (invoice == null) {
            return false;
        }
        if (INVOICE_STATUS_DEBT.equals(invoice.getStatus())) {
            return true;
        }
        return isPositive(invoice.getDebtAmount());
    }

    /** Phiếu trả NCC còn khoản tiền mặt phải thu. */
    private boolean hasCollectibleCashFromSupplier(Return ret) {
        return isPositive(remainingCollectibleFromSupplier(ret, accountedByReturnId()));
    }

    /**
     * Tiền thật NCC còn phải hoàn sau cấn trừ công nợ lúc duyệt ({@code offsetDebtAmount} cố định).
     */
    private BigDecimal collectibleCashFromSupplier(Return ret) {
        return nullToZero(ret != null ? ret.getTotalRefund() : null)
                .subtract(nullToZero(ret != null ? ret.getOffsetDebtAmount() : null))
                .max(BigDecimal.ZERO);
    }

    /** Số tiền NCC còn phải hoàn sau trừ các phiếu thu đã ghi nhận. */
    private BigDecimal remainingCollectibleFromSupplier(Return ret, Map<Integer, BigDecimal> accounted) {
        return collectibleCashFromSupplier(ret)
                .subtract(accounted.getOrDefault(ret != null ? ret.getId() : null, BigDecimal.ZERO))
                .max(BigDecimal.ZERO);
    }

    /** Tổng tiền đã thu qua phiếu thu SUPPLIER hoàn thành, gom theo id phiếu trả NCC. */
    private Map<Integer, BigDecimal> accountedByReturnId() {
        Map<Integer, BigDecimal> accounted = new LinkedHashMap<>();
        for (Income income : incomeRepository.findAllWithRelations()) {
            if (!isCompletedStatus(income.getStatus())) {
                continue;
            }
            if (!IncomeTypeOptionResponse.SUPPLIER.equals(resolveIncomeType(income))) {
                continue;
            }
            Return ret = income.getReturnID();
            if (ret == null || ret.getId() == null) {
                continue;
            }
            accounted.merge(ret.getId(), nullToZero(income.getAmount()), BigDecimal::add);
        }
        return accounted;
    }

    /** Kiểm tra số tiền thu không vượt số NCC còn phải hoàn trên phiếu trả. */
    private void validateSupplierPaymentAmount(Return ret, BigDecimal paymentAmount) {
        if (paymentAmount == null) {
            return;
        }
        BigDecimal remaining = remainingCollectibleFromSupplier(ret, accountedByReturnId());
        if (paymentAmount.setScale(2, RoundingMode.HALF_UP).compareTo(remaining.setScale(2, RoundingMode.HALF_UP)) > 0) {
            throw new IllegalArgumentException(
                    "Số tiền thu không được vượt quá số tiền NCC còn phải hoàn ("
                            + formatMoney(remaining) + ")");
        }
    }

    /**
     * Xác thực số tiền thu phiếu trả NCC khi hoàn thành phiếu thu.
     * {@code offsetDebtAmount} chỉ là cấn trừ công nợ lúc duyệt; tiền mặt thu theo dõi qua phiếu thu.
     */
    private void applySupplierOffsetDebtPayment(Income income) {
        if (income.getReturnID() == null || income.getAmount() == null) {
            return;
        }
        Return ret = returnRepository.findById(income.getReturnID().getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng nhà cung cấp"));
        validateSupplierPaymentAmount(ret, income.getAmount());
    }

    /** Phiếu trả hàng NCC đã duyệt (trả theo phiếu nhập, không phải trả khách). */
    private boolean isApprovedSupplierReturn(Return ret) {
        return ret != null
                && ret.getPurchaseID() != null
                && ret.getInvoiceID() == null
                && isStatus(ret.getStatus(), ReturnPurchaseStatus.APPROVED);
    }

    /** Id các phiếu điều chỉnh kho đã liên kết phiếu thu (trừ từ chối/hủy). */
    private Set<Integer> linkedStockAdjustmentIds() {
        return incomeRepository.findAllWithRelations().stream()
                .filter(income -> !isStatus(income.getStatus(), STATUS_REJECTED)
                        && !isStatus(income.getStatus(), STATUS_CANCELLED))
                .map(Income::getStockAdjustmentID)
                .filter(Objects::nonNull)
                .map(Stockadjustment::getId)
                .collect(Collectors.toSet());
    }

    /** Phiếu điều chỉnh kho đã hoàn thành (kể cả trạng thái legacy "Duyệt"). */
    private boolean isCompletedStockAdjustment(Stockadjustment adjustment) {
        if (adjustment == null || adjustment.getStatus() == null) {
            return false;
        }
        String status = adjustment.getStatus();
        return StockAdjustmentStatus.COMPLETED.equals(status)
                || STOCK_ADJUSTMENT_STATUS_COMPLETED_LEGACY.equals(status);
    }

    /** Phiếu điều chỉnh do lỗi nhân viên — hủy hàng hoặc giảm theo kiểm kê. */
    private boolean isEmployeeLiableStockAdjustment(Stockadjustment adjustment) {
        return adjustment != null
                && adjustment.getAdjustmentType() != null
                && EMPLOYEE_LIABLE_ADJUSTMENT_TYPES.contains(adjustment.getAdjustmentType());
    }

    /** Giá trị đền bù đề xuất theo giá bán niêm yết — cùng công thức với {@code StockadjustmentService}. */
    private Map<Integer, BigDecimal> reimbursementByAdjustmentIds(Set<Integer> adjustmentIds) {
        if (adjustmentIds == null || adjustmentIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, BigDecimal> totals = new LinkedHashMap<>();
        for (Stockadjustmentdetail detail : stockadjustmentdetailRepository.findAllWithRelations()) {
            Stockadjustment adjustment = detail.getStockAdjustmentID();
            if (adjustment == null || adjustment.getId() == null
                    || !adjustmentIds.contains(adjustment.getId())
                    || !isEmployeeLiableStockAdjustment(adjustment)) {
                continue;
            }
            Productunit unit = detail.getProductUnitID();
            BigDecimal sellPrice = unit != null && unit.getSellPrice() != null
                    ? unit.getSellPrice() : BigDecimal.ZERO;
            int qty = detail.getQuantity() != null ? detail.getQuantity() : 0;
            totals.merge(adjustment.getId(), sellPrice.multiply(BigDecimal.valueOf(qty)), BigDecimal::add);
        }
        return totals;
    }

    /** Giá trị đền bù của một phiếu điều chỉnh kho. */
    private BigDecimal reimbursementValueFor(Stockadjustment adjustment) {
        if (adjustment == null || adjustment.getId() == null) {
            return BigDecimal.ZERO;
        }
        return reimbursementByAdjustmentIds(Set.of(adjustment.getId()))
                .getOrDefault(adjustment.getId(), BigDecimal.ZERO);
    }

    /** Phiếu thu đền bù phải thu đúng một lần, bằng giá trị đền bù đề xuất. */
    private void validateEmployeePaymentAmount(Stockadjustment adjustment, BigDecimal paymentAmount) {
        if (paymentAmount == null) {
            return;
        }
        BigDecimal required = reimbursementValueFor(adjustment);
        BigDecimal normalizedPayment = paymentAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal normalizedRequired = required.setScale(2, RoundingMode.HALF_UP);
        if (normalizedRequired.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Phiếu điều chỉnh không có giá trị đền bù");
        }
        if (normalizedPayment.compareTo(normalizedRequired) != 0) {
            throw new IllegalArgumentException(
                    "Phiếu thu đền bù phải thu đủ một lần ("
                            + formatMoney(required) + ")");
        }
    }

    /** Phiếu thu thất thoát ca phải thu đúng một lần, bằng số tiền thiếu mặt. */
    private void validateShiftShortagePaymentAmount(Shiftreport shift, BigDecimal paymentAmount) {
        if (paymentAmount == null) {
            return;
        }
        BigDecimal required = collectibleShortageAmount(shift);
        BigDecimal normalizedPayment = paymentAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal normalizedRequired = required.setScale(2, RoundingMode.HALF_UP);
        if (normalizedRequired.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Báo cáo ca không có khoản thiếu tiền mặt");
        }
        if (normalizedPayment.compareTo(normalizedRequired) != 0) {
            throw new IllegalArgumentException(
                    "Phiếu thu thất thoát ca phải thu đủ một lần ("
                            + formatMoney(required) + ")");
        }
    }

    /** Báo cáo ca đã kết (không nháp) và còn khoản thiếu tiền mặt cần thu. */
    private boolean isShiftWithCollectibleShortage(Shiftreport shift) {
        if (shift == null || shift.getStatus() == null) {
            return false;
        }
        if (isStatus(shift.getStatus(), ShiftReportStatus.DRAFT)) {
            return false;
        }
        return isPositive(collectibleShortageAmount(shift));
    }

    /** {@code cashDiscrepancy < 0} nghĩa là thiếu tiền mặt — số thu = giá trị tuyệt đối. */
    private BigDecimal collectibleShortageAmount(Shiftreport shift) {
        if (shift == null || shift.getCashDiscrepancy() == null) {
            return BigDecimal.ZERO;
        }
        return shift.getCashDiscrepancy().compareTo(BigDecimal.ZERO) < 0
                ? shift.getCashDiscrepancy().abs()
                : BigDecimal.ZERO;
    }

    /** Id các báo cáo ca đã liên kết phiếu thu thất thoát (trừ từ chối/hủy). */
    private Set<Integer> linkedShiftReportOfAccountIds() {
        return incomeRepository.findAllWithRelations().stream()
                .filter(income -> !isStatus(income.getStatus(), STATUS_REJECTED)
                        && !isStatus(income.getStatus(), STATUS_CANCELLED))
                .map(Income::getShiftReportOfAccountID)
                .filter(Objects::nonNull)
                .map(Shiftreport::getId)
                .collect(Collectors.toSet());
    }

    /** Ngày giờ hiển thị trên dropdown chọn báo cáo ca. */
    private String formatShiftReferenceDate(Shiftreport shift) {
        if (shift == null) {
            return "";
        }
        String datePart = shift.getShiftDate() != null
                ? shift.getShiftDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                : "";
        String timePart = formatInstant(shift.getEndTime() != null ? shift.getEndTime() : shift.getStartTime());
        if (datePart.isBlank()) {
            return timePart;
        }
        if (timePart.isBlank()) {
            return datePart;
        }
        return datePart + " " + timePart;
    }

    /** Null-safe — trả chuỗi rỗng nếu null. */
    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    /** Kiểm tra số tiền lớn hơn 0. */
    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /** Kiểm tra số tiền thu không vượt số nợ trên hóa đơn bán. */
    private void validateCustomerPaymentAmount(Invoice invoice, BigDecimal paymentAmount) {
        if (paymentAmount == null) {
            return;
        }
        BigDecimal debt = nullToZero(invoice.getDebtAmount());
        if (paymentAmount.setScale(2, RoundingMode.HALF_UP).compareTo(debt.setScale(2, RoundingMode.HALF_UP)) > 0) {
            throw new IllegalArgumentException(
                    "Số tiền thu không được vượt quá số tiền nợ (" + formatMoney(debt) + ")");
        }
    }

    /** Hoàn lại công nợ hóa đơn bán khi hủy phiếu thu nợ khách đã hoàn thành. */
    private void reverseCustomerDebtPayment(Income income) {
        if (income.getInvoiceID() == null || income.getAmount() == null) {
            return;
        }
        Invoice invoice = invoiceRepository.findById(income.getInvoiceID().getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn bán hàng"));

        BigDecimal payment = income.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal cash = nullToZero(income.getPaidByCash());
        BigDecimal banking = nullToZero(income.getPaidByBanking());

        invoice.setPaidByCash(nullToZero(invoice.getPaidByCash()).subtract(cash).max(BigDecimal.ZERO));
        invoice.setPaidByBanking(nullToZero(invoice.getPaidByBanking()).subtract(banking).max(BigDecimal.ZERO));
        BigDecimal newDebt = nullToZero(invoice.getDebtAmount()).add(payment);
        invoice.setDebtAmount(newDebt);
        invoice.setStatus(newDebt.compareTo(BigDecimal.ZERO) > 0 ? INVOICE_STATUS_DEBT : INVOICE_STATUS_COMPLETED);
        invoiceService.persistInvoice(invoice);
    }

    /** Giảm công nợ hóa đơn bán khi hoàn thành phiếu thu nợ khách. */
    private void applyCustomerDebtPayment(Income income, BigDecimal paidByCash, BigDecimal paidByBanking) {
        if (income.getInvoiceID() == null || income.getAmount() == null) {
            return;
        }
        Invoice invoice = invoiceRepository.findById(income.getInvoiceID().getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn bán hàng"));
        validateCustomerPaymentAmount(invoice, income.getAmount());

        BigDecimal payment = income.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal currentDebt = nullToZero(invoice.getDebtAmount());
        BigDecimal newDebt = currentDebt.subtract(payment);
        if (newDebt.compareTo(BigDecimal.ZERO) < 0) {
            newDebt = BigDecimal.ZERO;
        }

        invoice.setPaidByCash(nullToZero(invoice.getPaidByCash()).add(nullToZero(paidByCash)));
        invoice.setPaidByBanking(nullToZero(invoice.getPaidByBanking()).add(nullToZero(paidByBanking)));
        invoice.setDebtAmount(newDebt);
        invoice.setStatus(newDebt.compareTo(BigDecimal.ZERO) > 0 ? INVOICE_STATUS_DEBT : INVOICE_STATUS_COMPLETED);
        invoiceService.persistInvoice(invoice);
    }

    /** Định dạng ngày giờ hóa đơn bán cho dropdown chứng từ. */
    private String formatInvoiceDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    /** Định dạng số tiền kèm đơn vị "đ" cho hiển thị. */
    private String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "0đ";
        }
        return amount.stripTrailingZeros().toPlainString() + "đ";
    }
}
