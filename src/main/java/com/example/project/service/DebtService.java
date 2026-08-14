package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ExpenseType;
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
import java.math.RoundingMode;
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

/**
 * Tổng hợp số dư công nợ theo đối tượng cho màn danh sách ({@code /owner/debts},
 * {@code /accountant/debts}). Service này <em>không</em> ghi nhận tiền — chỉ gom dữ liệu các
 * module khác đã lưu.
 *
 * <p><strong>Cho nợ</strong> — đối tượng còn phải trả nhà thuốc:</p>
 * <ul>
 *   <li><strong>Khách hàng</strong> — hóa đơn bán có {@code debtAmount &gt; 0} hoặc trạng thái
 *       {@code Còn nợ} ({@link #isDebtInvoice}). Thu tiền thực tế qua {@link IncomeService}.</li>
 *   <li><strong>Nhà cung cấp</strong> — phiếu trả NCC đã duyệt ({@code purchaseID != null &&
 *       invoiceID == null}) mà NCC còn phải hoàn tiền mặt
 *       ({@link IncomeService#remainingCollectibleForSupplierReturn}).</li>
 * </ul>
 *
 * <p><strong>Nợ</strong> — nhà thuốc còn phải trả đối tượng:</p>
 * <ul>
 *   <li><strong>Khách hàng</strong> — phiếu trả KH đã duyệt ({@link ReturnStatus#DEBT}). Dòng
 *       {@code Return} chỉ lưu nghĩa vụ hoàn tiền; chi thực tế theo phiếu chi
 *       {@link ExpenseType#RETURN_REFUND_PAYOUT} ({@link ExpenseService}). Chỉ phiếu chi
 *       {@link ExpenseStatus#COMPLETED} mới làm giảm số hiển thị ({@link #disbursedAmount}).</li>
 *   <li><strong>Nhà cung cấp</strong> — phiếu nhập còn nợ
 *       ({@link PurchaseinvoiceService#remainingDebt}). Chi qua phiếu {@link ExpenseType#GOODS_PAYMENT};
 *       phiếu {@link ExpenseStatus#AWAITING_PAYMENT} giữ chỗ nhưng chưa tính là đã trả.</li>
 * </ul>
 *
 * <p><strong>Khách lẻ.</strong> Hóa đơn không có {@code customerID} gom vào khóa
 * {@link #WALK_IN_CUSTOMER_KEY}, hiển thị {@code Khách lẻ}.</p>
 *
 * <p><strong>Bù trừ.</strong> Bù trừ thủ công (Owner) nằm ở {@link DebtOffsetService}. Bù trừ tự
 * động khi duyệt trả NCC ghi qua {@link #recordPurchaseDebtOffset}.</p>
 *
 * <p><strong>Phân trang.</strong> {@link #list} lọc toàn bộ dòng trong memory — phù hợp quy mô
 * nhà thuốc; không phải query phân trang ở DB.</p>
 */
@Service
public class DebtService {

    private static final String INVOICE_STATUS_DEBT = "Còn nợ";
    private static final String PARTY_CUSTOMER = "CUSTOMER";
    private static final String PARTY_SUPPLIER = "SUPPLIER";
    /** Khóa giả cho khách lẻ — hóa đơn không gắn {@code customerID}. */
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

    // ------------------------------------------------------------------ nhãn / hook ghi nhận

    /** Nhãn tiếng Việt loại đối tượng — dùng trên bộ lọc và cột danh sách. */
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

    // ------------------------------------------------------------------ danh sách / tổng hợp KPI

    /** Danh sách công nợ có phân trang — mỗi dòng một đối tượng còn cho nợ và/hoặc nợ. */
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

    /** Tổng KPI trên màn danh sách — tách theo loại đối tượng (KH / NCC). */
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

    // ------------------------------------------------------------------ màn chi tiết

    /**
     * Chi tiết cho nợ — từng chứng từ đối tượng còn nợ nhà thuốc.
     * {@code partyType}: {@code CUSTOMER} hoặc {@code SUPPLIER}; {@code entityId}: id KH/NCC
     * (khách lẻ dùng {@link #WALK_IN_CUSTOMER_KEY}).
     */
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

    /**
     * Chi tiết nợ — từng chứng từ nhà thuốc còn phải trả, kèm phiếu chi
     * {@link ExpenseStatus#AWAITING_PAYMENT} đã tạo cho từng chứng từ.
     */
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

    /** Cho nợ KH — từng hóa đơn bán còn {@link #isDebtInvoice}. */
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

    /** Cho nợ NCC — từng phiếu trả NCC đã duyệt còn phải hoàn tiền mặt. */
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

    /** Nợ KH — từng phiếu trả đã duyệt còn phải hoàn, kèm phiếu chi chờ thanh toán. */
    private PayableDetailResponse getCustomerPayableDetail(Integer customerId) {
        String name = WALK_IN_CUSTOMER_KEY.equals(customerId)
                ? "Khách lẻ"
                : customerRepository.findById(customerId)
                        .map(Customer::getName)
                        .orElse("—");

        Map<Integer, BigDecimal> disbursed = disbursedByReturnId();
        Map<Integer, BigDecimal> awaitingAmounts = awaitingPaymentAmountByReturnId();
        Map<Integer, List<PayableLineResponse>> awaitingByReturnId = awaitingExpensesByReturnId(customerId);

        List<PayableLineResponse> lines = returnRepository.findAllWithRelations().stream()
                .filter(this::isApprovedCustomerReturn)
                .filter(ret -> customerId.equals(customerKeyOf(ret.getInvoiceID())))
                .map(ret -> {
                    BigDecimal actualDebt = remainingCustomerReturnPayable(ret, disbursed);
                    BigDecimal creatable = displayCustomerReturnPayable(ret, disbursed, awaitingAmounts);
                    return Map.entry(ret, Map.entry(actualDebt, creatable));
                })
                .filter(entry -> isPositive(entry.getValue().getKey()))
                .sorted(Comparator.<Map.Entry<Return, Map.Entry<BigDecimal, BigDecimal>>, Instant>comparing(
                                entry -> entry.getKey().getReturnDate(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(entry -> entry.getKey().getId(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(entry -> new PayableLineResponse(
                        entry.getKey().getId(),
                        entry.getKey().getReturnCode(),
                        formatVnWallClockInstant(entry.getKey().getReturnDate()),
                        entry.getValue().getKey(),
                        customerReturnDetail(entry.getKey()),
                        entry.getValue().getValue(),
                        awaitingByReturnId.getOrDefault(entry.getKey().getId(), List.of())))
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

    /** Nợ NCC — từng phiếu nhập còn nợ, kèm phiếu chi chờ thanh toán. */
    private PayableDetailResponse getSupplierPayableDetail(Integer supplierId) {
        String name = supplierRepository.findById(supplierId)
                .map(supplier -> supplier.getName())
                .orElse("—");

        Map<Integer, BigDecimal> awaitingAmounts = awaitingPaymentAmountByPurchaseId();
        Map<Integer, List<PayableLineResponse>> awaitingByPurchaseId = awaitingExpensesByPurchaseId(supplierId);

        List<PayableLineResponse> lines = purchaseinvoiceService.findPayableInvoices().stream()
                .filter(purchase -> purchase.getSupplierID() != null
                        && supplierId.equals(purchase.getSupplierID().getId()))
                .map(purchase -> {
                    BigDecimal actualDebt = purchaseinvoiceService.remainingDebt(purchase);
                    BigDecimal creatable = displaySupplierPayable(purchase, awaitingAmounts);
                    return Map.entry(purchase, Map.entry(actualDebt, creatable));
                })
                .filter(entry -> isPositive(entry.getValue().getKey()))
                .sorted(Comparator.<Map.Entry<Purchaseinvoice, Map.Entry<BigDecimal, BigDecimal>>, Instant>comparing(
                                entry -> entry.getKey().getDate(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(entry -> entry.getKey().getId(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(entry -> new PayableLineResponse(
                        entry.getKey().getId(),
                        entry.getKey().getPurchaseInvoiceCode(),
                        formatInstant(entry.getKey().getDate()),
                        entry.getValue().getKey(),
                        "Tổng phiếu nhập: " + formatMoney(entry.getKey().getTotalAmount()),
                        entry.getValue().getValue(),
                        awaitingByPurchaseId.getOrDefault(entry.getKey().getId(), List.of())))
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

    // ------------------------------------------------------------------ gom dòng danh sách

    /** Gom dòng khách hàng và NCC trước khi lọc / phân trang. */
    private List<DebtListItemResponse> buildAllRows() {
        List<DebtListItemResponse> rows = new ArrayList<>();
        rows.addAll(buildCustomerRows());
        rows.addAll(buildSupplierRows());
        return rows;
    }

    /** Cho nợ từ hóa đơn còn nợ; nợ từ phiếu trả KH đã duyệt còn phải hoàn. */
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

    /** Cho nợ từ phiếu trả NCC đã duyệt; nợ từ phiếu nhập còn nợ. */
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

        for (Purchaseinvoice purchase : purchaseinvoiceService.findPayableInvoices()) {
            if (purchase.getSupplierID() == null || purchase.getSupplierID().getId() == null) {
                continue;
            }
            BigDecimal payable = purchaseinvoiceService.remainingDebt(purchase);
            if (!isPositive(payable)) {
                continue;
            }
            Integer supplierId = purchase.getSupplierID().getId();
            String name = purchase.getSupplierID().getName();
            bySupplierId.computeIfAbsent(supplierId, id -> new PartyBalance())
                    .addPayable(name, payable);
        }

        return bySupplierId.entrySet().stream()
                .map(entry -> toRow(entry.getKey(), entry.getValue(), PARTY_SUPPLIER))
                .collect(Collectors.toList());
    }

    /** Map bộ cộng dồn {@link PartyBalance} sang một dòng danh sách. */
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

    // ------------------------------------------------------------------ phiếu chi chờ thanh toán (chi tiết nợ)

    /** Phiếu chi {@link ExpenseStatus#AWAITING_PAYMENT} còn hiệu lực, gom theo id phiếu trả KH. */
    private Map<Integer, List<PayableLineResponse>> awaitingExpensesByReturnId(Integer customerId) {
        Map<Integer, List<Expense>> byReturnId = new LinkedHashMap<>();
        for (Expense expense : liveExpenses()) {
            if (!isStatus(expense.getStatus(), ExpenseStatus.AWAITING_PAYMENT)) {
                continue;
            }
            Return ret = expense.getReturnID();
            if (ret == null || ret.getId() == null) {
                continue;
            }
            if (!customerId.equals(customerKeyOf(ret.getInvoiceID()))) {
                continue;
            }
            byReturnId.computeIfAbsent(ret.getId(), id -> new ArrayList<>()).add(expense);
        }
        return toAwaitingExpenseLinesByDocumentId(byReturnId);
    }

    /** Phiếu chi {@link ExpenseStatus#AWAITING_PAYMENT} còn hiệu lực, gom theo id phiếu nhập. */
    private Map<Integer, List<PayableLineResponse>> awaitingExpensesByPurchaseId(Integer supplierId) {
        Map<Integer, List<Expense>> byPurchaseId = new LinkedHashMap<>();
        for (Expense expense : liveExpenses()) {
            if (!isStatus(expense.getStatus(), ExpenseStatus.AWAITING_PAYMENT)) {
                continue;
            }
            Purchaseinvoice purchase = expense.getPurchaseID();
            if (purchase == null || purchase.getId() == null || purchase.getSupplierID() == null) {
                continue;
            }
            if (!supplierId.equals(purchase.getSupplierID().getId())) {
                continue;
            }
            byPurchaseId.computeIfAbsent(purchase.getId(), id -> new ArrayList<>()).add(expense);
        }
        return toAwaitingExpenseLinesByDocumentId(byPurchaseId);
    }

    /** Chuyển map phiếu chi chờ thanh toán sang dòng hiển thị trên màn chi tiết nợ. */
    private Map<Integer, List<PayableLineResponse>> toAwaitingExpenseLinesByDocumentId(
            Map<Integer, List<Expense>> expensesByDocumentId) {
        Map<Integer, List<PayableLineResponse>> linesByDocumentId = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Expense>> entry : expensesByDocumentId.entrySet()) {
            List<PayableLineResponse> lines = entry.getValue().stream()
                    .sorted(Comparator.comparing(Expense::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(Expense::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(this::toAwaitingExpenseLine)
                    .toList();
            linesByDocumentId.put(entry.getKey(), lines);
        }
        return linesByDocumentId;
    }

    /** Một phiếu chi {@link ExpenseStatus#AWAITING_PAYMENT} — hiển thị như dòng con của chứng từ gốc. */
    private PayableLineResponse toAwaitingExpenseLine(Expense expense) {
        Return ret = expense.getReturnID();
        Purchaseinvoice purchase = expense.getPurchaseID();
        String detail;
        if (ret != null) {
            detail = "Phiếu trả: " + nullToEmpty(ret.getReturnCode());
        } else if (purchase != null) {
            detail = "Phiếu nhập: " + nullToEmpty(purchase.getPurchaseInvoiceCode());
        } else {
            detail = null;
        }
        return new PayableLineResponse(
                expense.getId(),
                expense.getExpenseCode(),
                formatInstant(expense.getDate()),
                nullToZero(expense.getAmount()),
                detail,
                null,
                List.of()
        );
    }

    /**
     * Số còn có thể tạo phiếu chi mới — nợ thực trừ các phiếu đang
     * {@link ExpenseStatus#AWAITING_PAYMENT}.
     */
    private BigDecimal displayCustomerReturnPayable(Return ret,
                                                    Map<Integer, BigDecimal> disbursed,
                                                    Map<Integer, BigDecimal> awaitingAmounts) {
        return remainingCustomerReturnPayable(ret, disbursed)
                .subtract(awaitingAmounts.getOrDefault(ret.getId(), BigDecimal.ZERO))
                .max(BigDecimal.ZERO);
    }

    /** Giống {@link #displayCustomerReturnPayable} nhưng cho nợ phiếu nhập. */
    private BigDecimal displaySupplierPayable(Purchaseinvoice purchase,
                                              Map<Integer, BigDecimal> awaitingAmounts) {
        return purchaseinvoiceService.remainingDebt(purchase)
                .subtract(awaitingAmounts.getOrDefault(purchase.getId(), BigDecimal.ZERO))
                .max(BigDecimal.ZERO);
    }

    /** Tổng tiền phiếu chi chờ thanh toán gom theo id phiếu trả KH. */
    private Map<Integer, BigDecimal> awaitingPaymentAmountByReturnId() {
        Map<Integer, BigDecimal> amounts = new LinkedHashMap<>();
        for (Expense expense : liveExpenses()) {
            if (!isStatus(expense.getStatus(), ExpenseStatus.AWAITING_PAYMENT)) {
                continue;
            }
            Return ret = expense.getReturnID();
            if (ret != null && ret.getId() != null) {
                amounts.merge(ret.getId(), nullToZero(expense.getAmount()), BigDecimal::add);
            }
        }
        return amounts;
    }

    /** Tổng tiền phiếu chi chờ thanh toán gom theo id phiếu nhập. */
    private Map<Integer, BigDecimal> awaitingPaymentAmountByPurchaseId() {
        Map<Integer, BigDecimal> amounts = new LinkedHashMap<>();
        for (Expense expense : liveExpenses()) {
            if (!isStatus(expense.getStatus(), ExpenseStatus.AWAITING_PAYMENT)) {
                continue;
            }
            Purchaseinvoice invoice = expense.getPurchaseID();
            if (invoice != null && invoice.getId() != null) {
                amounts.merge(invoice.getId(), nullToZero(expense.getAmount()), BigDecimal::add);
            }
        }
        return amounts;
    }

    // ------------------------------------------------------------------ helper phiếu chi

    /**
     * Tiền phiếu chi đã thực sự cấn vào chứng từ liên kết. Chỉ
     * {@link ExpenseStatus#COMPLETED} mới tính — {@link ExpenseStatus#AWAITING_PAYMENT} mới duyệt
     * chờ chi, chưa trừ tiền (xem {@code ExpenseService.confirmPayment}).
     */
    private BigDecimal disbursedAmount(Expense expense) {
        return isStatus(expense.getStatus(), ExpenseStatus.COMPLETED)
                ? nullToZero(expense.getPaid())
                : BigDecimal.ZERO;
    }

    /** Phiếu chi không bị từ chối / hủy — mới giữ chỗ hoặc cấn nợ. */
    private List<Expense> liveExpenses() {
        return expenseRepository.findAllWithRelations().stream()
                .filter(expense -> !ExpenseStatus.REJECTED.equals(expense.getStatus()))
                .filter(expense -> !ExpenseStatus.CANCELLED.equals(expense.getStatus()))
                .toList();
    }

    /**
     * Nhận diện phiếu trả NCC — cùng quy tắc {@link ExpenseService} / {@link ReturnService}:
     * {@code purchaseID != null && invoiceID == null}.
     */
    private boolean isApprovedSupplierReturn(Return ret) {
        return ret != null
                && ret.getPurchaseID() != null
                && ret.getInvoiceID() == null
                && isStatus(ret.getStatus(), ReturnPurchaseStatus.APPROVED);
    }

    /** Tổng tiền phiếu chi đã chi thực ({@link ExpenseStatus#COMPLETED}) gom theo id phiếu trả. */
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
     * Tiền còn phải hoàn KH: nghĩa vụ hoàn trên phiếu trả trừ phiếu chi đã chi thực tế.
     * Khác nợ phiếu nhập — bản thân {@code Return} không tự giảm; chỉ chi thực mới làm giảm số
     * hiển thị trên màn công nợ.
     */
    private BigDecimal remainingCustomerReturnPayable(Return ret, Map<Integer, BigDecimal> disbursed) {
        return cashRefundAmount(ret)
                .subtract(disbursed.getOrDefault(ret.getId(), BigDecimal.ZERO))
                .max(BigDecimal.ZERO);
    }

    /** Nhận diện phiếu trả KH — {@code invoiceID != null && purchaseID == null}. */
    private boolean isApprovedCustomerReturn(Return ret) {
        return ret != null
                && ret.getInvoiceID() != null
                && ret.getPurchaseID() == null
                && isStatus(ret.getStatus(), ReturnStatus.DEBT);
    }

    /** Khách null gom về {@link #WALK_IN_CUSTOMER_KEY} để công nợ khách lẻ thành một dòng. */
    private Integer customerKeyOf(Invoice invoice) {
        if (invoice == null || invoice.getCustomerID() == null || invoice.getCustomerID().getId() == null) {
            return WALK_IN_CUSTOMER_KEY;
        }
        return invoice.getCustomerID().getId();
    }

    /** Tên hiển thị trên dòng công nợ — khách null hoặc trống trả {@code Khách lẻ}. */
    private String customerNameOf(Invoice invoice) {
        if (invoice == null || invoice.getCustomerID() == null || invoice.getCustomerID().getName() == null
                || invoice.getCustomerID().getName().isBlank()) {
            return "Khách lẻ";
        }
        return invoice.getCustomerID().getName();
    }

    /** So sánh hai chuỗi trạng thái sau khi chuẩn hóa. */
    private boolean isStatus(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    /** Phần hoàn tiền mặt sau khi trừ số đã bù trừ công nợ lúc duyệt phiếu trả. */
    private BigDecimal cashRefundAmount(Return ret) {
        return nullToZero(ret.getTotalRefund())
                .subtract(nullToZero(ret.getOffsetDebtAmount()))
                .max(BigDecimal.ZERO);
    }

    /** Mô tả phụ trên dòng phiếu trả KH — tên khách và số hóa đơn gốc. */
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

    /** Chỉ giữ dòng còn cho nợ hoặc nợ &gt; 0 trên danh sách. */
    private boolean hasOutstandingBalance(DebtListItemResponse row) {
        return isPositive(row.getReceivableAmount()) || isPositive(row.getPayableAmount());
    }

    /** So khớp từ khóa tìm kiếm với tên đối tượng hoặc nhãn loại (đã chuẩn hóa). */
    private boolean matchesKeyword(DebtListItemResponse row, String normalizedKeyword) {
        if (normalizedKeyword.isEmpty()) {
            return true;
        }
        return normalize(row.getName()).contains(normalizedKeyword)
                || normalize(row.getPartyTypeDisplay()).contains(normalizedKeyword);
    }

    /** Hóa đơn còn cho nợ — trạng thái {@code Còn nợ} hoặc {@code debtAmount &gt; 0}. */
    private boolean isDebtInvoice(Invoice invoice) {
        if (invoice == null) {
            return false;
        }
        if (INVOICE_STATUS_DEBT.equals(invoice.getStatus())) {
            return true;
        }
        return isPositive(invoice.getDebtAmount());
    }

    // ------------------------------------------------------------------ định dạng / tìm kiếm

    /** Kiểm tra số dương (&gt; 0). */
    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /** Null-safe — trả {@link BigDecimal#ZERO} nếu null. */
    private BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /** Null-safe — trả {@code —} nếu null. */
    private String nullToEmpty(String value) {
        return value != null ? value : "—";
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

    /** Định dạng ngày hóa đơn bán ({@code dd/MM/yyyy HH:mm}). */
    private String formatInvoiceDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    /** Ngày phiếu trả lưu kiểu nowVn() — số giờ VN gắn nhãn UTC trên {@link Instant}. */
    private String formatVnWallClockInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneOffset.UTC)
                .format(instant);
    }

    /** Ngày phiếu nhập là {@link Instant} UTC thật ({@code Instant.now()}). */
    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        return instant.atZone(VN_ZONE).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    /** Định dạng tiền VND trên giao diện. */
    private String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "0đ";
        }
        long vnd = amount.setScale(0, RoundingMode.HALF_UP).longValue();
        return String.format(Locale.forLanguageTag("vi-VN"), "%,dđ", vnd);
    }

    /** Bộ cộng dồn cho nợ / nợ khi gom theo id khách hàng hoặc NCC. */
    private static final class PartyBalance {
        private String name;
        private BigDecimal receivableAmount = BigDecimal.ZERO;
        private BigDecimal payableAmount = BigDecimal.ZERO;

        /** Cộng dồn cho nợ theo id đối tượng. */
        private void addReceivable(String name, BigDecimal amount) {
            receivableAmount = receivableAmount.add(amount);
            mergeName(name);
        }

        /** Cộng dồn nợ theo id đối tượng. */
        private void addPayable(String name, BigDecimal amount) {
            payableAmount = payableAmount.add(amount);
            mergeName(name);
        }

        /** Giữ tên đầu tiên không rỗng khi gom nhiều chứng từ cùng đối tượng. */
        private void mergeName(String candidate) {
            if (candidate != null && !candidate.isBlank()) {
                if (name == null || name.isBlank()) {
                    name = candidate;
                }
            }
        }
    }
}
