package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ReturnPurchaseStatus;
import com.example.project.constant.ReturnStatus;
import com.example.project.dto.response.DebtListItemResponse;
import com.example.project.dto.response.DebtSummaryResponse;
import com.example.project.dto.response.PayableDetailResponse;
import com.example.project.dto.response.PayableLineResponse;
import com.example.project.dto.response.ReceivableDetailResponse;
import com.example.project.dto.response.ReceivableLineResponse;
import com.example.project.entity.Customer;
import com.example.project.entity.Expense;
import com.example.project.entity.Invoice;
import com.example.project.entity.Purchaseinvoice;
import com.example.project.entity.Return;
import com.example.project.repository.CustomerRepository;
import com.example.project.repository.ExpenseRepository;
import com.example.project.repository.InvoiceRepository;
import com.example.project.repository.PurchaseinvoiceRepository;
import com.example.project.repository.ReturnRepository;
import com.example.project.repository.SupplierRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class DebtService {

    private static final String INVOICE_STATUS_DEBT = "Còn nợ";
    private static final String PARTY_CUSTOMER = "CUSTOMER";
    private static final String PARTY_SUPPLIER = "SUPPLIER";
    /** Synthetic key for walk-in sales whose invoice has no {@code customerID}. */
    private static final Integer WALK_IN_CUSTOMER_KEY = 0;
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final InvoiceRepository invoiceRepository;
    private final ReturnRepository returnRepository;
    private final PurchaseinvoiceRepository purchaseinvoiceRepository;
    private final ExpenseRepository expenseRepository;
    private final PurchaseinvoiceService purchaseinvoiceService;
    private final IncomeService incomeService;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;

    public DebtService(InvoiceRepository invoiceRepository,
                       ReturnRepository returnRepository,
                       PurchaseinvoiceRepository purchaseinvoiceRepository,
                       ExpenseRepository expenseRepository,
                       PurchaseinvoiceService purchaseinvoiceService,
                       IncomeService incomeService,
                       CustomerRepository customerRepository,
                       SupplierRepository supplierRepository) {
        this.invoiceRepository = invoiceRepository;
        this.returnRepository = returnRepository;
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.expenseRepository = expenseRepository;
        this.purchaseinvoiceService = purchaseinvoiceService;
        this.incomeService = incomeService;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
    }

    public Map<String, String> partyTypeLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PARTY_CUSTOMER, "Khách hàng");
        labels.put(PARTY_SUPPLIER, "Nhà cung cấp");
        return labels;
    }

    /**
     * Ghi nhận phần bù trừ công nợ lên phiếu nhập khi duyệt phiếu trả NCC
     * ({@code ReturnPurchaseService#applyDebtOffset}). Dùng {@link PurchaseinvoiceService#applyPayment}
     * để {@code paid} và {@code status} luôn đồng bộ — mọi chỗ ghi {@code paid} sau khi tạo phiếu đều
     * phải cập nhật {@code status} trong cùng transaction (xem javadoc {@code resolveDisplayStatus}).
     */
    @Transactional
    public void recordPurchaseDebtOffset(Integer purchaseId, BigDecimal offset) {
        if (purchaseId == null || offset == null || offset.signum() <= 0) {
            return;
        }
        purchaseinvoiceService.applyPayment(purchaseId, offset);
    }

    @Transactional(readOnly = true)
    public Page<DebtListItemResponse> list(String search, String partyType, Pageable pageable) {
        String normalizedKeyword = normalize(search);
        String normalizedPartyType = partyType == null ? "" : partyType.trim();

        List<DebtListItemResponse> filtered = buildAllRows().stream()
                .filter(row -> normalizedPartyType.isEmpty()
                        || normalizedPartyType.equals(row.getPartyType()))
                .filter(row -> matchesKeyword(row, normalizedKeyword))
                .filter(row -> hasOutstandingBalance(row))
                .sorted(Comparator
                        .comparing(DebtListItemResponse::getName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(DebtListItemResponse::getPartyType))
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<DebtListItemResponse> content = start >= filtered.size() ? List.of() : filtered.subList(start, end);
        return new PageImpl<>(content, pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public DebtSummaryResponse summarize() {
        List<DebtListItemResponse> rows = buildAllRows().stream()
                .filter(this::hasOutstandingBalance)
                .toList();

        BigDecimal customerReceivable = rows.stream()
                .filter(row -> PARTY_CUSTOMER.equals(row.getPartyType()))
                .map(DebtListItemResponse::getReceivableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal supplierReceivable = rows.stream()
                .filter(row -> PARTY_SUPPLIER.equals(row.getPartyType()))
                .map(DebtListItemResponse::getReceivableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal customerPayable = rows.stream()
                .filter(row -> PARTY_CUSTOMER.equals(row.getPartyType()))
                .map(DebtListItemResponse::getPayableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal supplierPayable = rows.stream()
                .filter(row -> PARTY_SUPPLIER.equals(row.getPartyType()))
                .map(DebtListItemResponse::getPayableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DebtSummaryResponse(
                rows.size(),
                customerReceivable.add(supplierReceivable),
                customerReceivable,
                supplierReceivable,
                customerPayable.add(supplierPayable),
                customerPayable,
                supplierPayable
        );
    }

    @Transactional(readOnly = true)
    public ReceivableDetailResponse getReceivableDetail(String partyType, Integer entityId) {
        if (entityId == null) {
            throw new IllegalArgumentException("Không tìm thấy đối tượng công nợ");
        }
        String normalizedPartyType = partyType == null ? "" : partyType.trim().toUpperCase(Locale.ROOT);
        if (PARTY_CUSTOMER.equals(normalizedPartyType)) {
            return getCustomerReceivableDetail(entityId);
        }
        if (PARTY_SUPPLIER.equals(normalizedPartyType)) {
            return getSupplierReceivableDetail(entityId);
        }
        throw new IllegalArgumentException("Loại đối tượng không hợp lệ");
    }

    @Transactional(readOnly = true)
    public PayableDetailResponse getPayableDetail(String partyType, Integer entityId) {
        if (entityId == null) {
            throw new IllegalArgumentException("Không tìm thấy đối tượng công nợ");
        }
        String normalizedPartyType = partyType == null ? "" : partyType.trim().toUpperCase(Locale.ROOT);
        if (PARTY_CUSTOMER.equals(normalizedPartyType)) {
            return getCustomerPayableDetail(entityId);
        }
        if (PARTY_SUPPLIER.equals(normalizedPartyType)) {
            return getSupplierPayableDetail(entityId);
        }
        throw new IllegalArgumentException("Loại đối tượng không hợp lệ");
    }

    private ReceivableDetailResponse getCustomerReceivableDetail(Integer customerId) {
        String name = customerRepository.findById(customerId)
                .map(customer -> customer.getName())
                .orElse("—");

        List<ReceivableLineResponse> lines = invoiceRepository.findAllWithRelations().stream()
                .filter(invoice -> invoice.getCustomerID() != null
                        && customerId.equals(invoice.getCustomerID().getId()))
                .filter(this::isDebtInvoice)
                .sorted(Comparator.comparing(Invoice::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Invoice::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(invoice -> new ReceivableLineResponse(
                        invoice.getId(),
                        invoice.getInvoiceNumber(),
                        formatInvoiceDate(invoice.getDate()),
                        nullToZero(invoice.getDebtAmount()),
                        "Tổng HĐ: " + formatMoney(invoice.getTotal())))
                .toList();

        BigDecimal total = lines.stream()
                .map(ReceivableLineResponse::getReceivableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ReceivableDetailResponse(
                customerId,
                name,
                PARTY_CUSTOMER,
                partyTypeLabels().get(PARTY_CUSTOMER),
                total,
                lines
        );
    }

    private ReceivableDetailResponse getSupplierReceivableDetail(Integer supplierId) {
        String name = supplierRepository.findById(supplierId)
                .map(supplier -> supplier.getName())
                .orElse("—");

        Map<Integer, Purchaseinvoice> purchasesById = purchaseinvoiceRepository.findAllWithRelations().stream()
                .collect(Collectors.toMap(Purchaseinvoice::getId, purchase -> purchase, (a, b) -> a));

        List<ReceivableLineResponse> lines = returnRepository.findAllWithRelations().stream()
                .filter(this::isApprovedSupplierReturn)
                .filter(ret -> {
                    Purchaseinvoice purchase = purchasesById.get(
                            ret.getPurchaseID() != null ? ret.getPurchaseID().getId() : null);
                    return purchase != null && purchase.getSupplierID() != null
                            && supplierId.equals(purchase.getSupplierID().getId());
                })
                .map(ret -> Map.entry(ret, incomeService.remainingCollectibleForSupplierReturn(ret.getId())))
                .filter(entry -> isPositive(entry.getValue()))
                .sorted(Comparator.<Map.Entry<Return, BigDecimal>, Instant>comparing(
                                entry -> entry.getKey().getReturnDate(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(entry -> entry.getKey().getId(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(entry -> {
                    Return ret = entry.getKey();
                    Purchaseinvoice purchase = purchasesById.get(ret.getPurchaseID().getId());
                    String purchaseCode = purchase != null ? purchase.getPurchaseInvoiceCode() : "—";
                    return new ReceivableLineResponse(
                            ret.getId(),
                            ret.getReturnCode(),
                            formatVnWallClockInstant(ret.getReturnDate()),
                            entry.getValue(),
                            "Phiếu nhập: " + purchaseCode);
                })
                .toList();

        BigDecimal total = lines.stream()
                .map(ReceivableLineResponse::getReceivableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ReceivableDetailResponse(
                supplierId,
                name,
                PARTY_SUPPLIER,
                partyTypeLabels().get(PARTY_SUPPLIER),
                total,
                lines
        );
    }

    private PayableDetailResponse getCustomerPayableDetail(Integer customerId) {
        String name = WALK_IN_CUSTOMER_KEY.equals(customerId)
                ? "Khách lẻ"
                : customerRepository.findById(customerId)
                        .map(Customer::getName)
                        .orElse("—");

        Map<Integer, BigDecimal> disbursed = disbursedByReturnId();
        Map<Integer, Integer> awaitingPaymentByReturnId = awaitingPaymentExpenseByReturnId();

        List<PayableLineResponse> lines = returnRepository.findAllWithRelations().stream()
                .filter(this::isApprovedCustomerReturn)
                .filter(ret -> customerId.equals(customerKeyOf(ret.getInvoiceID())))
                .map(ret -> Map.entry(ret, remainingCustomerReturnPayable(ret, disbursed)))
                .filter(entry -> isPositive(entry.getValue()))
                .sorted(Comparator.<Map.Entry<Return, BigDecimal>, Instant>comparing(
                                entry -> entry.getKey().getReturnDate(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(entry -> entry.getKey().getId(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(entry -> new PayableLineResponse(
                        entry.getKey().getId(),
                        entry.getKey().getReturnCode(),
                        formatVnWallClockInstant(entry.getKey().getReturnDate()),
                        entry.getValue(),
                        customerReturnDetail(entry.getKey())))
                .toList();

        BigDecimal total = lines.stream()
                .map(PayableLineResponse::getPayableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new PayableDetailResponse(
                customerId,
                name,
                PARTY_CUSTOMER,
                partyTypeLabels().get(PARTY_CUSTOMER),
                total,
                lines
        );
    }

    private PayableDetailResponse getSupplierPayableDetail(Integer supplierId) {
        String name = supplierRepository.findById(supplierId)
                .map(supplier -> supplier.getName())
                .orElse("—");

        Map<Integer, BigDecimal> committed = committedByPurchaseId();

        List<PayableLineResponse> lines = purchaseinvoiceService.findPayableInvoices().stream()
                .filter(purchase -> purchase.getSupplierID() != null
                        && supplierId.equals(purchase.getSupplierID().getId()))
                .map(purchase -> Map.entry(purchase, availableToPay(purchase, committed)))
                .filter(entry -> isPositive(entry.getValue()))
                .sorted(Comparator.<Map.Entry<Purchaseinvoice, BigDecimal>, Instant>comparing(
                                entry -> entry.getKey().getDate(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(entry -> entry.getKey().getId(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(entry -> new PayableLineResponse(
                        entry.getKey().getId(),
                        entry.getKey().getPurchaseInvoiceCode(),
                        formatInstant(entry.getKey().getDate()),
                        entry.getValue(),
                        "Tổng phiếu nhập: " + formatMoney(entry.getKey().getTotalAmount())))
                .toList();

        BigDecimal total = lines.stream()
                .map(PayableLineResponse::getPayableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new PayableDetailResponse(
                supplierId,
                name,
                PARTY_SUPPLIER,
                partyTypeLabels().get(PARTY_SUPPLIER),
                total,
                lines
        );
    }

    private List<DebtListItemResponse> buildAllRows() {
        List<DebtListItemResponse> rows = new ArrayList<>();
        rows.addAll(buildCustomerRows());
        rows.addAll(buildSupplierRows());
        return rows;
    }

    private List<DebtListItemResponse> buildCustomerRows() {
        Map<Integer, PartyBalance> byCustomerId = new LinkedHashMap<>();

        for (Invoice invoice : invoiceRepository.findAllWithRelations()) {
            if (!isDebtInvoice(invoice)) {
                continue;
            }
            if (invoice.getCustomerID() == null || invoice.getCustomerID().getId() == null) {
                continue;
            }
            Integer customerId = invoice.getCustomerID().getId();
            String name = invoice.getCustomerID().getName();
            byCustomerId.computeIfAbsent(customerId, id -> new PartyBalance())
                    .addReceivable(name, nullToZero(invoice.getDebtAmount()));
        }

        Map<Integer, BigDecimal> disbursed = disbursedByReturnId();
        for (Return ret : returnRepository.findAllWithRelations()) {
            if (!isApprovedCustomerReturn(ret)) {
                continue;
            }
            BigDecimal payable = remainingCustomerReturnPayable(ret, disbursed);
            if (!isPositive(payable)) {
                continue;
            }
            Invoice invoice = ret.getInvoiceID();
            Integer customerId = customerKeyOf(invoice);
            String name = customerNameOf(invoice);
            byCustomerId.computeIfAbsent(customerId, id -> new PartyBalance())
                    .addPayable(name, payable);
        }

        return byCustomerId.entrySet().stream()
                .map(entry -> toRow(entry.getKey(), entry.getValue(), PARTY_CUSTOMER))
                .collect(Collectors.toList());
    }

    private List<DebtListItemResponse> buildSupplierRows() {
        Map<Integer, Purchaseinvoice> purchasesById = purchaseinvoiceRepository.findAllWithRelations().stream()
                .collect(Collectors.toMap(Purchaseinvoice::getId, purchase -> purchase, (a, b) -> a));

        Map<Integer, PartyBalance> bySupplierId = new LinkedHashMap<>();

        for (Return ret : returnRepository.findAllWithRelations()) {
            if (!isApprovedSupplierReturn(ret)) {
                continue;
            }
            BigDecimal receivable = incomeService.remainingCollectibleForSupplierReturn(ret.getId());
            if (!isPositive(receivable)) {
                continue;
            }

            Purchaseinvoice purchase = purchasesById.get(ret.getPurchaseID().getId());
            if (purchase == null || purchase.getSupplierID() == null || purchase.getSupplierID().getId() == null) {
                continue;
            }

            Integer supplierId = purchase.getSupplierID().getId();
            String name = purchase.getSupplierID().getName();
            bySupplierId.computeIfAbsent(supplierId, id -> new PartyBalance())
                    .addReceivable(name, receivable);
        }

        Map<Integer, BigDecimal> committed = committedByPurchaseId();
        for (Purchaseinvoice purchase : purchaseinvoiceService.findPayableInvoices()) {
            if (purchase.getSupplierID() == null || purchase.getSupplierID().getId() == null) {
                continue;
            }
            BigDecimal available = availableToPay(purchase, committed);
            if (!isPositive(available)) {
                continue;
            }
            Integer supplierId = purchase.getSupplierID().getId();
            String name = purchase.getSupplierID().getName();
            bySupplierId.computeIfAbsent(supplierId, id -> new PartyBalance())
                    .addPayable(name, available);
        }

        return bySupplierId.entrySet().stream()
                .map(entry -> toRow(entry.getKey(), entry.getValue(), PARTY_SUPPLIER))
                .collect(Collectors.toList());
    }

    private DebtListItemResponse toRow(Integer entityId, PartyBalance balance, String partyType) {
        return new DebtListItemResponse(
                entityId,
                balance.name,
                partyType,
                partyTypeLabels().getOrDefault(partyType, partyType),
                balance.payableAmount,
                balance.receivableAmount
        );
    }

    private BigDecimal availableToPay(Purchaseinvoice invoice, Map<Integer, BigDecimal> committed) {
        BigDecimal debt = purchaseinvoiceService.remainingDebt(invoice);
        return debt.subtract(committed.getOrDefault(invoice.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
    }

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
     * Money this slip has actually settled against its linked document. Only
     * {@link ExpenseStatus#COMPLETED} counts — {@link ExpenseStatus#AWAITING_PAYMENT} authorises
     * the slip but does not move money yet (see {@code ExpenseService.confirmPayment}).
     */
    private BigDecimal disbursedAmount(Expense expense) {
        return isStatus(expense.getStatus(), ExpenseStatus.COMPLETED)
                ? nullToZero(expense.getPaid())
                : BigDecimal.ZERO;
    }

    private List<Expense> liveExpenses() {
        return expenseRepository.findAll().stream()
                .filter(expense -> !ExpenseStatus.REJECTED.equals(expense.getStatus()))
                .filter(expense -> !ExpenseStatus.CANCELLED.equals(expense.getStatus()))
                .toList();
    }

    private boolean isApprovedSupplierReturn(Return ret) {
        return ret != null
                && ret.getPurchaseID() != null
                && ret.getInvoiceID() == null
                && isStatus(ret.getStatus(), ReturnPurchaseStatus.APPROVED);
    }

    private Map<Integer, BigDecimal> disbursedByReturnId() {
        Map<Integer, BigDecimal> disbursed = new LinkedHashMap<>();
        for (Expense expense : liveExpenses()) {
            Return ret = expense.getReturnID();
            if (ret == null || ret.getId() == null) {
                continue;
            }
            BigDecimal paid = disbursedAmount(expense);
            if (isPositive(paid)) {
                disbursed.merge(ret.getId(), paid, BigDecimal::add);
            }
        }
        return disbursed;
    }

    /**
     * Cash still owed back to the customer: the return's refund obligation minus what live expense
     * slips have already paid out. Unlike purchase-invoice debt, the return entity itself never
     * shrinks — only disbursements reduce what the debt screen should show.
     */
    private BigDecimal remainingCustomerReturnPayable(Return ret, Map<Integer, BigDecimal> disbursed) {
        return cashRefundAmount(ret)
                .subtract(disbursed.getOrDefault(ret.getId(), BigDecimal.ZERO))
                .max(BigDecimal.ZERO);
    }

    private boolean isApprovedCustomerReturn(Return ret) {
        return ret != null
                && ret.getInvoiceID() != null
                && ret.getPurchaseID() == null
                && isStatus(ret.getStatus(), ReturnStatus.DEBT);
    }

    private Integer customerKeyOf(Invoice invoice) {
        if (invoice == null || invoice.getCustomerID() == null || invoice.getCustomerID().getId() == null) {
            return WALK_IN_CUSTOMER_KEY;
        }
        return invoice.getCustomerID().getId();
    }

    private String customerNameOf(Invoice invoice) {
        if (invoice == null || invoice.getCustomerID() == null || invoice.getCustomerID().getName() == null
                || invoice.getCustomerID().getName().isBlank()) {
            return "Khách lẻ";
        }
        return invoice.getCustomerID().getName();
    }

    private boolean isStatus(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    private BigDecimal cashRefundAmount(Return ret) {
        return nullToZero(ret.getTotalRefund())
                .subtract(nullToZero(ret.getOffsetDebtAmount()))
                .max(BigDecimal.ZERO);
    }

    private String customerReturnDetail(Return ret) {
        Invoice invoice = ret.getInvoiceID();
        Customer customer = invoice != null ? invoice.getCustomerID() : null;
        String customerName = customer != null && customer.getName() != null && !customer.getName().isBlank()
                ? customer.getName()
                : "Khách lẻ";
        String invoiceNumber = invoice != null && invoice.getInvoiceNumber() != null
                ? invoice.getInvoiceNumber()
                : "—";
        return customerName + " · HĐ " + invoiceNumber;
    }

    private boolean hasOutstandingBalance(DebtListItemResponse row) {
        return isPositive(row.getReceivableAmount()) || isPositive(row.getPayableAmount());
    }

    private boolean matchesKeyword(DebtListItemResponse row, String normalizedKeyword) {
        if (normalizedKeyword.isEmpty()) {
            return true;
        }
        return normalize(row.getName()).contains(normalizedKeyword)
                || normalize(row.getPartyTypeDisplay()).contains(normalizedKeyword);
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

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
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

    private String formatInvoiceDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    /** Return dates are stored via nowVn() — VN wall-clock digits on a UTC-labelled Instant. */
    private String formatVnWallClockInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneOffset.UTC)
                .format(instant);
    }

    /** Purchase invoice dates are genuine UTC instants (Instant.now()). */
    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        return instant.atZone(VN_ZONE).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    private String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "0đ";
        }
        return amount.stripTrailingZeros().toPlainString() + "đ";
    }

    private static final class PartyBalance {
        private String name;
        private BigDecimal receivableAmount = BigDecimal.ZERO;
        private BigDecimal payableAmount = BigDecimal.ZERO;

        private void addReceivable(String name, BigDecimal amount) {
            receivableAmount = receivableAmount.add(amount);
            mergeName(name);
        }

        private void addPayable(String name, BigDecimal amount) {
            payableAmount = payableAmount.add(amount);
            mergeName(name);
        }

        private void mergeName(String candidate) {
            if (candidate != null && !candidate.isBlank()) {
                if (name == null || name.isBlank()) {
                    name = candidate;
                }
            }
        }
    }
}
