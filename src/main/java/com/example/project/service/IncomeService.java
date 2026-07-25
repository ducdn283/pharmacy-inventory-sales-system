package com.example.project.service;

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
import com.example.project.entity.Stockadjustment;
import com.example.project.entity.Supplier;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.CustomerRepository;
import com.example.project.repository.IncomeRepository;
import com.example.project.repository.InvoiceRepository;
import com.example.project.repository.PurchaseinvoiceRepository;
import com.example.project.repository.ReturnRepository;
import com.example.project.repository.StockadjustmentRepository;
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

@Service
public class IncomeService {

    private static final String STATUS_DRAFT = "Nháp";
    private static final String STATUS_PENDING = "Chờ duyệt";
    private static final String STATUS_COMPLETED = "Hoàn thành";
    private static final String STATUS_COMPLETED_LEGACY = "Duyệt";
    private static final String STATUS_REJECTED = "Từ chối";

    private static final String INVOICE_STATUS_DEBT = "Còn nợ";
    private static final String SUPPLIER_RETURN_STATUS_APPROVED = "Đã duyệt";
    private static final String STOCK_ADJUSTMENT_STATUS_APPROVED = "Duyệt";

    private static final String PAYMENT_CASH = "CASH";
    private static final String PAYMENT_BANKING = "BANKING";
    private static final String PAYMENT_MIXED = "MIXED";

    private final IncomeRepository incomeRepository;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final InvoiceRepository invoiceRepository;
    private final ReturnRepository returnRepository;
    private final PurchaseinvoiceRepository purchaseinvoiceRepository;
    private final StockadjustmentRepository stockadjustmentRepository;
    // Lazily opens/reuses the collector's shift the moment an income is actually recorded — same
    // hook as InvoiceService/ReturnService (phát sinh giao dịch là tạo báo cáo ca). Income is
    // only creatable by Owner/Pharmacist (see IncomeController routes), so this never opens a shift
    // for an Accountant.
    private final ShiftreportService shiftreportService;

    public IncomeService(IncomeRepository incomeRepository,
                         AccountRepository accountRepository,
                         CustomerRepository customerRepository,
                         SupplierRepository supplierRepository,
                         InvoiceRepository invoiceRepository,
                         ReturnRepository returnRepository,
                         PurchaseinvoiceRepository purchaseinvoiceRepository,
                         StockadjustmentRepository stockadjustmentRepository,
                         ShiftreportService shiftreportService) {
        this.incomeRepository = incomeRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
        this.invoiceRepository = invoiceRepository;
        this.returnRepository = returnRepository;
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.stockadjustmentRepository = stockadjustmentRepository;
        this.shiftreportService = shiftreportService;
    }

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

    @Transactional(readOnly = true)
    public List<String> listStatuses() {
        return List.of(STATUS_DRAFT, STATUS_PENDING, STATUS_COMPLETED, STATUS_REJECTED);
    }

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

    public List<IncomeTypeOptionResponse> listIncomeTypes() {
        return IncomeTypeOptionResponse.all();
    }

    public Map<String, String> paymentTypeLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PAYMENT_CASH, "Tiền mặt");
        labels.put(PAYMENT_BANKING, "Chuyển khoản");
        labels.put(PAYMENT_MIXED, "TM + CK");
        return labels;
    }

    @Transactional(readOnly = true)
    public List<CustomerOptionResponse> listCustomers() {
        return customerRepository.findAll().stream()
                .sorted(Comparator.comparing(Customer::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(customer -> new CustomerOptionResponse(
                        customer.getId(), customer.getName(), customer.getPhoneNumber(), customer.getCustomerType()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerOptionResponse> listSuppliers() {
        return supplierRepository.findAll().stream()
                .sorted(Comparator.comparing(Supplier::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(supplier -> new CustomerOptionResponse(supplier.getId(), supplier.getName(), null, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerOptionResponse> listEmployees() {
        return accountRepository.findAll().stream()
                .filter(account -> Boolean.TRUE.equals(account.getStatus()))
                .sorted(Comparator.comparing(Account::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(account -> new CustomerOptionResponse(account.getId(), account.getName(), null, null))
                .toList();
    }

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

    @Transactional(readOnly = true)
    public List<IncomeReferenceOptionResponse> listSupplierReturns(Integer supplierId) {
        if (supplierId == null) {
            return List.of();
        }
        Map<Integer, Purchaseinvoice> purchasesById = purchaseinvoiceRepository.findAllWithRelations().stream()
                .collect(Collectors.toMap(Purchaseinvoice::getId, purchase -> purchase, (a, b) -> a));
        Set<Integer> linkedReturnIds = linkedReturnIds();

        return returnRepository.findAll().stream()
                .filter(ret -> ret.getPurchaseID() != null && ret.getInvoiceID() == null)
                .filter(ret -> SUPPLIER_RETURN_STATUS_APPROVED.equals(ret.getStatus()))
                .filter(ret -> ret.getIncomeID() == null && !linkedReturnIds.contains(ret.getId()))
                .filter(this::hasCashRefund)
                .filter(ret -> {
                    Purchaseinvoice purchase = purchasesById.get(ret.getPurchaseID().getId());
                    return purchase != null && purchase.getSupplierID() != null
                            && supplierId.equals(purchase.getSupplierID().getId());
                })
                .sorted(Comparator.comparing(Return::getReturnDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Return::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ret -> {
                    Purchaseinvoice purchase = purchasesById.get(ret.getPurchaseID().getId());
                    String purchaseCode = purchase != null ? purchase.getPurchaseInvoiceCode() : "—";
                    return new IncomeReferenceOptionResponse(
                            ret.getId(),
                            ret.getReturnCode(),
                            formatInstant(ret.getReturnDate()),
                            cashRefundAmount(ret),
                            "Phiếu nhập: " + purchaseCode);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<IncomeReferenceOptionResponse> listStockAdjustments(Integer accountId) {
        if (accountId == null) {
            return List.of();
        }
        Set<Integer> linkedAdjustmentIds = linkedStockAdjustmentIds();

        return stockadjustmentRepository.findAllWithRelations().stream()
                .filter(adjustment -> STOCK_ADJUSTMENT_STATUS_APPROVED.equals(adjustment.getStatus()))
                .filter(adjustment -> adjustment.getCreatedBy() != null
                        && accountId.equals(adjustment.getCreatedBy().getId()))
                .filter(adjustment -> !linkedAdjustmentIds.contains(adjustment.getId()))
                .sorted(Comparator.comparing(Stockadjustment::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Stockadjustment::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(adjustment -> new IncomeReferenceOptionResponse(
                        adjustment.getId(),
                        adjustment.getStockAdjustmentCode(),
                        formatInstant(adjustment.getDate()),
                        null,
                        adjustment.getReason()))
                .toList();
    }

    @Transactional(readOnly = true)
    public IncomeDetailResponse getDetail(Integer incomeId) {
        Income income = incomeRepository.findByIdWithRelations(incomeId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu thu"));
        return toDetail(income);
    }

    /**
     * Creates a manual income slip. When {@code asDraft} is true it is saved as {@link #STATUS_DRAFT};
     * otherwise the Owner's slip is auto-completed and anyone else's goes to {@link #STATUS_PENDING}.
     */
    @Transactional
    public Integer createIncome(IncomeCreateRequest request, Integer currentAccountId, boolean isOwner,
                                boolean asDraft) {
        validateCreateRequest(request);

        Account applicant = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        String incomeTypeCode = resolveIncomeType(request.getIncomeType());
        BigDecimal[] split = resolveSplit(request);

        Income income = new Income();
        income.setApplicantID(applicant);
        income.setIncomeType(IncomeTypeOptionResponse.storageLabelOf(incomeTypeCode));
        income.setDate(Instant.now());
        income.setReason(request.getReason() != null ? request.getReason().trim() : "");
        income.setAmount(request.getAmount());
        income.setPaidByCash(split[0]);
        income.setPaidByBanking(split[1]);
        income.setNote(trimToNull(request.getNote()));
        applyPartyLinks(income, incomeTypeCode, request);
        applyReferenceLinks(income, incomeTypeCode, request);

        if (asDraft) {
            income.setStatus(STATUS_DRAFT);
        } else if (isOwner) {
            income.setStatus(STATUS_COMPLETED);
        } else {
            income.setStatus(STATUS_PENDING);
        }

        // A submitted income is a real counter transaction (cash/banking physically received) →
        // attach the collector's open shift. Drafts are not transactions yet, so they stay
        // unattached until they are actually sent (no submit flow exists for drafts yet).
        if (!asDraft) {
            income.setShiftReportID(shiftreportService.ensureOpenShiftFor(currentAccountId));
        }

        income.setIncomeCode(generateCode());
        Income saved = incomeRepository.save(income);
        saved.setIncomeCode(formatCode(saved.getId()));
        saved = incomeRepository.save(saved);
        linkReturnIncome(saved, incomeTypeCode, request.getReturnId());
        return saved.getId();
    }

    private void linkReturnIncome(Income saved, String incomeType, Integer returnId) {
        if (!IncomeTypeOptionResponse.SUPPLIER.equals(incomeType) || returnId == null) {
            return;
        }
        Return ret = returnRepository.findById(returnId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng nhà cung cấp"));
        ret.setIncomeID(saved);
        returnRepository.save(ret);
    }

    @Transactional(readOnly = true)
    public long countAll() {
        return incomeRepository.count();
    }

    @Transactional(readOnly = true)
    public long countToday() {
        LocalDate today = LocalDate.now();
        return incomeRepository.findAll().stream()
                .filter(income -> income.getDate() != null && toLocalDate(income.getDate()).equals(today))
                .count();
    }

    @Transactional(readOnly = true)
    public BigDecimal sumTodayAmount() {
        LocalDate today = LocalDate.now();
        return incomeRepository.findAll().stream()
                .filter(income -> income.getDate() != null && toLocalDate(income.getDate()).equals(today))
                .map(Income::getAmount)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return countByStatus(STATUS_PENDING);
    }

    @Transactional(readOnly = true)
    public BigDecimal sumPendingAmount() {
        return sumAmountByStatus(STATUS_PENDING);
    }

    @Transactional(readOnly = true)
    public long countApproved() {
        return countCompleted();
    }

    @Transactional(readOnly = true)
    public BigDecimal sumApprovedAmount() {
        return sumCompletedAmount();
    }

    private long countByStatus(String status) {
        return incomeRepository.findAll().stream()
                .filter(income -> isStatus(income.getStatus(), status))
                .count();
    }

    private BigDecimal sumAmountByStatus(String status) {
        return incomeRepository.findAll().stream()
                .filter(income -> isStatus(income.getStatus(), status))
                .map(Income::getAmount)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long countCompleted() {
        return incomeRepository.findAll().stream()
                .filter(income -> isCompletedStatus(income.getStatus()))
                .count();
    }

    private BigDecimal sumCompletedAmount() {
        return incomeRepository.findAll().stream()
                .filter(income -> isCompletedStatus(income.getStatus()))
                .map(Income::getAmount)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

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
                paymentDisplay(income.getPaidByCash(), income.getPaidByBanking()),
                statusName,
                statusCssClass(statusName));
    }

    private IncomeDetailResponse toDetail(Income income) {
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
            case IncomeTypeOptionResponse.EMPLOYEE -> "Nhân viên";
            default -> "—";
        };

        String referenceTypeDisplay = null;
        Integer invoiceId = null;
        Integer returnId = null;
        Integer stockAdjustmentId = null;
        if (income.getInvoiceID() != null) {
            referenceTypeDisplay = "Hóa đơn bán hàng";
            invoiceId = income.getInvoiceID().getId();
        } else if (income.getReturnID() != null) {
            referenceTypeDisplay = "Phiếu trả hàng NCC";
            returnId = income.getReturnID().getId();
        } else if (income.getStockAdjustmentID() != null) {
            referenceTypeDisplay = "Phiếu điều chỉnh kho";
            stockAdjustmentId = income.getStockAdjustmentID().getId();
        }

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
                paymentDisplay(income.getPaidByCash(), income.getPaidByBanking()),
                statusName,
                statusCssClass(statusName),
                partyTypeDisplay,
                partyName,
                referenceTypeDisplay,
                referenceCode(income),
                invoiceId,
                returnId,
                stockAdjustmentId,
                income.getNote());
    }

    private String displayReason(Income income) {
        if (income.getReason() == null || income.getReason().isBlank()) {
            return "—";
        }
        return income.getReason();
    }

    /** Internal type code; DB may store the Vietnamese label or a legacy English code. */
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
        if (income.getAccountID() != null) {
            return IncomeTypeOptionResponse.EMPLOYEE;
        }
        return IncomeTypeOptionResponse.OTHER;
    }

    private String referenceCode(Income income) {
        if (income.getInvoiceID() != null && income.getInvoiceID().getInvoiceNumber() != null) {
            return income.getInvoiceID().getInvoiceNumber();
        }
        if (income.getReturnID() != null && income.getReturnID().getReturnCode() != null) {
            return income.getReturnID().getReturnCode();
        }
        if (income.getShiftReportID() != null && income.getShiftReportID().getShiftReportCode() != null) {
            return income.getShiftReportID().getShiftReportCode();
        }
        if (income.getStockAdjustmentID() != null && income.getStockAdjustmentID().getStockAdjustmentCode() != null) {
            return income.getStockAdjustmentID().getStockAdjustmentCode();
        }
        return "—";
    }

    private String paymentDisplay(BigDecimal paidByCash, BigDecimal paidByBanking) {
        boolean hasCash = paidByCash != null && paidByCash.compareTo(BigDecimal.ZERO) > 0;
        boolean hasBanking = paidByBanking != null && paidByBanking.compareTo(BigDecimal.ZERO) > 0;
        if (hasCash && hasBanking) {
            return "TM + CK";
        }
        if (hasBanking) {
            return "Chuyển khoản";
        }
        if (hasCash) {
            return "Tiền mặt";
        }
        return "—";
    }

    private boolean matchesKeyword(Income income, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        return containsNormalized(income.getIncomeCode(), normalizedKeyword)
                || containsNormalized(income.getReason(), normalizedKeyword)
                || containsNormalized(formatIncomeType(resolveIncomeType(income)), normalizedKeyword)
                || containsNormalized(income.getStatus(), normalizedKeyword)
                || containsNormalized(referenceCode(income), normalizedKeyword)
                || containsNormalized(
                        income.getApplicantID() != null ? income.getApplicantID().getName() : null,
                        normalizedKeyword);
    }

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

    private boolean matchesApplicant(Income income, Integer applicantId) {
        return income.getApplicantID() != null && applicantId.equals(income.getApplicantID().getId());
    }

    private boolean matchesPaymentType(Income income, String paymentType) {
        if (paymentType == null || paymentType.isBlank()) {
            return true;
        }
        boolean cash = isPositive(income.getPaidByCash());
        boolean banking = isPositive(income.getPaidByBanking());
        return switch (paymentType.toUpperCase(Locale.ROOT)) {
            case PAYMENT_CASH -> cash && !banking;
            case PAYMENT_BANKING -> banking && !cash;
            case PAYMENT_MIXED -> cash && banking;
            default -> true;
        };
    }

    private boolean matchesStatus(Income income, String filterStatus) {
        if (isStatus(filterStatus, STATUS_COMPLETED)) {
            return isCompletedStatus(income.getStatus());
        }
        return isStatus(income.getStatus(), filterStatus);
    }

    private boolean isStatus(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    private boolean isCompletedStatus(String status) {
        return isStatus(status, STATUS_COMPLETED) || isStatus(status, STATUS_COMPLETED_LEGACY);
    }

    private String displayStatus(String status) {
        if (isCompletedStatus(status)) {
            return STATUS_COMPLETED;
        }
        return status;
    }

    private String statusCssClass(String statusName) {
        if (isCompletedStatus(statusName)) {
            return "status-completed";
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

    private String formatIncomeType(String typeCode) {
        if (typeCode == null) {
            return "Không rõ";
        }
        String label = IncomeTypeOptionResponse.labelOf(typeCode);
        return label.isBlank() ? typeCode : label;
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
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private boolean containsNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).contains(normalizedKeyword);
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
            throw new IllegalArgumentException("Vui lòng chọn nhân viên");
        }
        if (IncomeTypeOptionResponse.EMPLOYEE.equals(incomeType) && request.getStockAdjustmentId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn phiếu điều chỉnh kho");
        }
        validateReferenceSelection(incomeType, request);
    }

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
        }
        if (IncomeTypeOptionResponse.SUPPLIER.equals(incomeType)) {
            Return ret = returnRepository.findById(request.getReturnId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng nhà cung cấp"));
            if (ret.getPurchaseID() == null || ret.getInvoiceID() != null) {
                throw new IllegalArgumentException("Phiếu trả hàng không hợp lệ");
            }
            if (!SUPPLIER_RETURN_STATUS_APPROVED.equals(ret.getStatus())) {
                throw new IllegalArgumentException("Chỉ có thể thu tiền từ phiếu trả NCC đã duyệt");
            }
            if (ret.getIncomeID() != null || linkedReturnIds().contains(ret.getId())) {
                throw new IllegalArgumentException("Phiếu trả hàng này đã được ghi nhận thu tiền");
            }
            Purchaseinvoice purchase = purchaseinvoiceRepository.findById(ret.getPurchaseID().getId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập liên quan"));
            if (purchase.getSupplierID() == null || !request.getSupplierId().equals(purchase.getSupplierID().getId())) {
                throw new IllegalArgumentException("Phiếu trả hàng không thuộc nhà cung cấp đã chọn");
            }
            if (!hasCashRefund(ret)) {
                throw new IllegalArgumentException("Phiếu trả hàng không có khoản tiền NCC hoàn lại");
            }
        }
        if (IncomeTypeOptionResponse.EMPLOYEE.equals(incomeType)) {
            Stockadjustment adjustment = stockadjustmentRepository.findById(request.getStockAdjustmentId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu điều chỉnh kho"));
            if (!STOCK_ADJUSTMENT_STATUS_APPROVED.equals(adjustment.getStatus())) {
                throw new IllegalArgumentException("Chỉ có thể liên kết phiếu điều chỉnh đã duyệt");
            }
            if (adjustment.getCreatedBy() == null
                    || !request.getAccountId().equals(adjustment.getCreatedBy().getId())) {
                throw new IllegalArgumentException("Phiếu điều chỉnh không thuộc nhân viên đã chọn");
            }
            if (linkedStockAdjustmentIds().contains(adjustment.getId())) {
                throw new IllegalArgumentException("Phiếu điều chỉnh này đã được liên kết với phiếu thu khác");
            }
        }
    }

    private String resolveIncomeType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw new IllegalArgumentException("Vui lòng chọn loại phiếu thu");
        }
        String type = rawType.trim().toUpperCase(Locale.ROOT);
        if (!IncomeTypeOptionResponse.isValid(type)) {
            throw new IllegalArgumentException("Loại phiếu thu không hợp lệ");
        }
        return type;
    }

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
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên")));
        }
    }

    private void applyReferenceLinks(Income income, String incomeType, IncomeCreateRequest request) {
        income.setInvoiceID(null);
        income.setReturnID(null);
        income.setStockAdjustmentID(null);

        if (IncomeTypeOptionResponse.CUSTOMER.equals(incomeType)) {
            income.setInvoiceID(invoiceRepository.findById(request.getInvoiceId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn bán hàng")));
        } else if (IncomeTypeOptionResponse.SUPPLIER.equals(incomeType)) {
            income.setReturnID(returnRepository.findById(request.getReturnId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng nhà cung cấp")));
        } else if (IncomeTypeOptionResponse.EMPLOYEE.equals(incomeType)) {
            income.setStockAdjustmentID(stockadjustmentRepository.findById(request.getStockAdjustmentId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu điều chỉnh kho")));
        }
    }

    /** Returns {@code [paidByCash, paidByBanking]}, defaulting an unsplit amount entirely to cash. */
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

    private String formatCode(Integer id) {
        if (id == null) {
            return "PT-000000";
        }
        return "PT-" + String.format("%06d", id);
    }

    private String generateCode() {
        int nextId = incomeRepository.findAll().stream()
                .map(Income::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        return formatCode(nextId);
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

    private boolean isDebtInvoice(Invoice invoice) {
        if (invoice == null) {
            return false;
        }
        if (INVOICE_STATUS_DEBT.equals(invoice.getStatus())) {
            return true;
        }
        return isPositive(invoice.getDebtAmount());
    }

    private boolean hasCashRefund(Return ret) {
        return cashRefundAmount(ret).compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal cashRefundAmount(Return ret) {
        return nullToZero(ret.getRefundCash()).add(nullToZero(ret.getRefundBanking()));
    }

    private Set<Integer> linkedReturnIds() {
        return incomeRepository.findAllWithRelations().stream()
                .map(Income::getReturnID)
                .filter(Objects::nonNull)
                .map(Return::getId)
                .collect(Collectors.toSet());
    }

    private Set<Integer> linkedStockAdjustmentIds() {
        return incomeRepository.findAllWithRelations().stream()
                .map(Income::getStockAdjustmentID)
                .filter(Objects::nonNull)
                .map(Stockadjustment::getId)
                .collect(Collectors.toSet());
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private String formatInvoiceDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    private String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "0đ";
        }
        return amount.stripTrailingZeros().toPlainString() + "đ";
    }
}
