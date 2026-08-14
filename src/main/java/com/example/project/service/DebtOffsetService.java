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
import org.hibernate.Interceptor;
import org.hibernate.type.Type;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
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

/**
 * Bù trừ công nợ thủ công (chỉ Owner) — cấn cho nợ với nợ của <em>cùng</em> đối tượng
 * ({@code /owner/debts/offset/**}). Số dư đọc từ {@link DebtService}; service này ghi cặp
 * phiếu thu/chi {@link Income}/{@link Expense} xử lý hai bên mà không qua tiền mặt / chuyển khoản.
 *
 * <p><strong>Hình thức thanh toán.</strong> Mọi phiếu bù trừ tạo thẳng ở
 * {@code Hoàn thành} / {@link ExpenseStatus#COMPLETED}, toàn bộ trên {@code paidByCredit}, không
 * tiền mặt/chuyển khoản ({@link #baseCreditIncome}, {@link #baseCreditExpense}).
 * {@link #OFFSET_REASON} đánh dấu để không cho hủy — kiểm tra qua
 * {@link #assertIncomeNotCancellable} và {@link SlipCancelInterceptor}.</p>
 *
 * <p><strong>Theo loại đối tượng.</strong></p>
 * <ul>
 *   <li><strong>Cho nợ KH</strong> — hóa đơn còn nợ; giảm {@code Invoice.debtAmount} qua
 *       {@link #reduceInvoiceDebt}.</li>
 *   <li><strong>Nợ KH</strong> — phiếu trả đã duyệt ({@link ReturnStatus#DEBT}); phiếu chi loại
 *       {@link ExpenseType#RETURN_REFUND_PAYOUT}.</li>
 *   <li><strong>Cho nợ NCC</strong> — phiếu trả NCC đã duyệt; phiếu thu gắn {@code Return}.</li>
 *   <li><strong>Nợ NCC</strong> — nợ phiếu nhập; phiếu chi {@link ExpenseType#GOODS_PAYMENT} và
 *       {@link PurchaseinvoiceService#applyPayment}.</li>
 * </ul>
 *
 * <p><strong>Kiểm tra.</strong> Tổng phân bổ cho nợ phải bằng tổng phân bổ nợ
 * ({@link #applyOffset}). Mỗi dòng không vượt số còn lại của chứng từ, trừ phần đã giữ chỗ trên
 * phiếu chi còn hiệu lực ({@link #committedForPurchase}, {@link #disbursedForReturn}).</p>
 */
@Service
@Import(DebtOffsetService.HibernateConfiguration.class)
public class DebtOffsetService {

    private static final String PARTY_CUSTOMER = "CUSTOMER";
    private static final String PARTY_SUPPLIER = "SUPPLIER";
    private static final String INCOME_STATUS_COMPLETED = "Hoàn thành";
    private static final String INCOME_STATUS_REJECTED = "Từ chối";
    private static final String INCOME_STATUS_CANCELLED = "Đã hủy";
    private static final String STATUS_PROPERTY = "status";
    private static final String INVOICE_STATUS_DEBT = "Còn nợ";
    private static final String INVOICE_STATUS_COMPLETED = "Hoàn thành";
    /** Lý do ghi trên mọi phiếu bù trừ — dùng để nhận diện và chặn hủy. */
    static final String OFFSET_REASON = "Bù trừ công nợ";
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
    private final IncomeService incomeService;

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
                             InvoiceService invoiceService,
                             IncomeService incomeService) {
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
        this.incomeService = incomeService;
    }

    // ------------------------------------------------------------------ màn bù trừ (đọc)

    /**
     * Dựng form bù trừ cho Owner. Ném lỗi nếu đối tượng chỉ có một bên (chỉ cho nợ hoặc chỉ nợ)
     * — bù trừ cần cả hai.
     */
    @Transactional(readOnly = true)
    public DebtOffsetPageResponse getOffsetPage(String partyType, Integer entityId) {
        ReceivableDetailResponse receivable = debtService.getReceivableDetail(partyType, entityId);
        PayableDetailResponse payable = debtService.getPayableDetail(partyType, entityId);

        List<PayableLineResponse> offsetPayableLines = offsetPayableLines(payable, partyType);
        if (!isPositive(receivable.getTotalReceivable()) || !isPositive(sumOffsetablePayable(offsetPayableLines))) {
            throw new IllegalArgumentException(
                    "Chỉ có thể bù trừ khi đối tượng vừa có nợ vừa có cho nợ");
        }

        BigDecimal totalPayableOffsetable = sumOffsetablePayable(offsetPayableLines);

        BigDecimal maxOffset = receivable.getTotalReceivable()
                .min(totalPayableOffsetable)
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
                offsetPayableLines
        );
    }

    /** Dòng nợ với {@code remainingCreatable} tính lại cho bù trừ (trừ phiếu đã giữ chỗ). */
    private List<PayableLineResponse> offsetPayableLines(PayableDetailResponse payable, String partyType) {
        return payable.getLines().stream()
                .map(line -> new PayableLineResponse(
                        line.getId(),
                        line.getCode(),
                        line.getDateDisplay(),
                        line.getPayableAmount(),
                        line.getDetail(),
                        offsetablePayableAmount(line.getId(), partyType),
                        List.of()))
                .filter(line -> isPositive(line.getRemainingCreatable()))
                .toList();
    }

    /** Tổng {@code remainingCreatable} trên các dòng nợ có thể bù trừ. */
    private BigDecimal sumOffsetablePayable(List<PayableLineResponse> lines) {
        return lines.stream()
                .map(PayableLineResponse::getRemainingCreatable)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Số còn lại trên một chứng từ có thể dùng trong lần bù trừ này. */
    private BigDecimal offsetablePayableAmount(Integer documentId, String partyType) {
        if (documentId == null) {
            return BigDecimal.ZERO;
        }
        if (PARTY_CUSTOMER.equals(partyType)) {
            Return ret = returnRepository.findById(documentId).orElse(null);
            if (ret == null) {
                return BigDecimal.ZERO;
            }
            return cashRefundAmount(ret)
                    .subtract(disbursedForReturn(documentId))
                    .max(BigDecimal.ZERO);
        }
        if (PARTY_SUPPLIER.equals(partyType)) {
            Purchaseinvoice purchase = purchaseinvoiceRepository.findById(documentId).orElse(null);
            if (purchase == null) {
                return BigDecimal.ZERO;
            }
            return purchaseinvoiceService.remainingDebt(purchase)
                    .subtract(committedForPurchase(documentId))
                    .max(BigDecimal.ZERO);
        }
        return BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------ thực hiện bù trừ (ghi)

    /**
     * Cấn cho nợ với nợ cùng đối tượng bằng cặp phiếu thu/chi chỉ dùng {@code paidByCredit}
     * (không tiền mặt/chuyển khoản). Mỗi phiếu tạo ở {@code Hoàn thành} /
     * {@link ExpenseStatus#COMPLETED} và không thể hủy sau đó.
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
        Map<Integer, PayableLineResponse> payableById = offsetPayableLines(payable, partyType).stream()
                .collect(Collectors.toMap(PayableLineResponse::getId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));

        List<DebtOffsetLineRequest> receivableAllocations = normalizeAllocations(
                request.getReceivableLines(), receivableById, ReceivableLineResponse::getReceivableAmount);
        List<DebtOffsetLineRequest> payableAllocations = normalizeAllocations(
                request.getPayableLines(), payableById, PayableLineResponse::getRemainingCreatable);

        BigDecimal receivableTotal = sumAllocations(receivableAllocations);
        BigDecimal payableTotal = sumAllocations(payableAllocations);
        BigDecimal totalPayableOffsetable = sumOffsetablePayable(payableById.values().stream().toList());

        if (!isPositive(receivableTotal)) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một khoản cho nợ để bù trừ");
        }
        if (receivableTotal.compareTo(payableTotal) != 0) {
            throw new IllegalArgumentException(
                    "Tổng bù trừ cho nợ (" + formatMoney(receivableTotal)
                            + ") phải bằng tổng bù trừ nợ (" + formatMoney(payableTotal) + ")");
        }
        if (receivableTotal.compareTo(receivable.getTotalReceivable()) > 0
                || payableTotal.compareTo(totalPayableOffsetable) > 0) {
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

    // ------------------------------------------------------------------ tạo phiếu (từng nhánh)

    /** Tạo phiếu thu bù trừ cho nợ hóa đơn KH và giảm {@code debtAmount}. */
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

    /** Tạo phiếu chi bù trừ nợ hoàn tiền phiếu trả KH. */
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

    /** Tạo phiếu thu bù trừ cho nợ phiếu trả NCC. */
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

    /** Tạo phiếu chi bù trừ nợ phiếu nhập và cập nhật {@code paid} qua {@link PurchaseinvoiceService}. */
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

    /** Mẫu phiếu thu bù trừ — chỉ cấn trừ, hoàn thành ngay. */
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

    /** Mẫu phiếu chi bù trừ — chỉ cấn trừ, hoàn thành ngay. */
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

    /** Lưu phiếu thu — sinh mã PT tạm rồi cập nhật mã chính thức sau khi có id. */
    private void persistIncome(Income income) {
        income.setIncomeCode(generateIncomeCode());
        Income saved = incomeRepository.save(income);
        saved.setIncomeCode(formatIncomeCode(saved.getId()));
        incomeRepository.save(saved);
    }

    /** Lưu phiếu chi — sinh mã PC tạm rồi cập nhật mã chính thức sau khi có id. */
    private void persistExpense(Expense expense) {
        expense.setExpenseCode(generateExpenseCode());
        Expense saved = expenseRepository.save(expense);
        saved.setExpenseCode(formatExpenseCode(saved.getId()));
        expenseRepository.save(saved);
    }

    /** Nhánh cho nợ KH — giảm {@code Invoice.debtAmount}, đổi trạng thái khi hết nợ. */
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

    // ------------------------------------------------------------------ kiểm tra dữ liệu

    /** Kiểm tra hóa đơn thuộc KH đang bù trừ và số tiền không vượt cho nợ. */
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

    /** Kiểm tra phiếu trả KH đã duyệt và số tiền không vượt phần còn phải hoàn. */
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

    /** Kiểm tra phiếu trả NCC đã duyệt thuộc NCC đang bù trừ và số tiền hợp lệ. */
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
        BigDecimal remaining = incomeService.remainingCollectibleForSupplierReturn(ret.getId());
        if (amount.setScale(2, RoundingMode.HALF_UP).compareTo(remaining.setScale(2, RoundingMode.HALF_UP)) > 0) {
            throw new IllegalArgumentException(
                    "Số tiền bù trừ vượt quá phần NCC còn phải hoàn của phiếu " + ret.getReturnCode());
        }
    }

    /** Kiểm tra phiếu nhập thuộc NCC đang bù trừ và số tiền không vượt nợ còn lại. */
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

    // ------------------------------------------------------------------ đã chi / đã giữ chỗ

    /** Tổng tiền phiếu chi đã chi thực ({@link ExpenseStatus#COMPLETED}) cho một phiếu trả. */
    private BigDecimal disbursedForReturn(Integer returnId) {
        return expenseRepository.findAll().stream()
                .filter(expense -> !ExpenseStatus.REJECTED.equals(expense.getStatus()))
                .filter(expense -> !ExpenseStatus.CANCELLED.equals(expense.getStatus()))
                .filter(expense -> expense.getReturnID() != null
                        && returnId.equals(expense.getReturnID().getId()))
                .map(this::disbursedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Phần phiếu chi còn hiệu lực chưa chi thực cho phiếu nhập — giữ chỗ đến khi
     * {@link ExpenseStatus#COMPLETED}.
     */
    private BigDecimal committedForPurchase(Integer purchaseId) {
        return expenseRepository.findAll().stream()
                .filter(expense -> !ExpenseStatus.REJECTED.equals(expense.getStatus()))
                .filter(expense -> !ExpenseStatus.CANCELLED.equals(expense.getStatus()))
                .filter(expense -> expense.getPurchaseID() != null
                        && purchaseId.equals(expense.getPurchaseID().getId()))
                .map(expense -> nullToZero(expense.getAmount()).subtract(disbursedAmount(expense)).max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Giống {@link ExpenseService}: chỉ {@link ExpenseStatus#COMPLETED} mới tính là đã chi. */
    private BigDecimal disbursedAmount(Expense expense) {
        return ExpenseStatus.COMPLETED.equals(expense.getStatus())
                ? nullToZero(expense.getPaid())
                : BigDecimal.ZERO;
    }

    /** Phần hoàn tiền mặt sau khi trừ số đã bù trừ công nợ lúc duyệt phiếu trả. */
    private BigDecimal cashRefundAmount(Return ret) {
        return nullToZero(ret.getTotalRefund())
                .subtract(nullToZero(ret.getOffsetDebtAmount()))
                .max(BigDecimal.ZERO);
    }

    /** Lấy khách hàng từ hóa đơn gốc của phiếu trả — có thể null (khách lẻ). */
    private Customer customerOf(Return ret) {
        Invoice invoice = ret.getInvoiceID();
        return invoice != null ? invoice.getCustomerID() : null;
    }

    /** {@code entityId == 0} là khách lẻ — không có dòng {@link Customer} trong DB. */
    private Customer resolveCustomer(Integer entityId) {
        if (entityId != null && entityId == 0) {
            return null;
        }
        return customerRepository.findById(entityId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khách hàng"));
    }

    /** Nạp NCC từ id — ném lỗi nếu không tồn tại. */
    private Supplier resolveSupplier(Integer entityId) {
        return supplierRepository.findById(entityId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp"));
    }

    /** Kiểm tra form POST có đủ {@code partyType} và {@code entityId}. */
    private void validateRequestShape(DebtOffsetRequest request) {
        if (request == null || request.getPartyType() == null || request.getPartyType().isBlank()) {
            throw new IllegalArgumentException("Thiếu loại đối tượng");
        }
        if (request.getEntityId() == null) {
            throw new IllegalArgumentException("Thiếu đối tượng công nợ");
        }
    }

    /**
     * Lọc dòng phân bổ hợp lệ từ form — bỏ dòng trống, kiểm tra chứng từ thuộc đối tượng
     * và số tiền không vượt số còn lại.
     */
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
                    BigDecimal submitted = line.getAmount().setScale(2, RoundingMode.HALF_UP);
                    BigDecimal capped = remaining.setScale(2, RoundingMode.HALF_UP);
                    if (submitted.compareTo(capped) > 0) {
                        throw new IllegalArgumentException(
                                "Số tiền bù trừ vượt quá số còn lại của chứng từ ("
                                        + formatMoney(capped) + ")");
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

    /** Cộng tổng số tiền trên các dòng phân bổ bù trừ. */
    private BigDecimal sumAllocations(List<DebtOffsetLineRequest> lines) {
        return lines.stream()
                .map(DebtOffsetLineRequest::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Thời điểm hiện tại theo giờ VN, lưu dạng Instant UTC (giống {@link DebtService}). */
    private Instant nowVn() {
        LocalDateTime wallClock = LocalDateTime.now(VN_ZONE);
        return wallClock.toInstant(ZoneOffset.UTC);
    }

    /** Mã phiếu thu tạm trước khi lưu — cập nhật lại sau khi có id thật. */
    private String generateIncomeCode() {
        Integer nextId = incomeRepository.findAll().stream()
                .map(Income::getId)
                .max(Integer::compareTo)
                .map(id -> id + 1)
                .orElse(1);
        return formatIncomeCode(nextId);
    }

    /** Sinh mã phiếu thu dạng PT-000001 từ id. */
    private String formatIncomeCode(Integer id) {
        return "PT-" + String.format(Locale.ROOT, "%06d", id);
    }

    /** Mã phiếu chi tạm trước khi lưu — cập nhật lại sau khi có id thật. */
    private String generateExpenseCode() {
        Integer nextId = expenseRepository.findAll().stream()
                .map(Expense::getId)
                .max(Integer::compareTo)
                .map(id -> id + 1)
                .orElse(1);
        return formatExpenseCode(nextId);
    }

    /** Sinh mã phiếu chi dạng PC-000001 từ id. */
    private String formatExpenseCode(Integer id) {
        return "PC-" + String.format(Locale.ROOT, "%06d", id);
    }

    /** Định dạng tiền VND trong thông báo lỗi. */
    private String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "0đ";
        }
        long vnd = amount.setScale(0, RoundingMode.HALF_UP).longValue();
        return String.format(Locale.forLanguageTag("vi-VN"), "%,dđ", vnd);
    }

    /** Trim chuỗi — trả null nếu rỗng. */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Kiểm tra số dương (&gt; 0). */
    private boolean isPositive(BigDecimal value) {
        return isPositiveAmount(value);
    }

    /** Null-safe — trả {@link BigDecimal#ZERO} nếu null. */
    private BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------ chặn hủy phiếu bù trừ (Hibernate interceptor)

    /** Đăng ký {@link SlipCancelInterceptor} với Hibernate — chặn hủy phiếu bù trừ ở tầng flush. */
    static HibernatePropertiesCustomizer slipCancelInterceptorCustomizer() {
        return hibernateProperties ->
                hibernateProperties.put("hibernate.session_factory.interceptor", SlipCancelInterceptor.INSTANCE);
    }

    /** {@code IncomeService} gọi để chặn hủy phiếu thu bù trừ tạo bởi {@link #applyOffset}. */
    public static boolean isDebtOffsetIncome(Income income) {
        return isDebtOffsetSlip(income);
    }

    /** {@code IncomeService} gọi trước hủy — ném lỗi nếu là phiếu thu bù trừ. */
    public static void assertIncomeNotCancellable(Income income) {
        if (isDebtOffsetSlip(income)) {
            throw new IllegalArgumentException("Phiếu thu bù trừ công nợ không thể hủy");
        }
    }

    /** Nhận diện phiếu thu do {@link #applyOffset} tạo — lý do và chỉ thanh toán bằng cấn trừ. */
    private static boolean isDebtOffsetSlip(Income income) {
        return matchesOffsetPayment(income.getReason(),
                income.getPaidByCash(), income.getPaidByBanking(), income.getPaidByCredit());
    }

    /** Nhận diện phiếu chi do {@link #applyOffset} tạo — lý do và chỉ thanh toán bằng cấn trừ. */
    private static boolean isDebtOffsetSlip(Expense expense) {
        return matchesOffsetPayment(expense.getReason(),
                expense.getPaidByCash(), expense.getPaidByBanking(), expense.getPaidByCredit());
    }

    /** Khớp {@link #OFFSET_REASON} và toàn bộ số tiền nằm trên {@code paidByCredit}. */
    private static boolean matchesOffsetPayment(String reason,
                                                BigDecimal paidByCash,
                                                BigDecimal paidByBanking,
                                                BigDecimal paidByCredit) {
        if (!OFFSET_REASON.equals(reason)) {
            return false;
        }
        return isPositiveAmount(paidByCredit)
                && !isPositiveAmount(paidByCash)
                && !isPositiveAmount(paidByBanking);
    }

    /** Kiểm tra số dương (&gt; 0) — dùng trong interceptor static. */
    private static boolean isPositiveAmount(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /** Tìm vị trí tên thuộc tính trong mảng Hibernate flush — trả -1 nếu không có. */
    private static int indexOf(String[] propertyNames, String target) {
        for (int i = 0; i < propertyNames.length; i++) {
            if (target.equals(propertyNames[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Chặn hủy phiếu thu/chi do {@link #applyOffset} tạo — không sửa {@code IncomeService} /
     * {@code ExpenseService}.
     */
    private static final class SlipCancelInterceptor implements Interceptor, Serializable {

        static final SlipCancelInterceptor INSTANCE = new SlipCancelInterceptor();

        private SlipCancelInterceptor() {
        }

        /** Hibernate callback — ném lỗi khi cố đổi trạng thái phiếu bù trừ sang hủy. */
        @Override
        public boolean onFlushDirty(Object entity,
                                    Object id,
                                    Object[] currentState,
                                    Object[] previousState,
                                    String[] propertyNames,
                                    Type[] types) {
            int statusIndex = indexOf(propertyNames, STATUS_PROPERTY);
            if (statusIndex < 0 || currentState[statusIndex] == null) {
                return false;
            }
            String newStatus = String.valueOf(currentState[statusIndex]);
            String oldStatus = previousState[statusIndex] == null
                    ? null
                    : String.valueOf(previousState[statusIndex]);
            if (Objects.equals(newStatus, oldStatus)) {
                return false;
            }

            if (entity instanceof Income income && isDebtOffsetSlip(income)) {
                if (INCOME_STATUS_CANCELLED.equals(newStatus)) {
                    throw new IllegalArgumentException("Phiếu thu bù trừ công nợ không thể hủy");
                }
            } else if (entity instanceof Expense expense && isDebtOffsetSlip(expense)) {
                if (ExpenseStatus.CANCELLED.equals(newStatus)) {
                    throw new IllegalArgumentException("Phiếu chi bù trừ công nợ không thể hủy");
                }
            }
            return false;
        }
    }

    @Configuration
    static class HibernateConfiguration {

        /** Bean đăng ký interceptor chặn hủy phiếu bù trừ khi khởi tạo Hibernate. */
        @Bean
        static HibernatePropertiesCustomizer debtOffsetSlipCancelInterceptorCustomizer() {
            return slipCancelInterceptorCustomizer();
        }
    }
}
