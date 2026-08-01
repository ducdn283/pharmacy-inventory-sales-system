package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ExpenseType;
import com.example.project.constant.ReturnPurchaseStatus;
import com.example.project.constant.ReturnStatus;
import com.example.project.dto.request.DebtOffsetLineRequest;
import com.example.project.dto.request.DebtOffsetRequest;
import com.example.project.dto.response.DebtOffsetPageResponse;
import com.example.project.dto.response.IncomeTypeOptionResponse;
import com.example.project.dto.response.PayableDetailResponse;
import com.example.project.dto.response.PayableLineResponse;
import com.example.project.dto.response.ReceivableDetailResponse;
import com.example.project.dto.response.ReceivableLineResponse;
import com.example.project.entity.Account;
import com.example.project.entity.Customer;
import com.example.project.entity.Expense;
import com.example.project.entity.Income;
import com.example.project.entity.Invoice;
import com.example.project.entity.Purchaseinvoice;
import com.example.project.entity.Return;
import com.example.project.entity.Supplier;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.CustomerRepository;
import com.example.project.repository.ExpenseRepository;
import com.example.project.repository.IncomeRepository;
import com.example.project.repository.InvoiceRepository;
import com.example.project.repository.PurchaseinvoiceRepository;
import com.example.project.repository.ReturnRepository;
import com.example.project.repository.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DebtOffsetService {

    private static final String PARTY_CUSTOMER = "CUSTOMER";
    private static final String PARTY_SUPPLIER = "SUPPLIER";
    private static final String INCOME_STATUS_COMPLETED = "Hoàn thành";
    private static final String INCOME_STATUS_REJECTED = "Từ chối";
    private static final String INVOICE_STATUS_DEBT = "Còn nợ";
    private static final String INVOICE_STATUS_COMPLETED = "Hoàn thành";
    private static final String OFFSET_REASON = "Bù trừ công nợ";
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final DebtService debtService;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final IncomeRepository incomeRepository;
    private final ExpenseRepository expenseRepository;
    private final InvoiceRepository invoiceRepository;
    private final ReturnRepository returnRepository;
    private final PurchaseinvoiceRepository purchaseinvoiceRepository;
    private final PurchaseinvoiceService purchaseinvoiceService;
    private final InvoiceService invoiceService;

    public DebtOffsetService(DebtService debtService,
                             AccountRepository accountRepository,
                             CustomerRepository customerRepository,
                             SupplierRepository supplierRepository,
                             IncomeRepository incomeRepository,
                             ExpenseRepository expenseRepository,
                             InvoiceRepository invoiceRepository,
                             ReturnRepository returnRepository,
                             PurchaseinvoiceRepository purchaseinvoiceRepository,
                             PurchaseinvoiceService purchaseinvoiceService,
                             InvoiceService invoiceService) {
        this.debtService = debtService;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
        this.incomeRepository = incomeRepository;
        this.expenseRepository = expenseRepository;
        this.invoiceRepository = invoiceRepository;
        this.returnRepository = returnRepository;
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.purchaseinvoiceService = purchaseinvoiceService;
        this.invoiceService = invoiceService;
    }

    @Transactional(readOnly = true)
    public DebtOffsetPageResponse getOffsetPage(String partyType, Integer entityId) {
        ReceivableDetailResponse receivable = debtService.getReceivableDetail(partyType, entityId);
        PayableDetailResponse payable = debtService.getPayableDetail(partyType, entityId);

        if (!isPositive(receivable.getTotalReceivable()) || !isPositive(payable.getTotalPayable())) {
            throw new IllegalArgumentException(
                    "Chỉ có thể bù trừ khi đối tượng vừa có nợ vừa có cho nợ");
        }

        BigDecimal maxOffset = receivable.getTotalReceivable()
                .min(payable.getTotalPayable())
                .setScale(2, RoundingMode.HALF_UP);

        return new DebtOffsetPageResponse(
                entityId,
                receivable.getName(),
                receivable.getPartyType(),
                receivable.getPartyTypeDisplay(),
                receivable.getTotalReceivable(),
                payable.getTotalPayable(),
                maxOffset,
                receivable.getLines(),
                payable.getLines()
        );
    }

    /**
     * Offsets receivable against payable for the same party using paired Income/Expense slips
     * with {@code paidByCredit} only (no cash/banking movement).
     */
    @Transactional
    public void applyOffset(DebtOffsetRequest request, Integer currentAccountId) {
        validateRequestShape(request);

        String partyType = request.getPartyType().trim().toUpperCase(Locale.ROOT);
        Integer entityId = request.getEntityId();

        ReceivableDetailResponse receivable = debtService.getReceivableDetail(partyType, entityId);
        PayableDetailResponse payable = debtService.getPayableDetail(partyType, entityId);

        Map<Integer, ReceivableLineResponse> receivableById = receivable.getLines().stream()
                .collect(Collectors.toMap(ReceivableLineResponse::getId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        Map<Integer, PayableLineResponse> payableById = payable.getLines().stream()
                .collect(Collectors.toMap(PayableLineResponse::getId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));

        List<DebtOffsetLineRequest> receivableAllocations = normalizeAllocations(
                request.getReceivableLines(), receivableById, ReceivableLineResponse::getReceivableAmount);
        List<DebtOffsetLineRequest> payableAllocations = normalizeAllocations(
                request.getPayableLines(), payableById, PayableLineResponse::getPayableAmount);

        BigDecimal receivableTotal = sumAllocations(receivableAllocations);
        BigDecimal payableTotal = sumAllocations(payableAllocations);

        if (!isPositive(receivableTotal)) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một khoản cho nợ để bù trừ");
        }
        if (receivableTotal.compareTo(payableTotal) != 0) {
            throw new IllegalArgumentException(
                    "Tổng bù trừ cho nợ (" + formatMoney(receivableTotal)
                            + ") phải bằng tổng bù trừ nợ (" + formatMoney(payableTotal) + ")");
        }
        if (receivableTotal.compareTo(receivable.getTotalReceivable()) > 0
                || payableTotal.compareTo(payable.getTotalPayable()) > 0) {
            throw new IllegalArgumentException("Số tiền bù trừ vượt quá số còn lại của đối tượng");
        }

        Account applicant = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        String note = trimToNull(request.getNote());

        if (PARTY_CUSTOMER.equals(partyType)) {
            Customer customer = resolveCustomer(entityId);
            for (DebtOffsetLineRequest line : receivableAllocations) {
                Invoice invoice = invoiceRepository.findById(line.getDocumentId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn cho nợ"));
                createCustomerReceivableOffset(applicant, customer, invoice, line.getAmount(), note);
            }
            for (DebtOffsetLineRequest line : payableAllocations) {
                Return ret = returnRepository.findById(line.getDocumentId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng cần hoàn"));
                createCustomerPayableOffset(applicant, customer, ret, line.getAmount(), note);
            }
            return;
        }

        if (PARTY_SUPPLIER.equals(partyType)) {
            Supplier supplier = resolveSupplier(entityId);
            for (DebtOffsetLineRequest line : receivableAllocations) {
                Return ret = returnRepository.findById(line.getDocumentId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng NCC"));
                createSupplierReceivableOffset(applicant, supplier, ret, line.getAmount(), note);
            }
            for (DebtOffsetLineRequest line : payableAllocations) {
                Purchaseinvoice purchase = purchaseinvoiceRepository.findById(line.getDocumentId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập còn nợ"));
                createSupplierPayableOffset(applicant, supplier, purchase, line.getAmount(), note);
            }
            return;
        }

        throw new IllegalArgumentException("Loại đối tượng không hợp lệ");
    }

    private void createCustomerReceivableOffset(Account applicant,
                                                Customer customer,
                                                Invoice invoice,
                                                BigDecimal amount,
                                                String note) {
        validateCustomerInvoiceOffset(invoice, customer, amount);

        Income income = baseCreditIncome(applicant, IncomeTypeOptionResponse.storageLabelOf(
                IncomeTypeOptionResponse.CUSTOMER), amount, note);
        income.setCustomerID(customer);
        income.setInvoiceID(invoice);
        persistIncome(income);
        reduceInvoiceDebt(invoice, amount);
    }

    private void createCustomerPayableOffset(Account applicant,
                                             Customer customer,
                                             Return ret,
                                             BigDecimal amount,
                                             String note) {
        validateCustomerReturnOffset(ret, amount);

        Expense expense = baseCreditExpense(applicant, ExpenseType.RETURN_REFUND_PAYOUT, amount, note);
        expense.setReturnID(ret);
        expense.setCustomerID(customer != null ? customer : customerOf(ret));
        persistExpense(expense);
    }

    private void createSupplierReceivableOffset(Account applicant,
                                                Supplier supplier,
                                                Return ret,
                                                BigDecimal amount,
                                                String note) {
        validateSupplierReturnOffset(ret, supplier, amount);

        Income income = baseCreditIncome(applicant, IncomeTypeOptionResponse.storageLabelOf(
                IncomeTypeOptionResponse.SUPPLIER), amount, note);
        income.setSupplierID(supplier);
        income.setReturnID(ret);
        persistIncome(income);
    }

    private void createSupplierPayableOffset(Account applicant,
                                             Supplier supplier,
                                             Purchaseinvoice purchase,
                                             BigDecimal amount,
                                             String note) {
        validateSupplierPurchaseOffset(purchase, supplier, amount);

        Expense expense = baseCreditExpense(applicant, ExpenseType.GOODS_PAYMENT, amount, note);
        expense.setPurchaseID(purchase);
        expense.setSupplierID(supplier);
        persistExpense(expense);
        purchaseinvoiceService.applyPayment(purchase.getId(), amount);
    }

    private Income baseCreditIncome(Account applicant,
                                    String incomeTypeLabel,
                                    BigDecimal amount,
                                    String note) {
        Income income = new Income();
        income.setApplicantID(applicant);
        income.setIncomeType(incomeTypeLabel);
        income.setDate(nowVn());
        income.setReason(OFFSET_REASON);
        income.setAmount(amount);
        income.setPaidByCash(BigDecimal.ZERO);
        income.setPaidByBanking(BigDecimal.ZERO);
        income.setPaidByCredit(amount);
        income.setNote(note);
        income.setStatus(INCOME_STATUS_COMPLETED);
        return income;
    }

    private Expense baseCreditExpense(Account applicant,
                                      String expenseType,
                                      BigDecimal amount,
                                      String note) {
        Expense expense = new Expense();
        expense.setApplicantID(applicant);
        expense.setExpenseType(expenseType);
        expense.setDate(Instant.now());
        expense.setReason(OFFSET_REASON);
        expense.setAmount(amount);
        expense.setPaid(amount);
        expense.setPaidByCash(BigDecimal.ZERO);
        expense.setPaidByBanking(BigDecimal.ZERO);
        expense.setPaidByCredit(amount);
        expense.setNote(note);
        expense.setStatus(ExpenseStatus.COMPLETED);
        expense.setApprovedAt(Instant.now());
        return expense;
    }

    private void persistIncome(Income income) {
        income.setIncomeCode(generateIncomeCode());
        Income saved = incomeRepository.save(income);
        saved.setIncomeCode(formatIncomeCode(saved.getId()));
        incomeRepository.save(saved);
    }

    private void persistExpense(Expense expense) {
        expense.setExpenseCode(generateExpenseCode());
        Expense saved = expenseRepository.save(expense);
        saved.setExpenseCode(formatExpenseCode(saved.getId()));
        expenseRepository.save(saved);
    }

    private void reduceInvoiceDebt(Invoice invoice, BigDecimal amount) {
        BigDecimal payment = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal currentDebt = nullToZero(invoice.getDebtAmount());
        if (payment.compareTo(currentDebt) > 0) {
            throw new IllegalArgumentException(
                    "Số tiền bù trừ vượt quá dư nợ hóa đơn " + invoice.getInvoiceNumber());
        }
        BigDecimal newDebt = currentDebt.subtract(payment).max(BigDecimal.ZERO);
        invoice.setDebtAmount(newDebt);
        invoice.setStatus(newDebt.compareTo(BigDecimal.ZERO) > 0 ? INVOICE_STATUS_DEBT : INVOICE_STATUS_COMPLETED);
        invoiceService.persistInvoice(invoice);
    }

    private void validateCustomerInvoiceOffset(Invoice invoice, Customer customer, BigDecimal amount) {
        if (invoice == null || invoice.getId() == null) {
            throw new IllegalArgumentException("Hóa đơn không hợp lệ");
        }
        if (customer != null) {
            if (invoice.getCustomerID() == null
                    || !Objects.equals(customer.getId(), invoice.getCustomerID().getId())) {
                throw new IllegalArgumentException("Hóa đơn không thuộc khách hàng đang bù trừ");
            }
        } else if (invoice.getCustomerID() != null) {
            throw new IllegalArgumentException("Hóa đơn không thuộc khách lẻ đang bù trừ");
        }
        if (!isPositive(nullToZero(invoice.getDebtAmount()))) {
            throw new IllegalArgumentException("Hóa đơn " + invoice.getInvoiceNumber() + " không còn cho nợ");
        }
        if (amount.setScale(2, RoundingMode.HALF_UP)
                .compareTo(nullToZero(invoice.getDebtAmount()).setScale(2, RoundingMode.HALF_UP)) > 0) {
            throw new IllegalArgumentException(
                    "Số tiền bù trừ vượt quá cho nợ của hóa đơn " + invoice.getInvoiceNumber());
        }
    }

    private void validateCustomerReturnOffset(Return ret, BigDecimal amount) {
        if (ret == null || ret.getInvoiceID() == null || ret.getPurchaseID() != null) {
            throw new IllegalArgumentException("Phiếu trả không hợp lệ để bù trừ nợ khách hàng");
        }
        if (!ReturnStatus.DEBT.equals(ret.getStatus())) {
            throw new IllegalArgumentException("Chỉ bù trừ với phiếu trả hàng đã duyệt");
        }
        BigDecimal remaining = cashRefundAmount(ret).subtract(disbursedForReturn(ret.getId())).max(BigDecimal.ZERO);
        if (amount.setScale(2, RoundingMode.HALF_UP).compareTo(remaining.setScale(2, RoundingMode.HALF_UP)) > 0) {
            throw new IllegalArgumentException(
                    "Số tiền bù trừ vượt quá phần còn phải hoàn của phiếu " + ret.getReturnCode());
        }
    }

    private void validateSupplierReturnOffset(Return ret, Supplier supplier, BigDecimal amount) {
        if (ret == null || ret.getPurchaseID() == null || ret.getInvoiceID() != null) {
            throw new IllegalArgumentException("Phiếu trả NCC không hợp lệ");
        }
        if (!ReturnPurchaseStatus.APPROVED.equals(ret.getStatus())) {
            throw new IllegalArgumentException("Chỉ bù trừ với phiếu trả NCC đã duyệt");
        }
        Purchaseinvoice purchase = ret.getPurchaseID();
        if (purchase.getSupplierID() == null
                || !Objects.equals(supplier.getId(), purchase.getSupplierID().getId())) {
            throw new IllegalArgumentException("Phiếu trả không thuộc nhà cung cấp đang bù trừ");
        }
        BigDecimal remaining = supplierCashReceivable(ret).subtract(accountedForReturn(ret.getId())).max(BigDecimal.ZERO);
        if (amount.setScale(2, RoundingMode.HALF_UP).compareTo(remaining.setScale(2, RoundingMode.HALF_UP)) > 0) {
            throw new IllegalArgumentException(
                    "Số tiền bù trừ vượt quá phần NCC còn phải hoàn của phiếu " + ret.getReturnCode());
        }
    }

    private void validateSupplierPurchaseOffset(Purchaseinvoice purchase, Supplier supplier, BigDecimal amount) {
        if (purchase == null || purchase.getSupplierID() == null
                || !Objects.equals(supplier.getId(), purchase.getSupplierID().getId())) {
            throw new IllegalArgumentException("Phiếu nhập không thuộc nhà cung cấp đang bù trừ");
        }
        BigDecimal remaining = purchaseinvoiceService.remainingDebt(purchase)
                .subtract(committedForPurchase(purchase.getId()))
                .max(BigDecimal.ZERO);
        if (!isPositive(remaining)) {
            throw new IllegalArgumentException("Phiếu nhập " + purchase.getPurchaseInvoiceCode() + " không còn nợ");
        }
        if (amount.setScale(2, RoundingMode.HALF_UP).compareTo(remaining.setScale(2, RoundingMode.HALF_UP)) > 0) {
            throw new IllegalArgumentException(
                    "Số tiền bù trừ vượt quá nợ còn lại của phiếu nhập " + purchase.getPurchaseInvoiceCode());
        }
    }

    private BigDecimal disbursedForReturn(Integer returnId) {
        return expenseRepository.findAll().stream()
                .filter(expense -> !ExpenseStatus.REJECTED.equals(expense.getStatus()))
                .filter(expense -> !ExpenseStatus.CANCELLED.equals(expense.getStatus()))
                .filter(expense -> expense.getReturnID() != null
                        && returnId.equals(expense.getReturnID().getId()))
                .map(this::disbursedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal accountedForReturn(Integer returnId) {
        return incomeRepository.findAllWithRelations().stream()
                .filter(income -> !INCOME_STATUS_REJECTED.equals(income.getStatus()))
                .filter(income -> income.getReturnID() != null
                        && returnId.equals(income.getReturnID().getId()))
                .map(Income::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal committedForPurchase(Integer purchaseId) {
        return expenseRepository.findAll().stream()
                .filter(expense -> !ExpenseStatus.REJECTED.equals(expense.getStatus()))
                .filter(expense -> !ExpenseStatus.CANCELLED.equals(expense.getStatus()))
                .filter(expense -> expense.getPurchaseID() != null
                        && purchaseId.equals(expense.getPurchaseID().getId()))
                .map(expense -> nullToZero(expense.getAmount()).subtract(disbursedAmount(expense)).max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal disbursedAmount(Expense expense) {
        boolean approved = ExpenseStatus.AWAITING_PAYMENT.equals(expense.getStatus())
                || ExpenseStatus.COMPLETED.equals(expense.getStatus());
        return approved ? nullToZero(expense.getPaid()) : BigDecimal.ZERO;
    }

    private BigDecimal cashRefundAmount(Return ret) {
        return nullToZero(ret.getTotalRefund())
                .subtract(nullToZero(ret.getOffsetDebtAmount()))
                .max(BigDecimal.ZERO);
    }

    private BigDecimal supplierCashReceivable(Return ret) {
        return cashRefundAmount(ret);
    }

    private Customer customerOf(Return ret) {
        Invoice invoice = ret.getInvoiceID();
        return invoice != null ? invoice.getCustomerID() : null;
    }

    private Customer resolveCustomer(Integer entityId) {
        if (entityId != null && entityId == 0) {
            return null;
        }
        return customerRepository.findById(entityId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khách hàng"));
    }

    private Supplier resolveSupplier(Integer entityId) {
        return supplierRepository.findById(entityId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp"));
    }

    private void validateRequestShape(DebtOffsetRequest request) {
        if (request == null || request.getPartyType() == null || request.getPartyType().isBlank()) {
            throw new IllegalArgumentException("Thiếu loại đối tượng");
        }
        if (request.getEntityId() == null) {
            throw new IllegalArgumentException("Thiếu đối tượng công nợ");
        }
    }

    private <T> List<DebtOffsetLineRequest> normalizeAllocations(List<DebtOffsetLineRequest> raw,
                                                                 Map<Integer, T> allowed,
                                                                 Function<T, BigDecimal> remainingFn) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(line -> line != null && line.getDocumentId() != null && isPositive(line.getAmount()))
                .peek(line -> {
                    T document = allowed.get(line.getDocumentId());
                    if (document == null) {
                        throw new IllegalArgumentException("Chứng từ không hợp lệ hoặc không thuộc đối tượng");
                    }
                    BigDecimal remaining = remainingFn.apply(document);
                    if (line.getAmount().setScale(2, RoundingMode.HALF_UP)
                            .compareTo(remaining.setScale(2, RoundingMode.HALF_UP)) > 0) {
                        throw new IllegalArgumentException("Số tiền bù trừ vượt quá số còn lại của chứng từ");
                    }
                })
                .map(line -> {
                    DebtOffsetLineRequest normalized = new DebtOffsetLineRequest();
                    normalized.setDocumentId(line.getDocumentId());
                    normalized.setAmount(line.getAmount().setScale(2, RoundingMode.HALF_UP));
                    return normalized;
                })
                .toList();
    }

    private BigDecimal sumAllocations(List<DebtOffsetLineRequest> lines) {
        return lines.stream()
                .map(DebtOffsetLineRequest::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Instant nowVn() {
        LocalDateTime wallClock = LocalDateTime.now(VN_ZONE);
        return wallClock.toInstant(ZoneOffset.UTC);
    }

    private String generateIncomeCode() {
        Integer nextId = incomeRepository.findAll().stream()
                .map(Income::getId)
                .max(Integer::compareTo)
                .map(id -> id + 1)
                .orElse(1);
        return formatIncomeCode(nextId);
    }

    private String formatIncomeCode(Integer id) {
        return "PT-" + String.format(Locale.ROOT, "%06d", id);
    }

    private String generateExpenseCode() {
        Integer nextId = expenseRepository.findAll().stream()
                .map(Expense::getId)
                .max(Integer::compareTo)
                .map(id -> id + 1)
                .orElse(1);
        return formatExpenseCode(nextId);
    }

    private String formatExpenseCode(Integer id) {
        return "PC-" + String.format(Locale.ROOT, "%06d", id);
    }

    private String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "0đ";
        }
        return amount.stripTrailingZeros().toPlainString() + "đ";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
