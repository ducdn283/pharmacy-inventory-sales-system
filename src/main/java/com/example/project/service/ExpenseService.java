package com.example.project.service;

import com.example.project.constant.ExpenseStatus;
import com.example.project.constant.ExpenseType;
import com.example.project.constant.ReturnStatus;
import com.example.project.constant.RoleConstants;
import com.example.project.dto.request.ExpenseCreateRequest;
import com.example.project.dto.response.ExpenseDetailResponse;
import com.example.project.dto.response.ExpenseListItemResponse;
import com.example.project.dto.response.ExpenseReferenceOptionResponse;
import com.example.project.dto.response.ExpenseStatsResponse;
import com.example.project.entity.Account;
import com.example.project.entity.Customer;
import com.example.project.entity.Expense;
import com.example.project.entity.Invoice;
import com.example.project.entity.Purchaseinvoice;
import com.example.project.entity.Return;
import com.example.project.entity.Shiftreport;
import com.example.project.entity.Supplier;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.AccountpermissionRepository;
import com.example.project.repository.ExpenseRepository;
import com.example.project.repository.ReturnRepository;
import org.springframework.context.annotation.Lazy;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phiếu chi — màn kiểm soát dòng tiền chi ra của nhà thuốc. Đa số phiếu là nhập tay thuần, nhưng
 * phiếu {@link ExpenseType#RETURN_REFUND_PAYOUT} là bước chi tiền thật của một lượt trả hàng
 * <em>khách</em>, và phiếu {@link ExpenseType#GOODS_PAYMENT} là bước trả tiền thật cho một phiếu
 * nhập.
 *
 * <p><strong>Trả hàng khách vs. trả hàng NCC.</strong> Phân biệt bằng FK:
 * {@code invoiceID != null} là trả hàng khách, {@code purchaseID != null && invoiceID == null} là
 * trả hàng NCC. Expense chỉ xử lý loại đầu (nhà thuốc chi tiền hoàn khách); loại sau là tiền
 * <em>vào</em>, thuộc {@code IncomeService.listSupplierReturns()}. Trả hàng cho NCC không tốn tiền
 * mặt nên nằm ngoài phạm vi module này.</p>
 *
 * <p><strong>Một phiếu trả hàng chỉ TÍNH tiền, không CHI tiền.</strong> {@code Return} chỉ có
 * {@code totalRefund}, không tách tiền mặt/chuyển khoản/cấn trừ. Quyết định tiền chi ra bằng cách
 * nào — và ghi nhận việc đó — hoàn toàn là việc của module này, nên {@link #listCustomerReturns()}
 * là cổng duy nhất để hoàn tiền khách. Một phiếu chi cũng là nguồn <em>duy nhất</em> ghi nhận tiền
 * mặt ra khỏi một ca làm việc.</p>
 *
 * <p><strong>Trả tiền NCC.</strong> Phiếu {@link ExpenseType#GOODS_PAYMENT} có thể gắn một
 * {@code PurchaseInvoice} và là bước trả tiền thật cho nó. Chỉ khi {@link #confirmPayment} chạy,
 * tiền mới thực sự được cộng vào {@code Purchaseinvoice.paid} qua
 * {@link PurchaseinvoiceService#applyPayment(Integer, java.math.BigDecimal)} (hàm này tự tính lại
 * và lưu status của phiếu nhập trong cùng transaction). Tiền chỉ tính là đã chi khi phiếu ở trạng
 * thái {@link ExpenseStatus#COMPLETED} — xem {@link #disbursedAmount}.</p>
 *
 * <p><strong>Gắn ca làm việc.</strong> Phiếu được đóng dấu ca đang mở của người lập tại đúng lúc
 * tiền THỰC SỰ được chi (không phải lúc duyệt) — xem {@link #attachOpenShift}. Tổng
 * {@code totalCashOut} của một ca là tổng {@code paidByCash} các phiếu chi của nó, nên bước đóng
 * dấu này là bắt buộc: phiếu không được đóng dấu là tiền ca không thể đối soát được.</p>
 *
 * <p><strong>Duyệt và chi tiền thật là hai bước tách biệt.</strong> {@link #createExpense}/
 * {@link #submit}/{@link #approve} chỉ đưa phiếu tới {@link ExpenseStatus#AWAITING_PAYMENT} — đã
 * được duyệt nhưng tiền chưa rời quỹ/tài khoản. Chỉ {@link #confirmPayment} mới đưa phiếu tới
 * {@link ExpenseStatus#COMPLETED} và kích hoạt các side-effect chi tiền ở trên — mặc định chỉ Chủ
 * nhà thuốc được gọi, trừ ngoại lệ ở đoạn dưới. {@link ExpenseStatus#CANCELLED} chỉ hủy được trước
 * khi tiền thực chi — xem {@link #cancel} — sau đó thì không, vì lúc này đã có tiền thật cần đảo
 * ngược.</p>
 *
 * <p><strong>Phiếu nhỏ của Dược sĩ tự phục vụ trọn vòng.</strong> Một phiếu do Dược sĩ lập dưới
 * ngưỡng {@link ExpenseType#PHARMACIST_AUTO_APPROVE_LIMIT} tự động sang thẳng
 * {@link ExpenseStatus#AWAITING_PAYMENT} lúc tạo (không cần Chủ nhà thuốc duyệt), và chính Dược sĩ
 * đó cũng được tự {@link #confirmPayment(Integer, Integer) xác nhận thanh toán} phiếu của mình — họ
 * là người lập và cũng là người trực tiếp chi tiền. Từ ngưỡng trở lên, cả hai bước vẫn cần Chủ nhà
 * thuốc: phiếu rơi vào {@link ExpenseStatus#PENDING} như mọi phiếu không phải Owner khác, và chỉ
 * overload không giới hạn {@link #confirmPayment(Integer)} mới đưa được phiếu tới
 * {@link ExpenseStatus#COMPLETED}.</p>
 *
 * <p><strong>Tiền mặt/chuyển khoản bị khoá theo vai trò.</strong> Owner được chia tự do cả hai. Dược
 * sĩ chỉ được chi tiền mặt — ca chỉ mở với quỹ tiền mặt cố định, không có gì để đối soát một khoản
 * chuyển khoản. Kế toán chỉ được chi chuyển khoản — không trực tiếp cầm tiền mặt và không có ca để
 * đối soát. Xem {@link #resolveSplit}.</p>
 */
@Service
public class ExpenseService {

    private static final String PAYMENT_CASH = "CASH";
    private static final String PAYMENT_BANKING = "BANKING";
    private static final String PAYMENT_MIXED = "MIXED";
    private static final String PAYMENT_CREDIT = "CREDIT";

    private final ExpenseRepository expenseRepository;
    private final AccountRepository accountRepository;
    private final ReturnRepository returnRepository;
    private final AccountpermissionRepository accountpermissionRepository;
    private final PurchaseinvoiceService purchaseinvoiceService;
    private final ShiftreportService shiftreportService;
    private final WorkflowNotificationService workflowNotificationService;
    private final FinancialsettingService financialsettingService;
    // @Lazy để phá vòng lặp bean (ReturnService cũng inject ExpenseService). Chỉ dùng ở
    // confirmPayment() để báo ReturnService đồng bộ lại trạng thái sau khi tiền thực chi.
    private final ReturnService returnService;

    public ExpenseService(ExpenseRepository expenseRepository,
                          AccountRepository accountRepository,
                          ReturnRepository returnRepository,
                          AccountpermissionRepository accountpermissionRepository,
                          PurchaseinvoiceService purchaseinvoiceService,
                          ShiftreportService shiftreportService,
                          WorkflowNotificationService workflowNotificationService,
                          FinancialsettingService financialsettingService,
                          @Lazy ReturnService returnService) {
        this.expenseRepository = expenseRepository;
        this.accountRepository = accountRepository;
        this.returnRepository = returnRepository;
        this.accountpermissionRepository = accountpermissionRepository;
        this.purchaseinvoiceService = purchaseinvoiceService;
        this.shiftreportService = shiftreportService;
        this.workflowNotificationService = workflowNotificationService;
        this.financialsettingService = financialsettingService;
        this.returnService = returnService;
    }

    // ------------------------------------------------------------------ list / search

    // Tìm kiếm phiếu chi không giới hạn theo người lập — ủy quyền cho overload đầy đủ bên dưới.
    @Transactional(readOnly = true)
    public Page<ExpenseListItemResponse> search(String keyword,
                                                 String fromDate,
                                                 String toDate,
                                                 String expenseType,
                                                 String status,
                                                 Pageable pageable) {
        return search(keyword, fromDate, toDate, expenseType, status, pageable, null);
    }

    // Lọc + sắp xếp (mới nhất trước) + phân trang danh sách phiếu chi theo từ khóa/ngày/loại/trạng
    // thái/người lập (applicantAccountId null = không giới hạn, dùng cho Dược sĩ chỉ xem phiếu của mình).
    @Transactional(readOnly = true)
    public Page<ExpenseListItemResponse> search(String keyword,
                                                 String fromDate,
                                                 String toDate,
                                                 String expenseType,
                                                 String status,
                                                 Pageable pageable,
                                                 Integer applicantAccountId) {
        final String normalizedKeyword = normalize(keyword);
        final LocalDate from = parseDate(fromDate);
        final LocalDate to = parseDate(toDate);

        List<Expense> expenses = expenseRepository.findAll();

        List<ExpenseListItemResponse> filtered = expenses.stream()
                .filter(expense -> belongsToApplicant(expense, applicantAccountId))
                .filter(expense -> matchesKeyword(expense, normalizedKeyword))
                .filter(expense -> matchesDate(expense, from, to))
                .filter(expense -> expenseType == null || expenseType.isBlank()
                        || expenseType.equals(expense.getExpenseType()))
                .filter(expense -> status == null || status.isBlank() || status.equals(expense.getStatus()))
                // Mới nhất lên đầu. Phải có mốc phụ theo id vì resolveDate() cắt về đầu ngày, nên MỌI
                // phiếu trong cùng một ngày có date y hệt nhau: chỉ so date thôi thì các phiếu hôm nay
                // hoà nhau và giữ nguyên thứ tự findAll() trả về, tức là phiếu cũ nhất nằm trên cùng.
                .sorted(Comparator.comparing(Expense::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Expense::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toListItem)
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());

        List<ExpenseListItemResponse> content = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    // Thống kê tổng quan phiếu chi cho toàn bộ hệ thống (không giới hạn người lập).
    @Transactional(readOnly = true)
    public ExpenseStatsResponse getStats() {
        return getStats(null);
    }

    // Thống kê tổng quan phiếu chi cho trang danh sách: số phiếu/tổng tiền đã chi trong tháng,
    // số phiếu đang chờ duyệt, số phiếu đang chờ thanh toán — lọc theo người lập nếu có truyền vào.
    @Transactional(readOnly = true)
    public ExpenseStatsResponse getStats(Integer applicantAccountId) {
        List<Expense> expenses = expenseRepository.findAll().stream()
                .filter(expense -> belongsToApplicant(expense, applicantAccountId))
                .toList();
        YearMonth currentMonth = YearMonth.now();

        List<Expense> thisMonth = expenses.stream()
                .filter(expense -> expense.getDate() != null)
                .filter(expense -> YearMonth.from(toLocalDate(expense.getDate())).equals(currentMonth))
                .toList();

        // Chỉ COMPLETED là tiền THẬT đã rời quỹ (xem confirmPayment) — DRAFT/PENDING/
        // AWAITING_PAYMENT/CANCELLED đều không được cộng vào "Đã chi trong tháng".
        BigDecimal monthlyPaidTotal = thisMonth.stream()
                .filter(expense -> ExpenseStatus.COMPLETED.equals(expense.getStatus()))
                .map(Expense::getPaid)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long pendingCount = countByStatus(expenses, ExpenseStatus.PENDING);
        long awaitingPaymentCount = countByStatus(expenses, ExpenseStatus.AWAITING_PAYMENT);

        return new ExpenseStatsResponse(thisMonth.size(), monthlyPaidTotal, pendingCount, awaitingPaymentCount);
    }

    // Toàn bộ trạng thái hợp lệ, cho dropdown lọc trên màn danh sách.
    public List<String> listStatuses() {
        return ExpenseStatus.ALL;
    }

    // Map loại phiếu chi -> nhãn tiếng Việt, cho dropdown chọn loại.
    public Map<String, String> expenseTypeLabels() {
        return ExpenseType.vietnameseLabels();
    }

    /** Loại nào hiện ô chọn phiếu nhập — trả cho form để JS không lệch khỏi Java. */
    public List<String> purchaseLinkableTypes() {
        return ExpenseType.PURCHASE_LINKABLE;
    }

    // ------------------------------------------------------------------ reference documents

    /**
     * Các phiếu trả hàng của khách còn chờ hoàn tiền, cho ô chọn trên màn tạo phiếu chi. Render sẵn
     * vào trang (không cần AJAX) vì không cần bước "chọn đối tác trước" như Phiếu thu.
     *
     * <p>Một phiếu trả hợp lệ khi thoả cả bốn điều kiện:</p>
     * <ol>
     *   <li>{@code invoiceID != null} — là trả hàng khách, không phải trả hàng NCC;</li>
     *   <li>status là {@link ReturnStatus#DEBT} — đây chính là trạng thái "đã duyệt" (Return không
     *       có bước "Duyệt" riêng vì duyệt một phiếu trả đồng nghĩa nhà thuốc nợ khách tiền);</li>
     *   <li>còn phát sinh tiền hoàn thật — xem {@link #cashRefundAmount};</li>
     *   <li>chưa có phiếu chi nào đang sống trỏ vào nó — xem {@link #committedByReturnId()}.</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public List<ExpenseReferenceOptionResponse> listCustomerReturns() {
        Map<Integer, BigDecimal> committed = committedByReturnId();

        return returnRepository.findAllWithRelations().stream()
                .filter(ret -> ret.getInvoiceID() != null)
                .filter(ret -> ReturnStatus.DEBT.equals(ret.getStatus()))
                // Còn tiền hoàn chưa ai nhận, chứ không phải "chưa có phiếu chi nào" — hoàn tiền
                // chia được nhiều lần, xem committedByReturnId().
                .filter(ret -> availableToRefund(ret, committed).compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(Return::getReturnDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Return::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ret -> new ExpenseReferenceOptionResponse(
                        ret.getId(),
                        ret.getReturnCode(),
                        formatInstant(ret.getReturnDate()),
                        availableToRefund(ret, committed),
                        referenceDetail(ret)))
                .toList();
    }

    /**
     * {@code returnId -> số tiền hoàn}, để màn tạo phiếu tự điền ô tiền (readonly) ngay khi chọn
     * phiếu trả. Dùng map vô hướng thay vì list DTO vì đây là dạng tra cứu an toàn cho
     * {@code th:inline}.
     */
    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> customerReturnAmounts() {
        return amountsById(listCustomerReturns());
    }

    /**
     * {@code returnId -> phần còn phải hoàn thực}, cho màn Return hiển thị đúng "còn phải hoàn bao
     * nhiêu" sau khi trừ các phiếu chi RETURN_REFUND_PAYOUT còn sống đã cam kết — cùng vai trò
     * {@code PurchaseinvoiceService.remainingDebt()} đóng cho {@code Purchaseinvoice.paid}, chỉ
     * khác là Return không có cột tiền-đã-chi riêng nên nguồn sự thật nằm ở Expense. Trả 0 cho một
     * return đã hoàn đủ (kể cả trả hàng NCC — công thức vẫn đúng vì {@link #committedByReturnId()}
     * chỉ đếm phiếu chi thực sự trỏ vào nó, mà chỉ trả hàng khách mới có loại phiếu chi này).
     */
    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> outstandingRefundByReturnId(Collection<Return> returns) {
        Map<Integer, BigDecimal> committed = committedByReturnId();
        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        for (Return ret : returns) {
            if (ret != null && ret.getId() != null) {
                result.put(ret.getId(), availableToRefund(ret, committed));
            }
        }
        return result;
    }

    /**
     * Tổng tiền các phiếu chi RETURN_REFUND_PAYOUT đã THỰC CHI ({@link ExpenseStatus#COMPLETED}) cho
     * một phiếu trả — dùng bởi {@code ReturnService.syncStatusAfterRefundPayment()} để biết khi nào
     * phiếu trả đã được hoàn ĐỦ tiền thật. Khác {@link #committedByReturnId()} (đếm cả phiếu mới chỉ
     * CAM KẾT — DRAFT/PENDING/AWAITING_PAYMENT — chưa chắc tiền đã rời quỹ): dùng con số đó ở đây sẽ
     * chuyển phiếu trả sang Hoàn thành ngay khi phiếu chi được TẠO, trước cả khi Owner xác nhận
     * thanh toán.
     */
    @Transactional(readOnly = true)
    public BigDecimal disbursedRefundAmount(Integer returnId) {
        if (returnId == null) {
            return BigDecimal.ZERO;
        }
        return expenseRepository.findAll().stream()
                .filter(expense -> ExpenseStatus.COMPLETED.equals(expense.getStatus()))
                .filter(expense -> expense.getReturnID() != null
                        && returnId.equals(expense.getReturnID().getId()))
                .map(Expense::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Các phiếu nhập nhà thuốc còn nợ tiền, cho ô chọn trả nợ. Số hiển thị là phần <em>còn có thể
     * cam kết chi</em>, không phải nợ gốc — xem {@link #availableToPay}.
     */
    @Transactional(readOnly = true)
    public List<ExpenseReferenceOptionResponse> listPayablePurchaseInvoices() {
        Map<Integer, BigDecimal> committed = committedByPurchaseId();

        return purchaseinvoiceService.findPayableInvoices().stream()
                .map(invoice -> Map.entry(invoice, availableToPay(invoice, committed)))
                .filter(entry -> entry.getValue().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.<Map.Entry<Purchaseinvoice, BigDecimal>, Instant>comparing(
                                entry -> entry.getKey().getDate(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(entry -> entry.getKey().getId(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(entry -> new ExpenseReferenceOptionResponse(
                        entry.getKey().getId(),
                        entry.getKey().getPurchaseInvoiceCode(),
                        formatInstant(entry.getKey().getDate()),
                        entry.getValue(),
                        purchaseReferenceDetail(entry.getKey())))
                .toList();
    }

    // purchaseId -> số tiền còn có thể cam kết chi, để form tự điền ô tiền khi chọn phiếu nhập.
    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> payablePurchaseInvoiceAmounts() {
        return amountsById(listPayablePurchaseInvoices());
    }

    // Dựng map id -> amount từ danh sách tuỳ chọn tham chiếu, phục vụ tra cứu an toàn trong th:inline.
    private Map<Integer, BigDecimal> amountsById(List<ExpenseReferenceOptionResponse> options) {
        Map<Integer, BigDecimal> amounts = new LinkedHashMap<>();
        for (ExpenseReferenceOptionResponse option : options) {
            amounts.put(option.getId(), option.getAmount());
        }
        return amounts;
    }

    // ------------------------------------------------------------------ detail

    // Lấy chi tiết phiếu chi, không giới hạn theo người lập.
    @Transactional(readOnly = true)
    public ExpenseDetailResponse getDetail(Integer expenseId) {
        return getDetail(expenseId, null);
    }

    // Lấy chi tiết phiếu chi, kiểm tra quyền xem nếu requiredApplicantAccountId được truyền vào (Dược sĩ chỉ xem phiếu của mình).
    @Transactional(readOnly = true)
    public ExpenseDetailResponse getDetail(Integer expenseId, Integer requiredApplicantAccountId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));
        ensureApplicantAccess(expense, requiredApplicantAccountId);
        return toDetail(expense);
    }

    /**
     * Quyền hủy thuộc về tài khoản đã tạo phiếu, không phụ thuộc chức vụ. Chỉ dùng để hiện/ẩn nút
     * trên trang chi tiết; {@link #cancel} tự kiểm lại đúng điều kiện này ở phía server.
     */
    @Transactional(readOnly = true)
    public boolean canCancel(Integer expenseId, Integer currentAccountId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));
        return isApplicant(expense, currentAccountId)
                && !ExpenseStatus.CANCELLED.equals(expense.getStatus())
                && !ExpenseStatus.COMPLETED.equals(expense.getStatus());
    }

    // ------------------------------------------------------------------ create

    /**
     * Tạo phiếu chi mới. {@code asDraft = true} thì luôn lưu {@link ExpenseStatus#DRAFT} bất kể vai
     * trò. Ngược lại: phiếu của Owner tự động sang thẳng {@link ExpenseStatus#AWAITING_PAYMENT}
     * (tiền chưa rời quỹ — xem {@link #confirmPayment}); phiếu của vai trò khác sang
     * {@link ExpenseStatus#PENDING} chờ Owner duyệt. Overload này luôn truyền
     * {@code isPharmacist = false} — cần các quy tắc riêng cho Dược sĩ thì dùng overload 6 tham số.
     */
    @Transactional
    public Integer createExpense(ExpenseCreateRequest request, Integer currentAccountId, boolean isOwner,
                                  boolean asDraft) {
        return createExpense(request, currentAccountId, isOwner, asDraft, false, null);
    }

    /**
     * @param isPharmacist người tạo có phải Dược sĩ không — quyết định cả ngưỡng tự động duyệt
     *                     ({@link ExpenseType#PHARMACIST_AUTO_APPROVE_LIMIT}) lẫn ràng buộc chỉ
     *                     được chi tiền mặt (xem {@link #resolveSplit}).
     */
    @Transactional
    public Integer createExpense(ExpenseCreateRequest request, Integer currentAccountId, boolean isOwner,
                                  boolean asDraft, boolean isPharmacist, String requiredExpenseType) {
        String expenseType = resolveExpenseType(request.getExpenseType());
        if (requiredExpenseType != null && !requiredExpenseType.equals(expenseType)) {
            throw new IllegalArgumentException("Tài khoản này chỉ được tạo phiếu chi hoàn tiền trả hàng");
        }

        // Giải quyết cả hai liên kết trước khi validate: khi phiếu gắn chứng từ thì chứng từ đó
        // quyết định số tiền — giá trị client gửi chỉ để hiển thị, không được tin.
        Return linkedReturn = resolveCustomerReturn(request, expenseType);
        Purchaseinvoice linkedPurchase = resolvePurchaseInvoice(request, expenseType);
        BigDecimal amount = resolveAmount(request, linkedReturn, linkedPurchase);
        if (ExpenseType.RETURN_REFUND_PAYOUT.equals(requiredExpenseType)
                && amount.compareTo(ExpenseType.PHARMACIST_REFUND_LIMIT) >= 0) {
            throw new IllegalArgumentException(
                    "Dược sĩ chỉ được tạo phiếu hoàn tiền trả hàng dưới 500.000đ");
        }

        // Mọi cột NOT NULL (expenseType/reason/amount) phải có giá trị thật kể cả khi lưu nháp —
        // "nháp" ở đây chỉ nghĩa là "chưa gửi duyệt", không phải "dữ liệu chưa đầy đủ".
        validateRequest(request, amount);

        Account applicant = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        Expense expense = new Expense();
        expense.setApplicantID(applicant);
        expense.setExpenseType(expenseType);
        expense.setDate(resolveDate(request.getDate()));
        expense.setReason(request.getReason() != null ? request.getReason().trim() : "");
        expense.setAmount(amount);
        expense.setNote(trimToNull(request.getNote()));

        if (linkedReturn != null) {
            expense.setReturnID(linkedReturn);
            // Suy ra chứ không nhận từ client: người nhận tiền là khách hàng của hóa đơn gốc. Là
            // null với khách lẻ (không có row Customer).
            expense.setCustomerID(customerOf(linkedReturn));
        }

        if (linkedPurchase != null) {
            expense.setPurchaseID(linkedPurchase);
            expense.setSupplierID(linkedPurchase.getSupplierID());
        }

        // Một phiếu là một lần chi: tiền đã chi luôn đúng bằng số tiền của phiếu, không có phiếu
        // "chi thiếu so với chính nó". Chi thiếu so với CHỨNG TỪ thì nằm ở chỗ khác — chứng từ còn nợ.
        boolean canPayCash = isOwner || isPharmacist;
        boolean canPayBanking = !isPharmacist;
        BigDecimal[] split = resolveSplit(request, amount, canPayCash, canPayBanking);
        expense.setPaid(amount);
        expense.setPaidByCash(split[0]);
        expense.setPaidByBanking(split[1]);
        // Expense không có @DynamicInsert nên để null Hibernate sẽ ghi NULL thay vì dùng DEFAULT 0
        // của cột — set tường minh về 0. Chưa có UI nào thu thập phần cấn trừ công nợ.
        expense.setPaidByCredit(BigDecimal.ZERO);

        if (asDraft) {
            expense.setStatus(ExpenseStatus.DRAFT);
        } else if (isOwner || pharmacistAutoApproves(isPharmacist, amount)) {
            applyApproval(expense, applicant);
        } else {
            expense.setStatus(ExpenseStatus.PENDING);
        }

        expense.setExpenseCode(generateCode());
        Expense saved = expenseRepository.save(expense);
        // Ghi lại mã phiếu đúng theo id thật vừa sinh ra (mã ở trên chỉ tạm giữ chỗ theo thứ tự).
        saved.setExpenseCode(formatCode(saved.getId()));

        Expense finalSaved = expenseRepository.save(saved);

        if (ExpenseStatus.PENDING.equals(finalSaved.getStatus())) {
            workflowNotificationService.expensePending(finalSaved);
        }

        return finalSaved.getId();
    }

    /** Gửi một phiếu {@link ExpenseStatus#DRAFT} đi tiếp. */
    @Transactional
    public void submit(Integer expenseId, Integer currentAccountId, boolean isOwner) {
        submit(expenseId, currentAccountId, isOwner, false);
    }

    /**
     * @param isPharmacist giới hạn chỉ được gửi phiếu nháp của chính mình, và theo
     *                      {@link ExpenseType#PHARMACIST_AUTO_APPROVE_LIMIT} cho phép phiếu đủ nhỏ
     *                      bỏ qua {@link ExpenseStatus#PENDING} giống {@link #createExpense}.
     */
    @Transactional
    public void submit(Integer expenseId, Integer currentAccountId, boolean isOwner,
                       boolean isPharmacist) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));
        if (isPharmacist) {
            ensureApplicantAccess(expense, currentAccountId);
        }

        if (!ExpenseStatus.DRAFT.equals(expense.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể gửi duyệt phiếu đang ở trạng thái nháp");
        }

        Account actor = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        boolean autoApproved = isOwner || pharmacistAutoApproves(isPharmacist, expense.getAmount());
        if (autoApproved) {
            applyApproval(expense, actor);
        } else {
            expense.setStatus(ExpenseStatus.PENDING);
        }

        expenseRepository.save(expense);
        if (!autoApproved) {
            workflowNotificationService
                    .expensePending(expense);
        }
    }

    // Chủ nhà thuốc duyệt phiếu đang Chờ duyệt -> chuyển sang Chờ thanh toán, tiền vẫn chưa rời quỹ.
    @Transactional
    public void approve(Integer expenseId, Integer approverAccountId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));

        if (!ExpenseStatus.PENDING.equals(expense.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể duyệt phiếu đang ở trạng thái chờ duyệt");
        }

        Account approver = accountRepository.findById(approverAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        applyApproval(expense, approver);
        expenseRepository.save(expense);
        workflowNotificationService.expenseApproved(expense);
    }

    // Chủ nhà thuốc từ chối phiếu đang Chờ duyệt -> chuyển sang Từ chối.
    @Transactional
    public void reject(Integer expenseId, Integer approverAccountId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));

        if (!ExpenseStatus.PENDING.equals(expense.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể từ chối phiếu đang ở trạng thái chờ duyệt");
        }

        Account approver = accountRepository.findById(approverAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        expense.setStatus(ExpenseStatus.REJECTED);
        expense.setApprovedAt(Instant.now());
        expenseRepository.save(expense);
        workflowNotificationService.expenseRejected(expense);
        // Expense không có cột "rejectedBy" riêng — người từ chối không được lưu tách biệt.
    }

    /**
     * Sửa lỗi nội bộ cho phiếu lập sai — không phải một bút toán đảo ngược thật, chỉ đánh dấu vô
     * hiệu.
     *
     * <p><strong>Chỉ hủy được trước khi tiền thực chi.</strong> Một phiếu chi không sửa/trả góp
     * được sau khi tạo, nên {@code DRAFT}/{@code PENDING}/{@code AWAITING_PAYMENT} vẫn hủy được vì
     * chưa có gì bị chi ra (chưa tác động phiếu nhập/quỹ/ca — xem {@link #confirmPayment}). Một
     * phiếu {@code COMPLETED} là trạng thái cuối: tiền đã thực sự ra khỏi quỹ, hủy lúc này cần một
     * bút toán đảo ngược thật mà hàm này không làm — hãy lập phiếu mới để ghi nhận đúng thực tế.</p>
     */
    @Transactional
    public void cancel(Integer expenseId, String reason, Integer currentAccountId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));
        ensureCancelCreator(expense, currentAccountId);

        if (ExpenseStatus.CANCELLED.equals(expense.getStatus())) {
            throw new IllegalArgumentException("Phiếu chi này đã bị hủy trước đó");
        }
        if (ExpenseStatus.COMPLETED.equals(expense.getStatus())) {
            throw new IllegalArgumentException(
                    "Phiếu chi đã hoàn thành (tiền đã thực chi) không thể hủy nữa");
        }

        expense.setStatus(ExpenseStatus.CANCELLED);
        String trimmedReason = trimToNull(reason);
        if (trimmedReason != null) {
            String existingNote = expense.getNote();
            expense.setNote(existingNote == null || existingNote.isBlank()
                    ? "Lý do hủy: " + trimmedReason
                    : existingNote + " | Lý do hủy: " + trimmedReason);
        }

        expenseRepository.save(expense);
    }

    // ------------------------------------------------------------------ mapping

    /**
     * Cố tình KHÔNG có cột "tình trạng công nợ" ở đây. Một phiếu chi là một lần chi tiền, không mang
     * khái niệm nợ: nợ là thuộc tính của CHỨNG TỪ (phiếu nhập còn phải trả bao nhiêu, phiếu trả hàng
     * còn phải hoàn bao nhiêu), và việc theo dõi nó là của màn Công nợ. Phiếu thu cũng đúng như vậy —
     * {@code IncomeService} không có trường nợ nào trên phiếu, chỉ hiện số còn nợ ở ô chọn chứng từ.
     */
    private ExpenseListItemResponse toListItem(Expense expense) {
        return new ExpenseListItemResponse(
                expense.getId(),
                formatCode(expense.getId()),
                formatInstant(expense.getDate()),
                expense.getExpenseType(),
                ExpenseType.vietnameseName(expense.getExpenseType()),
                expense.getApplicantID() != null ? expense.getApplicantID().getName() : "Không rõ",
                expense.getAmount(),
                expense.getPaid(),
                paymentDisplay(expense.getPaidByCash(), expense.getPaidByBanking(), expense.getPaidByCredit()),
                expense.getStatus(),
                statusCssClass(expense.getStatus())
        );
    }

    // applicantAccountId null = không giới hạn; ngược lại phiếu phải do đúng tài khoản đó lập.
    private boolean belongsToApplicant(Expense expense, Integer applicantAccountId) {
        return applicantAccountId == null
                || expense.getApplicantID() != null
                && applicantAccountId.equals(expense.getApplicantID().getId());
    }

    // Tài khoản này có đúng là người đã lập phiếu chi hay không.
    private boolean isApplicant(Expense expense, Integer accountId) {
        return accountId != null
                && expense.getApplicantID() != null
                && accountId.equals(expense.getApplicantID().getId());
    }

    // Chặn hủy phiếu nếu người thao tác không phải người đã tạo ra nó.
    private void ensureCancelCreator(Expense expense, Integer currentAccountId) {
        if (!isApplicant(expense, currentAccountId)) {
            throw new IllegalArgumentException("Chỉ tài khoản đã tạo phiếu chi này mới có thể hủy phiếu");
        }
    }

    // Chặn xem/thao tác phiếu nếu bị giới hạn theo người lập mà không khớp (Dược sĩ xem phiếu người khác).
    private void ensureApplicantAccess(Expense expense, Integer requiredApplicantAccountId) {
        if (!belongsToApplicant(expense, requiredApplicantAccountId)) {
            throw new IllegalArgumentException("Bạn không có quyền xem hoặc thao tác phiếu chi này");
        }
    }

    /**
     * Hình thức chi, suy ra từ ba cột tiền — cùng cách với {@code IncomeService#paymentDisplay}.
     */
    private String paymentDisplay(BigDecimal paidByCash, BigDecimal paidByBanking, BigDecimal paidByCredit) {
        boolean hasCash = isPositive(paidByCash);
        boolean hasBanking = isPositive(paidByBanking);
        boolean hasCredit = isPositive(paidByCredit);
        if (hasCredit && !hasCash && !hasBanking) {
            return paymentTypeLabel(PAYMENT_CREDIT);
        }
        if (hasCash && hasBanking && !hasCredit) {
            return paymentTypeLabel(PAYMENT_MIXED);
        }
        if (hasBanking && !hasCash && !hasCredit) {
            return paymentTypeLabel(PAYMENT_BANKING);
        }
        if (hasCash && !hasBanking && !hasCredit) {
            return paymentTypeLabel(PAYMENT_CASH);
        }
        StringBuilder parts = new StringBuilder();
        if (hasCash) {
            parts.append(paymentTypeLabel(PAYMENT_CASH));
        }
        if (hasBanking) {
            appendPaymentPart(parts, paymentTypeLabel(PAYMENT_BANKING));
        }
        if (hasCredit) {
            appendPaymentPart(parts, paymentTypeLabel(PAYMENT_CREDIT));
        }
        return parts.isEmpty() ? "—" : parts.toString();
    }

    private String paymentTypeLabel(String code) {
        return switch (code) {
            case PAYMENT_CASH -> "Tiền mặt";
            case PAYMENT_BANKING -> "Chuyển khoản";
            case PAYMENT_MIXED -> "TM + CK";
            case PAYMENT_CREDIT -> "Cấn trừ công nợ";
            default -> code;
        };
    }

    private void appendPaymentPart(StringBuilder parts, String label) {
        if (!parts.isEmpty()) {
            parts.append(" + ");
        }
        parts.append(label);
    }

    private boolean isPositive(BigDecimal value) {
        return nullToZero(value).compareTo(BigDecimal.ZERO) > 0;
    }

    // Chuyển entity Expense sang DTO chi tiết đầy đủ cho trang xem một phiếu chi.
    private ExpenseDetailResponse toDetail(Expense expense) {
        Return linkedReturn = expense.getReturnID();
        Customer customer = expense.getCustomerID();
        Purchaseinvoice linkedPurchase = expense.getPurchaseID();
        Supplier supplier = expense.getSupplierID();
        Shiftreport shift = expense.getShiftReportID();

        return new ExpenseDetailResponse(
                expense.getId(),
                formatCode(expense.getId()),
                formatInstant(expense.getDate()),
                expense.getExpenseType(),
                ExpenseType.vietnameseName(expense.getExpenseType()),
                expense.getApplicantID() != null ? expense.getApplicantID().getName() : "Không rõ",
                expense.getReason(),
                expense.getAmount(),
                expense.getPaid(),
                expense.getPaidByCash(),
                expense.getPaidByBanking(),
                expense.getPaidByCredit(),
                paymentDisplay(expense.getPaidByCash(), expense.getPaidByBanking(), expense.getPaidByCredit()),
                expense.getStatus(),
                statusCssClass(expense.getStatus()),
                approverName(expense),
                formatInstant(expense.getApprovedAt()),
                expense.getNote(),
                linkedReturn != null ? linkedReturn.getId() : null,
                linkedReturn != null ? linkedReturn.getReturnCode() : null,
                customer != null ? customer.getName() : null,
                linkedPurchase != null ? linkedPurchase.getId() : null,
                linkedPurchase != null ? linkedPurchase.getPurchaseInvoiceCode() : null,
                supplier != null ? supplier.getName() : null,
                shift != null ? shift.getId() : null,
                shift != null ? shift.getShiftReportCode() : null,
                underPharmacistAutoApproveLimit(expense.getAmount())
        );
    }

    // Tên người duyệt hiển thị — luôn là "Chủ nhà thuốc" nếu đã duyệt, vì không có cột lưu người duyệt riêng.
    private String approverName(Expense expense) {
        // Expense không có FK "approvedBy" riêng, chỉ có approvedAt.
        return expense.getApprovedAt() != null ? "Chủ nhà thuốc" : "Chưa có";
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Chốt chặn duy nhất khi một phiếu được duyệt — gọi từ tạo-với-Owner, gửi-với-Owner và duyệt.
     * Chỉ đưa phiếu tới {@link ExpenseStatus#AWAITING_PAYMENT}, chưa phải
     * {@link ExpenseStatus#COMPLETED}: duyệt chỉ xác nhận thẩm quyền, chưa di chuyển tiền — xem
     * {@link #confirmPayment} là bước thực sự làm việc đó.
     */
    private void applyApproval(Expense expense, Account approver) {
        expense.setApprovedAt(Instant.now());
        expense.setStatus(ExpenseStatus.AWAITING_PAYMENT);
    }

    /**
     * Phiếu do Dược sĩ lập với số tiền này có đủ nhỏ để tự phục vụ hay không: bỏ qua duyệt Owner
     * lúc tạo ({@link #pharmacistAutoApproves}), và bỏ qua Owner ở {@link #confirmPayment} — cùng
     * chung ngưỡng {@link ExpenseType#PHARMACIST_AUTO_APPROVE_LIMIT}.
     */
    private boolean underPharmacistAutoApproveLimit(BigDecimal amount) {
        return amount != null && amount.compareTo(ExpenseType.PHARMACIST_AUTO_APPROVE_LIMIT) < 0;
    }

    /**
     * Phiếu Dược sĩ lập có đủ nhỏ để bỏ qua hẳn {@link ExpenseStatus#PENDING} hay không — cùng cách
     * tự duyệt Owner luôn được, chỉ giới hạn bởi {@link ExpenseType#PHARMACIST_AUTO_APPROVE_LIMIT}.
     * Luôn {@code false} với vai trò khác; nơi gọi tự OR thêm điều kiện {@code isOwner} của mình.
     */
    private boolean pharmacistAutoApproves(boolean isPharmacist, BigDecimal amount) {
        return isPharmacist && underPharmacistAutoApproveLimit(amount);
    }

    /**
     * Xác nhận tiền của một phiếu {@link ExpenseStatus#AWAITING_PAYMENT} đã thực sự rời quỹ — bước
     * chi tiền thật. Mặc định chỉ Chủ nhà thuốc, bất kể ai lập/duyệt phiếu (kể cả Kế toán):
     * {@code ExpensePageController} chỉ map route này dưới {@code /owner/**}, giống duyệt/từ chối.
     * Đây là chốt chặn duy nhất đẩy tiền vào phiếu nhập liên kết, trừ quỹ tài chính, và đóng dấu ca
     * làm việc — không việc nào trong số đó xảy ra ở bước duyệt nữa. Với phiếu
     * {@link ExpenseType#RETURN_REFUND_PAYOUT}, đây cũng là lúc đồng bộ lại trạng thái
     * {@code Return} liên kết — xem {@link ReturnService#syncStatusAfterRefundPayment(Integer)}.
     *
     * <p>Overload không giới hạn — không kiểm người lập/số tiền, dành cho route của Owner, được xác
     * nhận BẤT KỲ phiếu nào chứ không chỉ phiếu nhỏ tự lập (xem overload 2 tham số bên dưới).</p>
     */
    @Transactional
    public void confirmPayment(Integer expenseId) {
        applyConfirmedPayment(loadForConfirmPayment(expenseId));
    }

    /**
     * Dược sĩ cũng được tự xác nhận thanh toán cho phiếu CỦA CHÍNH MÌNH, nhưng chỉ khi phiếu đủ nhỏ
     * để đã tự động duyệt ngay từ đầu ({@link #underPharmacistAutoApproveLimit}) — họ lập, phiếu tự
     * duyệt, và vì họ là người trực tiếp chi tiền nên tự xác nhận luôn thay vì chờ Owner. Phiếu từ
     * ngưỡng trở lên — hoặc thuộc về người khác — vẫn cần overload không giới hạn ở trên.
     */
    @Transactional
    public void confirmPayment(Integer expenseId, Integer currentAccountId) {
        Expense expense = loadForConfirmPayment(expenseId);
        ensureApplicantAccess(expense, currentAccountId);
        if (!underPharmacistAutoApproveLimit(expense.getAmount())) {
            throw new IllegalArgumentException(
                    "Phiếu từ " + String.format(Locale.forLanguageTag("vi-VN"), "%,.0fđ",
                            ExpenseType.PHARMACIST_AUTO_APPROVE_LIMIT)
                            + " trở lên phải do Chủ nhà thuốc xác nhận thanh toán");
        }
        applyConfirmedPayment(expense);
    }

    // Tải phiếu chi và kiểm tra đang ở đúng trạng thái Chờ thanh toán trước khi xác nhận chi tiền.
    private Expense loadForConfirmPayment(Integer expenseId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chi"));
        if (!ExpenseStatus.AWAITING_PAYMENT.equals(expense.getStatus())) {
            throw new IllegalArgumentException(
                    "Chỉ có thể xác nhận thanh toán cho phiếu đang ở trạng thái chờ thanh toán");
        }
        return expense;
    }

    /**
     * Thực thi toàn bộ side-effect khi tiền THỰC SỰ rời quỹ: chuyển COMPLETED, tất toán phiếu nhập
     * liên kết, trừ quỹ tài chính, đóng dấu ca làm việc, và đồng bộ trạng thái Return nếu có.
     * @see #confirmPayment(Integer)
     */
    private void applyConfirmedPayment(Expense expense) {
        BigDecimal paid = nullToZero(expense.getPaid());
        expense.setStatus(ExpenseStatus.COMPLETED);
        settlePurchaseInvoice(expense, paid);
        // Tiền rời quỹ đúng lúc này — cùng thời điểm status chuyển COMPLETED, không phải lúc duyệt
        // (Chờ thanh toán chưa phải tiền thật). Xem FinancialsettingService.adjustFundBalances: quỹ
        // chưa từng được thiết lập (còn null) thì delta của quỹ đó bị bỏ qua lặng lẽ.
        financialsettingService.adjustFundBalances(
                nullToZero(expense.getPaidByCash()).negate(),
                nullToZero(expense.getPaidByBanking()).negate());
        attachOpenShift(expense, applicantIdOf(expense));
        expenseRepository.save(expense);

        // Đường thứ hai vào ReturnStatus.COMPLETED (xem javadoc của ReturnStatus): phiếu chi hoàn
        // tiền vừa thực chi xong, báo ReturnService tự kiểm và tất toán phiếu trả nếu không còn nợ.
        // No-op cho mọi loại phiếu chi khác (getReturnID() luôn null ngoài RETURN_REFUND_PAYOUT).
        if (expense.getReturnID() != null) {
            returnService.syncStatusAfterRefundPayment(expense.getReturnID().getId());
        }
    }

    /** Người LẬP phiếu — xem {@link #attachOpenShift}. */
    private Integer applicantIdOf(Expense expense) {
        return expense.getApplicantID() != null ? expense.getApplicantID().getId() : null;
    }

    /**
     * Đóng dấu ca đang mở của người <strong>LẬP</strong> phiếu (không phải người duyệt hay người chi
     * hộ), để tiền đã ra có thể đối soát vào đúng ca của họ. Giữ nguyên nếu đã đóng dấu từ trước —
     * phiếu thuộc về ca lúc nó được lập, không phải ca đang mở khi chi tiếp sau đó.
     *
     * <p>Phiếu có tiền mặt gọi {@code ensureOpenShiftFor} (tự mở ca nếu chưa có); phiếu chỉ chuyển
     * khoản chỉ tra ca đã mở sẵn. {@code ensureOpenShiftFor} trả {@code null} với vai trò không có
     * ca, còn {@link #resolveSplit} đã chặn tiền mặt từ mọi vai trò trừ Owner/Dược sĩ nên nhánh tiền
     * mặt ở đây luôn an toàn.</p>
     */
    private void attachOpenShift(Expense expense, Integer accountId) {
        if (expense.getShiftReportID() != null || accountId == null) {
            return;
        }
        Shiftreport shift = touchesCashDrawer(expense)
                ? shiftreportService.ensureOpenShiftFor(accountId)
                : shiftreportService.findDraftShift(accountId).orElse(null);
        if (shift != null) {
            expense.setShiftReportID(shift);
        }
    }

    /** Phiếu này có phần nào chi bằng tiền mặt thật hay không. */
    private boolean touchesCashDrawer(Expense expense) {
        return nullToZero(expense.getPaidByCash()).compareTo(BigDecimal.ZERO) > 0;
    }

    /** Phiếu này có phải do Owner lập hay không. */
    private boolean raisedByOwner(Expense expense) {
        Integer applicantId = applicantIdOf(expense);
        return applicantId != null
                && accountpermissionRepository.existsByAccountIdAndRole(applicantId, RoleConstants.OWNER);
    }

    // Chuẩn hoá + kiểm tra loại phiếu chi client gửi lên có hợp lệ hay không.
    private String resolveExpenseType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw new IllegalArgumentException("Vui lòng chọn loại phiếu chi");
        }
        String type = rawType.trim().toUpperCase(Locale.ROOT);
        if (!ExpenseType.isValid(type)) {
            throw new IllegalArgumentException("Loại phiếu chi không hợp lệ");
        }
        return type;
    }

    // Parse ngày từ chuỗi yyyy-MM-dd của form, mặc định hôm nay nếu để trống.
    private Instant resolveDate(String rawDate) {
        LocalDate date = parseDate(rawDate);
        LocalDate resolved = date != null ? date : LocalDate.now();
        return resolved.atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    /**
     * Tra và kiểm tra đầy đủ phiếu trả hàng mà một phiếu {@link ExpenseType#RETURN_REFUND_PAYOUT}
     * hoàn tiền, hoặc {@code null} với mọi loại khác (một {@code returnId} còn sót từ lúc người dùng
     * đổi loại khác thì bị bỏ qua, không tự động gắn nhầm).
     */
    private Return resolveCustomerReturn(ExpenseCreateRequest request, String expenseType) {
        if (!ExpenseType.RETURN_REFUND_PAYOUT.equals(expenseType)) {
            return null;
        }
        if (request.getReturnId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn phiếu trả hàng của khách cần hoàn tiền");
        }

        Return ret = returnRepository.findById(request.getReturnId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng"));

        if (ret.getInvoiceID() == null) {
            throw new IllegalArgumentException(
                    "Phiếu chi hoàn tiền chỉ áp dụng cho phiếu trả hàng của khách, không áp dụng cho trả hàng nhà cung cấp");
        }
        if (!ReturnStatus.DEBT.equals(ret.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể hoàn tiền cho phiếu trả hàng đã duyệt");
        }
        if (cashRefundAmount(ret).compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Phiếu trả hàng này không phát sinh tiền hoàn (toàn bộ đã cấn trừ vào công nợ)");
        }
        if (availableToRefund(ret, committedByReturnId()).compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Phiếu trả hàng này đã được hoàn đủ tiền");
        }
        return ret;
    }

    /**
     * Phần thực sự là tiền mặt rời quỹ của một phiếu trả: số nợ khách ({@code totalRefund}) trừ đi
     * phần đã tất toán bằng cách trừ nợ hóa đơn gốc ({@code offsetDebtAmount}) — chi lại phần đó là
     * hoàn tiền khách hai lần.
     *
     * <p>Thực tế {@code offsetDebtAmount} luôn bằng 0 với trả hàng khách (khách phải trả hết nợ hóa
     * đơn trước khi được trả hàng), nhưng vẫn giữ phép trừ vì cột này chưa có gì ràng buộc chắc
     * chắn bằng 0.</p>
     */
    private BigDecimal cashRefundAmount(Return ret) {
        return nullToZero(ret.getTotalRefund())
                .subtract(nullToZero(ret.getOffsetDebtAmount()))
                .max(BigDecimal.ZERO);
    }

    /**
     * {@code returnID -> tổng tiền các phiếu chi còn sống đã nhận hoàn cho phiếu trả đó}. Một phiếu
     * trả có thể được hoàn nhiều lần (xem {@link #createExpense}), nên đây là một tổng chứ không
     * phải cờ một-phiếu-một-lần.
     *
     * <p>Cộng cả phiếu chưa duyệt lẫn phiếu đã chi: phiếu chưa duyệt là tiền đã hứa, không được để
     * hai phiếu cùng nhận trọn phần hoàn rồi cả hai cùng được duyệt. Phiếu bị từ chối / bị hủy thì
     * không tính, nên hủy một phiếu là trả phần hoàn đó về cho phiếu sau — cố ý khác
     * {@code IncomeService.linkedReturnIds()}, nơi rào lỏng hơn sẽ kẹt phiếu trả lại vĩnh viễn.</p>
     */
    private Map<Integer, BigDecimal> committedByReturnId() {
        Map<Integer, BigDecimal> committed = new LinkedHashMap<>();
        for (Expense expense : liveExpenses()) {
            Return ret = expense.getReturnID();
            if (ret == null || ret.getId() == null) {
                continue;
            }
            committed.merge(ret.getId(), nullToZero(expense.getAmount()), BigDecimal::add);
        }
        return committed;
    }

    /** Phần tiền hoàn của một phiếu trả chưa được phiếu chi nào nhận. */
    private BigDecimal availableToRefund(Return ret, Map<Integer, BigDecimal> committed) {
        return cashRefundAmount(ret)
                .subtract(committed.getOrDefault(ret.getId(), BigDecimal.ZERO))
                .max(BigDecimal.ZERO);
    }

    // Khách hàng của hóa đơn gốc thuộc phiếu trả hàng này; null nếu là khách lẻ.
    private Customer customerOf(Return ret) {
        Invoice invoice = ret.getInvoiceID();
        return invoice != null ? invoice.getCustomerID() : null;
    }

    /**
     * Tra và kiểm tra phiếu nhập mà phiếu chi này tất toán. Cho phép với mọi loại trong
     * {@link ExpenseType#PURCHASE_LINKABLE} và luôn không bắt buộc — trả NCC mà không gắn hóa đơn
     * cụ thể vẫn hợp lệ — nên thiếu id không phải lỗi, khác với hoàn tiền trả hàng.
     */
    private Purchaseinvoice resolvePurchaseInvoice(ExpenseCreateRequest request, String expenseType) {
        if (!ExpenseType.GOODS_PAYMENT.equals(expenseType)) {
            return null;
        }

        if (request.getPurchaseId() == null) {
            return null;
        }

        Purchaseinvoice invoice = purchaseinvoiceService.findPayableInvoices().stream()
                .filter(candidate -> request.getPurchaseId().equals(candidate.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Phiếu nhập không tồn tại, đã hủy hoặc đã trả đủ tiền"));

        if (availableToPay(invoice, committedByPurchaseId()).compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Phiếu nhập này đã có phiếu chi khác nhận trả toàn bộ phần còn nợ");
        }
        return invoice;
    }

    /**
     * Số tiền của phiếu = số tiền người lập nhập cho lần chi này — không có khái niệm "chi thiếu so
     * với chính nó" (một phiếu là một lần chi). Chặn trần ở phần chứng từ còn thiếu (xem
     * {@link #cappedByDocument}), để hai phiếu chi cùng lúc không cùng trả vượt phần còn nợ.
     *
     * <p><strong>Ngoại lệ: hoàn tiền trả hàng không nhận số người dùng gõ, luôn lấy nguyên phần
     * còn phải hoàn của phiếu trả</strong> (đúng như {@link ExpenseCreateRequest#getReturnId()} đã ghi
     * — "the posted value is never trusted"), khác phiếu nhập vẫn cho trả một phần. Ô tiền trên form
     * chỉ hiển thị, không sửa được khi chọn loại hoàn tiền — xem {@code expense/create.html}.</p>
     */
    private BigDecimal resolveAmount(ExpenseCreateRequest request,
                                     Return linkedReturn,
                                     Purchaseinvoice linkedPurchase) {
        BigDecimal posted = request.getAmount();
        if (linkedReturn != null) {
            return availableToRefund(linkedReturn, committedByReturnId());
        }
        if (linkedPurchase != null) {
            return cappedByDocument(posted, availableToPay(linkedPurchase, committedByPurchaseId()),
                    "Số tiền chi vượt quá phần còn phải trả của phiếu nhập");
        }
        return posted;
    }

    /**
     * Bỏ trống ô tiền trên một phiếu có gắn chứng từ = "trả nốt", nên mặc định là toàn bộ phần còn
     * lại. Nhập vượt phần còn lại thì báo lỗi chứ không tự cắt bớt: người lập cần biết con số họ gõ
     * không được ghi nhận, thay vì thấy phiếu lưu xong với một số khác.
     */
    private BigDecimal cappedByDocument(BigDecimal posted, BigDecimal available, String overLimitMessage) {
        if (posted == null || posted.compareTo(BigDecimal.ZERO) <= 0) {
            return available;
        }
        if (posted.compareTo(available) > 0) {
            throw new IllegalArgumentException(overLimitMessage
                    + " (" + String.format(Locale.forLanguageTag("vi-VN"), "%,.0fđ", available) + ")");
        }
        return posted;
    }

    /**
     * Phần nợ của phiếu nhập chưa bị phiếu chi nào "nhắm tới": nợ gốc trừ đi tổng đã cam kết bởi
     * các phiếu chi còn sống. Không có bước này thì hai phiếu nháp cùng nhận trả hết nợ đều được
     * chấp nhận, và phiếu nhập bị trả vượt ngay khi cả hai được duyệt.
     */
    private BigDecimal availableToPay(Purchaseinvoice invoice, Map<Integer, BigDecimal> committed) {
        BigDecimal debt = purchaseinvoiceService.remainingDebt(invoice);
        return debt.subtract(committed.getOrDefault(invoice.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
    }

    /**
     * {@code purchaseId -> số tiền đã hứa nhưng chưa thực chi}, cộng dồn qua mọi phiếu còn sống.
     * Phần "đã hứa" của một phiếu là {@code amount - đã thực chi}: phần đã thực chi đã nằm trong
     * {@code Purchaseinvoice.paid} rồi, tính lại ở đây sẽ bị trừ hai lần.
     */
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
     * Số tiền phiếu này đã thực sự cộng vào phiếu nhập. Chỉ phiếu {@code COMPLETED} mới tính là đã
     * chi — duyệt xong ({@code AWAITING_PAYMENT}) mới chỉ xác nhận thẩm quyền, chưa di chuyển tiền.
     */
    private BigDecimal disbursedAmount(Expense expense) {
        return isDisbursed(expense) ? nullToZero(expense.getPaid()) : BigDecimal.ZERO;
    }

    /** Tiền của phiếu này đã thực sự rời quỹ hay chưa — chỉ dựa vào status. */
    private boolean isDisbursed(Expense expense) {
        return ExpenseStatus.COMPLETED.equals(expense.getStatus());
    }

    // Toàn bộ phiếu chi còn hiệu lực (loại bỏ phiếu bị Từ chối/Đã hủy).
    private List<Expense> liveExpenses() {
        return expenseRepository.findAll().stream()
                .filter(expense -> !ExpenseStatus.REJECTED.equals(expense.getStatus()))
                .filter(expense -> !ExpenseStatus.CANCELLED.equals(expense.getStatus()))
                .toList();
    }

    /** Cộng {@code delta} vào phiếu nhập liên kết của phiếu chi này, nếu có. */
    private void settlePurchaseInvoice(Expense expense, BigDecimal delta) {
        Purchaseinvoice invoice = expense.getPurchaseID();
        if (invoice == null || delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        purchaseinvoiceService.applyPayment(invoice.getId(), delta);
    }

    /** Dòng phụ trong ô chọn trả nợ: nhà cung cấp nào, tổng tiền phiếu nhập bao nhiêu. */
    private String purchaseReferenceDetail(Purchaseinvoice invoice) {
        Supplier supplier = invoice.getSupplierID();
        String supplierName = supplier != null && supplier.getName() != null && !supplier.getName().isBlank()
                ? supplier.getName()
                : "Không rõ NCC";
        return supplierName + " · Tổng "
                + String.format(Locale.forLanguageTag("vi-VN"), "%,.0fđ", nullToZero(invoice.getTotalAmount()));
    }

    /** Dòng phụ trong ô chọn: hoàn cho ai, thuộc hóa đơn bán nào. */
    private String referenceDetail(Return ret) {
        Invoice invoice = ret.getInvoiceID();
        Customer customer = customerOf(ret);
        String customerName = customer != null && customer.getName() != null && !customer.getName().isBlank()
                ? customer.getName()
                : "Khách lẻ";
        String invoiceNumber = invoice != null && invoice.getInvoiceNumber() != null
                ? invoice.getInvoiceNumber()
                : "—";
        return customerName + " · HĐ " + invoiceNumber;
    }

    /**
     * Trả về {@code [paidByCash, paidByBanking]}.
     *
     * <p><strong>Tiền mặt: Owner/Dược sĩ. Chuyển khoản: Owner/Kế toán.</strong> Owner và Dược sĩ giữ
     * quỹ/ca nên được chi tiền mặt; Kế toán không giữ quỹ nên chỉ chi chuyển khoản. Ngược lại, ca
     * của Dược sĩ chỉ mở bằng quỹ tiền mặt cố định nên Dược sĩ không được chuyển khoản. Ràng buộc
     * này là điều kiện để {@link #attachOpenShift} đóng dấu ca an toàn. Khi không có gì được gửi
     * lên: mặc định toàn bộ tiền mặt nếu được phép, ngược lại toàn bộ chuyển khoản.</p>
     */
    private BigDecimal[] resolveSplit(ExpenseCreateRequest request, BigDecimal amount,
                                       boolean canPayCash, boolean canPayBanking) {
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        }
        BigDecimal cash = request.getPaidByCash();
        BigDecimal banking = request.getPaidByBanking();
        if (cash == null && banking == null) {
            return canPayCash
                    ? new BigDecimal[]{amount, BigDecimal.ZERO}
                    : new BigDecimal[]{BigDecimal.ZERO, amount};
        }
        cash = nullToZero(cash);
        banking = nullToZero(banking);
        assertCashAllowed(cash, canPayCash);
        assertBankingAllowed(banking, canPayBanking);
        if (cash.add(banking).setScale(2, RoundingMode.HALF_UP)
                .compareTo(amount.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalArgumentException("Tiền mặt + chuyển khoản phải bằng số tiền chi");
        }
        return new BigDecimal[]{cash, banking};
    }

    /** @see #resolveSplit */
    private void assertCashAllowed(BigDecimal cash, boolean canPayCash) {
        if (!canPayCash && nullToZero(cash).compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalArgumentException(
                    "Kế toán chỉ được chi qua chuyển khoản; phần tiền mặt phải do Chủ nhà thuốc chi");
        }
    }

    /** @see #resolveSplit */
    private void assertBankingAllowed(BigDecimal banking, boolean canPayBanking) {
        if (!canPayBanking && nullToZero(banking).compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalArgumentException(
                    "Dược sĩ chỉ được chi bằng tiền mặt; phần chuyển khoản phải do Chủ nhà thuốc hoặc Kế toán chi");
        }
    }

    /** {@code amount} truyền riêng vì phiếu hoàn tiền lấy số này từ phiếu trả, không từ form. */
    private void validateRequest(ExpenseCreateRequest request, BigDecimal amount) {
        if (request.getExpenseType() == null || request.getExpenseType().isBlank()) {
            throw new IllegalArgumentException("Vui lòng chọn loại phiếu chi");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập lý do chi");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền cần chi phải lớn hơn 0");
        }
    }

    // Từ khóa có khớp mã phiếu/lý do chi/tên người lập hay không (không phân biệt hoa thường, dấu).
    private boolean matchesKeyword(Expense expense, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        return containsNormalized(formatCode(expense.getId()), normalizedKeyword)
                || containsNormalized(expense.getReason(), normalizedKeyword)
                || containsNormalized(expense.getApplicantID() != null ? expense.getApplicantID().getName() : null,
                        normalizedKeyword);
    }

    // Ngày của phiếu chi có nằm trong khoảng [from, to] được lọc hay không.
    private boolean matchesDate(Expense expense, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        if (expense.getDate() == null) {
            return false;
        }
        LocalDate date = toLocalDate(expense.getDate());
        if (from != null && date.isBefore(from)) {
            return false;
        }
        return to == null || !date.isAfter(to);
    }

    private long countByStatus(List<Expense> expenses, String status) {
        return expenses.stream().filter(expense -> status.equals(expense.getStatus())).count();
    }

    // Map trạng thái phiếu chi -> class CSS tương ứng để tô màu badge trên giao diện.
    private String statusCssClass(String status) {
        if (status == null) {
            return "status-default";
        }
        if (ExpenseStatus.DRAFT.equals(status)) {
            return "status-draft";
        }
        if (ExpenseStatus.PENDING.equals(status)) {
            return "status-pending";
        }
        if (ExpenseStatus.REJECTED.equals(status)) {
            return "status-rejected";
        }
        if (ExpenseStatus.AWAITING_PAYMENT.equals(status)) {
            return "status-awaiting";
        }
        if (ExpenseStatus.COMPLETED.equals(status)) {
            return "status-approved";
        }
        if (ExpenseStatus.CANCELLED.equals(status)) {
            return "status-rejected";
        }
        return "status-default";
    }

    private String formatCode(Integer id) {
        if (id == null) {
            return "PC-000000";
        }
        return "PC-" + String.format("%06d", id);
    }

    // Sinh mã tạm cho phiếu mới dựa trên id lớn nhất hiện có + 1 (sẽ được ghi lại đúng theo id thật sau khi lưu).
    private String generateCode() {
        int nextId = expenseRepository.findAll().stream()
                .map(Expense::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        return "PC-" + String.format("%06d", nextId);
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
        return LocalDate.parse(value);
    }

    private boolean containsNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).contains(normalizedKeyword);
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

    // Chuẩn hoá chuỗi để tìm kiếm không phân biệt hoa/thường và dấu tiếng Việt.
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replace("Đ", "D").replace("đ", "d");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }
}
