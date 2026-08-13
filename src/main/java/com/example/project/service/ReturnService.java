package com.example.project.service;

import com.example.project.constant.ReturnStatus;
import com.example.project.constant.TaxRevenueGroup;
import com.example.project.dto.request.ReturnCreateRequest;
import com.example.project.dto.request.ReturnLineRequest;
import com.example.project.dto.response.*;
import com.example.project.entity.*;
import com.example.project.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Customer returns: listing/searching, detail, creation and the approve/reject workflow.
 *
 * <p>Statuses (see {@link ReturnStatus}): Nháp → Chờ duyệt → Nợ / Từ chối. There is no "Duyệt"
 * state — an approved return becomes a payable ("Nợ") because the pharmacy now owes the customer the
 * not-yet-paid refund. Owner-created slips auto-approve straight to {@code Nợ}.</p>
 *
 * <p><strong>Approval changes stock:</strong> each restockable line goes into a brand-new batch cloned
 * from the one it was sold from — returned goods are kept in their own batch for traceability (there is
 * no "returned" flag on {@code batch}). The cash payout itself lives on a separate Expense; this service
 * only records the refund amounts on the slip.</p>
 */
@Service
public class ReturnService {

    /**
     * "No return window at all" — the meaning of a blank {@code Financialsetting.returnPolicyMaxDays}.
     * The policy lives entirely in the financial setting; there is no hard-coded fallback on purpose.
     */
    private static final int RETURN_WINDOW_UNLIMITED = -1;

    /**
     * Tỷ lệ hoàn đầy đủ. Dùng khi {@code Financialsetting.returnProductOnInvoiceValueRate} chưa được
     * đặt (NULL / 0) — không có cấu hình thì hoàn nguyên giá trị, không tự ý giữ lại của khách.
     */
    private static final BigDecimal FULL_REFUND_RATE = new BigDecimal("100.00");

    /**
     * Khoảng thời gian coi hai phiếu trùng khít nội dung là MỘT lần bấm Tạo bị lặp — xem
     * {@link #findRecentDuplicate}. Đặt ngắn có chủ đích: lần gửi lại vì mất mạng luôn diễn ra
     * trong vòng vài giây tới vài chục giây, còn để rộng quá thì chặn nhầm một phiếu thật.
     */
    private static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(2);

    // Invoice.date is stored as VN wall-clock LocalDateTime (see InvoiceService) — the adjustment
    // invoice's own date must use the same convention, not a real UTC Instant.
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Only completed sale invoices are returnable. Matched accent/case-insensitively. */
    private static final String INVOICE_STATUS_COMPLETED = "Hoàn thành";
    /** An invoice still carrying an unpaid balance (mirrors InvoiceService.STATUS_DEBT). */
    private static final String INVOICE_STATUS_DEBT = "Còn nợ";
    /**
     * Giá trị CŨ của {@code invoice.status}: việc ký nay nằm ở 2 cột riêng {@code signAt}/{@code signBy}
     * và không rẽ nhánh nghiệp vụ nào ở màn trả hàng. Hằng số giữ lại CHỈ để đọc dữ liệu cũ — hóa đơn cũ
     * mang chuỗi này vẫn phải trả hàng được ({@link #isReturnEligibleStatus}) và không bị ghi đè trạng
     * thái ({@link #hasLegacySignedStatus}).
     */
    private static final String INVOICE_STATUS_SIGNED = "Đã ký";
    private static final String INVOICE_STATUS_RETURNED_FULL = "Đã trả hàng toàn bộ";
    private static final String INVOICE_STATUS_RETURNED_PARTIAL = "Đã trả hàng 1 phần";

    /**
     * Loại hóa đơn CHỈ CÒN 2: "Bán hàng" (normal) và "Thay thế" (replacement). 3 hằng {@code *_LEGACY}
     * và {@link #INVOICE_TYPE_VAT} giữ lại CHỈ để đọc dữ liệu cũ, không bao giờ ghi mới.
     */
    private static final String INVOICE_TYPE_NORMAL = "Bán hàng";
    /** Giá trị cũ: hóa đơn bán hàng của nhà thuốc Nhóm 3+ khi còn phân biệt theo nhóm doanh thu. */
    private static final String INVOICE_TYPE_VAT = "Hóa đơn GTGT";
    /** Legacy DB value before invoiceType was stored in Vietnamese. */
    private static final String INVOICE_TYPE_NORMAL_LEGACY = "normal";
    /** Loại đã bị bỏ — chỉ còn dùng để NHẬN DIỆN hóa đơn điều chỉnh cũ và chặn không cho trả tiếp. */
    private static final String INVOICE_TYPE_ADJUSTMENT = "Điều chỉnh";
    /** Legacy DB value before invoiceType was stored in Vietnamese. */
    private static final String INVOICE_TYPE_ADJUSTMENT_LEGACY = "adjustment";
    /**
     * Hóa đơn thay thế — phát hành cho MỌI phiếu trả hàng, bất kể hóa đơn gốc đã ký hay chưa. Nó vô hiệu
     * hoàn toàn bản gốc (xem {@link #isSupersededByReturn}) và kế thừa TOÀN BỘ phần dữ liệu còn lại,
     * gồm cả công nợ chưa trả.
     */
    private static final String INVOICE_TYPE_REPLACEMENT = "Thay thế";

    /** Chặn vòng lặp vô hạn khi đi ngược chuỗi {@code originalInvoiceID} — xem {@link #rootOf}. */
    private static final int MAX_INVOICE_CHAIN_DEPTH = 100;

    private static final String INVOICE_RETURN_NONE = "NONE";
    private static final String INVOICE_RETURN_PARTIAL = "PARTIAL";
    private static final String INVOICE_RETURN_FULL = "FULL";

    // returnType no longer describes HOW the money moves the return screen does not
    // touch cash at all) — it only says WHO the goods went back to. Supplier slips use SUPPLIER.
    private static final String TYPE_CUSTOMER = "CUSTOMER";

    // Product types (Type.sortType / Type.name) that cannot be returned. Compared
    // accent/case-insensitively against normalize(...). Medical-device "máy" carries a warranty so it
    // is handled via warranty, not return.
    private static final String SORT_MEDICAL_DEVICE = "thiet bi y te";
    private static final String DEVICE_MACHINE_MARK = "may";

    private final ReturnRepository returnRepository;
    private final ReturndetailRepository returndetailRepository;
    private final AccountRepository accountRepository;
    private final BatchRepository batchRepository;
    // Sales invoice / detail rows are read for the TH1 flow; for TH2 (signed invoice) we also *create*
    // a replacement invoice row here (see createReplacementInvoice) — mirroring how cloneReturnBatch
    // creates a Batch — without calling into InvoiceService.
    private final InvoiceRepository invoiceRepository;
    private final InvoicedetailRepository invoicedetailRepository;
    // Read-only: the pharmacy's return policy (returnPolicyMaxDays) — see getReturnWindowDays().
    private final FinancialsettingRepository financialsettingRepository;
    // Read-only: mốc kỳ thuế đã chốt gần nhất — xem lastClosedPeriodEnd().
    private final TaxperiodsnapshotRepository taxperiodsnapshotRepository;
    // Lazily opens/reuses the acting account's shift the moment a return is actually approved
    // (becomes Nợ) — mirrors the same hook on the Invoice side (see ShiftreportService).
    private final ShiftreportService shiftreportService;
    private final InvoiceService invoiceService;
    private final WorkflowNotificationService workflowNotificationService;
    private final ExpenseService expenseService;
    private InventoryAlertEventService inventoryAlertEventService;

    public ReturnService(ReturnRepository returnRepository,
                         ReturndetailRepository returndetailRepository,
                         AccountRepository accountRepository,
                         BatchRepository batchRepository,
                         InvoiceRepository invoiceRepository,
                         InvoicedetailRepository invoicedetailRepository,
                         FinancialsettingRepository financialsettingRepository,
                         TaxperiodsnapshotRepository taxperiodsnapshotRepository,
                         ShiftreportService shiftreportService,
                         InvoiceService invoiceService,
                         WorkflowNotificationService workflowNotificationService,
                         ExpenseService expenseService) {
        this.returnRepository = returnRepository;
        this.returndetailRepository = returndetailRepository;
        this.accountRepository = accountRepository;
        this.batchRepository = batchRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoicedetailRepository = invoicedetailRepository;
        this.financialsettingRepository = financialsettingRepository;
        this.taxperiodsnapshotRepository = taxperiodsnapshotRepository;
        this.shiftreportService = shiftreportService;
        this.invoiceService = invoiceService;
        this.workflowNotificationService =  workflowNotificationService;
        this.expenseService = expenseService;
    }

    // ------------------------------------------------------------------ list / search
    @Autowired
    public void setInventoryAlertEventService(
            InventoryAlertEventService inventoryAlertEventService
    ) {
        this.inventoryAlertEventService =
                inventoryAlertEventService;
    }

    @Transactional(readOnly = true)
    public Page<ReturnListItemResponse> search(String keyword,
                                               String fromDate,
                                               String toDate,
                                               String status,
                                               Pageable pageable) {
        final String normalizedKeyword = normalize(keyword);
        final LocalDate from = parseDate(fromDate);
        final LocalDate to = parseDate(toDate);

        List<Return> returns = returnRepository.findAllWithRelations();
        Map<Integer, List<Returndetail>> detailMap = returndetailRepository.findAllWithRelations().stream()
                .filter(detail -> detail.getReturnID() != null)
                .collect(Collectors.groupingBy(detail -> detail.getReturnID().getId()));
        // Còn phải hoàn thực sau khi trừ phiếu chi RETURN_REFUND_PAYOUT đã chi — nguồn sự thật nằm ở
        // Expense, không phải ở return (xem ExpenseService.outstandingRefundByReturnId).
        Map<Integer, BigDecimal> outstandingRefund = expenseService.outstandingRefundByReturnId(returns);

        List<ReturnListItemResponse> filtered = returns.stream()
                // Exclude supplier returns (purchaseID set) — they share the table but have their own screens.
                .filter(ret -> ret.getInvoiceID() != null)
                .filter(ret -> matchesKeyword(ret, detailMap.getOrDefault(ret.getId(), List.of()), normalizedKeyword))
                .filter(ret -> matchesDate(ret, from, to))
                .filter(ret -> status == null || status.isBlank() || isStatus(getStatusName(ret), status))
                .sorted(Comparator.comparing(Return::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ret -> toListItem(ret, detailMap.getOrDefault(ret.getId(), List.of()), outstandingRefund))
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<ReturnListItemResponse> content = start >= filtered.size() ? List.of() : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public ReturnStatsResponse getStats() {
        List<Return> returns = returnRepository.findAllWithRelations().stream()
                .filter(ret -> ret.getInvoiceID() != null)
                .toList();
        YearMonth currentMonth = YearMonth.now();

        long monthlyCount = returns.stream()
                .filter(ret -> ret.getReturnDate() != null)
                .filter(ret -> YearMonth.from(toLocalDate(ret.getReturnDate())).equals(currentMonth))
                .count();

        return new ReturnStatsResponse(
                monthlyCount,
                countByStatus(returns, ReturnStatus.DRAFT),
                countByStatus(returns, ReturnStatus.PENDING),
                countByStatus(returns, ReturnStatus.DEBT),
                countByStatus(returns, ReturnStatus.COMPLETED),
                countByStatus(returns, ReturnStatus.REJECTED));
    }

    public List<String> listStatuses() {
        return ReturnStatus.ALL;
    }


    // ------------------------------------------------------------------ create screen sources

    /**
     * Sale invoices a customer may still return against: completed, within the return window and not
     * already fully returned. Read-only over the Invoice module.
     */
    @Transactional(readOnly = true)
    public List<ReturnableInvoiceResponse> listReturnableInvoices(String keyword) {
        String normalizedKeyword = normalize(keyword);
        // Resolved once, not per invoice — the window is a single setting row, not per-invoice data.
        int windowDays = getReturnWindowDays();

        // Lọc những điều kiện RẺ (chỉ đọc cột của chính hóa đơn) TRƯỚC, rồi mới đọc dòng chi tiết cho
        // phần còn lại — đọc dòng của mọi hóa đơn trong bảng là lãng phí. Đọc đúng MỘT lần cho mỗi ứng
        // viên rồi dùng lại cho cả trạng thái trả, bộ lọc từ khóa lẫn phần hiển thị.
        return invoiceRepository.findAll().stream()
                .filter(this::isNormalInvoice)
                .filter(this::isReturnEligibleStatus)
                .filter(invoice -> !Boolean.TRUE.equals(invoice.getPrescriptionRequired()))
                .map(this::returnableCandidateOf)
                .filter(candidate -> isReturnable(candidate.invoice(), windowDays, candidate.returnCode()))
                .filter(candidate -> matchesInvoiceKeyword(candidate, normalizedKeyword))
                .sorted(Comparator.comparing(candidate -> candidate.invoice().getDate(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(candidate -> {
                    Invoice invoice = candidate.invoice();
                    String productSummary = productSummaryOf(candidate.lines());
                    return new ReturnableInvoiceResponse(
                            invoice.getId(),
                            // Số hóa đơn (HD00000x) — duy nhất, để phân biệt; KHÔNG dùng invoicePattern.
                            invoice.getInvoiceNumber(),
                            formatLocalDateTime(invoice.getDate()),
                            invoice.getEmployeeID() != null ? invoice.getEmployeeID().getName() : "Không rõ",
                            invoice.getCustomerID() != null ? invoice.getCustomerID().getName() : "Khách lẻ",
                            invoice.getTotal(),
                            // Công nợ còn lại của chính hóa đơn — số sẽ bị cấn trừ vào tiền hoàn khi duyệt
                            // phiếu trả (netting). Hiển thị ngay ở bảng chọn để người lập biết trước.
                            nz(invoice.getDebtAmount()),
                            returnStatusDisplay(candidate.returnCode()),
                            productSummary,
                            searchTextOf(invoice, candidate.lines()));
                })
                .toList();
    }

    /** Một hóa đơn ứng viên kèm dòng chi tiết đã đọc sẵn — xem {@link #listReturnableInvoices}. */
    private record ReturnableCandidate(Invoice invoice, List<Invoicedetail> lines, String returnCode) {
    }

    private ReturnableCandidate returnableCandidateOf(Invoice invoice) {
        List<Invoicedetail> lines = invoiceLinesOf(invoice.getId());
        return new ReturnableCandidate(invoice, lines, returnCodeOf(lines));
    }

    /** Các dòng còn trả được của hóa đơn — nguồn của cả phần hiển thị lẫn phần tìm theo sản phẩm. */
    private List<Invoicedetail> stillReturnableLines(List<Invoicedetail> lines) {
        return lines.stream()
                .filter(line -> {
                    int returned = line.getReturnedQty() != null ? line.getReturnedQty() : 0;
                    int quantity = line.getQuantity() != null ? line.getQuantity() : 0;
                    return returned < quantity;
                })
                .filter(line -> isReturnableProductType(line.getProductID()))
                .toList();
    }

    /**
     * Tên các mặt hàng còn trả được, hiện thành một dòng phụ dưới số hóa đơn ở bảng chọn: khi người
     * lập tìm theo tên thuốc, dòng này cho biết ngay vì sao hóa đơn đó khớp.
     */
    private String productSummaryOf(List<Invoicedetail> lines) {
        return stillReturnableLines(lines).stream()
                .map(line -> line.getProductID() != null ? line.getProductID().getName() : null)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .collect(Collectors.joining(" · "));
    }

    /**
     * Chuỗi để lọc phía màn hình: số hóa đơn + tên khách + tên và MÃ của các mặt hàng còn trả được.
     *
     * <p><strong>KHÔNG có số điện thoại.</strong> Bảng chọn không hiển thị cột nào chứa số điện thoại
     * — hóa đơn khách lẻ còn không có bản ghi khách hàng để mà lưu — nên tìm theo số điện thoại chỉ
     * làm người dùng gõ vào rồi không hiểu vì sao không ra kết quả.</p>
     */
    private String searchTextOf(Invoice invoice, List<Invoicedetail> lines) {
        StringBuilder text = new StringBuilder();
        appendSearchPart(text, invoice.getInvoiceNumber());
        appendSearchPart(text, invoice.getCustomerID() != null ? invoice.getCustomerID().getName() : "Khách lẻ");
        for (Invoicedetail line : stillReturnableLines(lines)) {
            Product product = line.getProductID();
            if (product != null) {
                appendSearchPart(text, product.getName());
                appendSearchPart(text, product.getCode());
            }
        }
        return text.toString();
    }

    private void appendSearchPart(StringBuilder target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(' ');
        }
        target.append(value.trim());
    }

    /** The still-returnable lines of one invoice, for the create screen (JSON). */
    @Transactional(readOnly = true)
    public List<ReturnInvoiceLineResponse> loadInvoiceLines(Integer invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn"));

        return invoiceLinesOf(invoice.getId()).stream()
                .filter(line -> isReturnableProductType(line.getProductID()))
                .map(this::toInvoiceLine)
                .filter(line -> line.getReturnableQty() != null && line.getReturnableQty() > 0)
                .toList();
    }

    /**
     * Whether a product may be returned at all, by its {@link Type}. Blocks the medical-device "máy"
     * sub-type (warranty items); other medical devices (có hạn / không hạn) and all drugs / goods are
     * allowed. Unknown/missing type → allowed (don't over-block).
     */
    private boolean isReturnableProductType(Product product) {
        if (product == null || product.getTypeID() == null) {
            return true;
        }
        Type type = product.getTypeID();
        String sort = normalize(type.getSortType());
        String name = normalize(type.getName());
        return !(SORT_MEDICAL_DEVICE.equals(sort) && name.contains(DEVICE_MACHINE_MARK));
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates one customer-return slip from a chosen completed invoice.
     *
     * <p>Resulting status: {@code asDraft} → Nháp; otherwise the Owner auto-approves to Nợ and a
     * Pharmacist submits to Chờ duyệt. When the slip lands in Nợ, stock is restored and the invoice's
     * return status is updated (see {@link #applyReturnEffect}).</p>
     *
     * <p>Trước khi ghi, phiếu được đối chiếu với các phiếu vừa lập để không tạo bản sao khi người
     * dùng bấm Tạo lần thứ hai — xem {@link #findRecentDuplicate}.</p>
     *
     * @return id phiếu để chuyển hướng, kèm cờ cho biết đó là phiếu vừa tạo hay phiếu đã có.
     */
    @Transactional
    public SlipCreateOutcome createReturn(ReturnCreateRequest request,
                                          Integer currentAccountId,
                                          boolean isOwner,
                                          boolean asDraft) {
        if (request.getInvoiceId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn hóa đơn cần trả");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập lý do trả hàng");
        }

        Invoice invoice = invoiceRepository.findById(request.getInvoiceId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn"));
        assertReturnable(invoice);

        Account creator = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        // Tỷ lệ hoàn của PHIẾU NÀY: mặc định lấy từ thiết lập tài chính, người lập chỉnh tay được từng
        // phiếu, và áp đồng loạt cho mọi dòng trong phiếu.
        BigDecimal refundRate = resolveRefundRate(request.getRefundRate());

        Map<Integer, Invoicedetail> lineById = invoiceLinesOf(invoice.getId()).stream()
                .collect(Collectors.toMap(Invoicedetail::getId, line -> line, (a, b) -> a));

        // Collapse the posted rows onto the invoice lines, keeping only positive, validated quantities.
        Map<Integer, PreparedLine> prepared = new LinkedHashMap<>();
        for (ReturnLineRequest item : request.getItems()) {
            if (item == null || item.getInvoiceDetailId() == null
                    || item.getReturnQty() == null || item.getReturnQty() <= 0) {
                continue;
            }
            Invoicedetail line = lineById.get(item.getInvoiceDetailId());
            if (line == null) {
                throw new IllegalArgumentException("Dòng hóa đơn không thuộc hóa đơn đã chọn");
            }
            if (!isReturnableProductType(line.getProductID())) {
                throw new IllegalArgumentException("Sản phẩm \"" + productName(line)
                        + "\" không được phép trả (thiết bị y tế máy)");
            }
            int alreadyReturned = line.getReturnedQty() != null ? line.getReturnedQty() : 0;
            int returnable = line.getQuantity() - alreadyReturned;
            int qty = item.getReturnQty();
            if (qty > returnable) {
                throw new IllegalArgumentException("Số lượng trả của \"" + productName(line)
                        + "\" vượt quá số còn có thể trả (" + returnable + ")");
            }
            // Hard-coded by item type (the client value is ignored) — see isRestockableUnit.
            boolean restockable = isRestockableUnit(line);
            prepared.put(line.getId(), preparedLineOf(line, qty, restockable, refundRate));
        }

        if (prepared.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một dòng hàng cần trả");
        }

        BigDecimal totalRefund = prepared.values().stream()
                .map(PreparedLine::lineRefund)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // KHÔNG chặn theo hạn mức tiền mặt ở đây: phiếu trả chỉ ghi nhận việc khách trả hàng và số
        // tiền nhà thuốc NỢ lại khách — dược sĩ vẫn phải lập được đầy đủ dù số tiền lớn. Hạn mức nằm
        // ở bước CHI tiền thật (ExpenseService.assertCashRefundWithinLimit), nơi mới biết phần thực
        // hoàn sau khi cấn trừ công nợ.

        String status = asDraft ? ReturnStatus.DRAFT : (isOwner ? ReturnStatus.DEBT : ReturnStatus.PENDING);
        boolean approvedNow = ReturnStatus.DEBT.equals(status);

        String reason = request.getReason().trim();
        Map<Integer, Integer> qtyByInvoiceLine = prepared.values().stream()
                .collect(Collectors.toMap(line -> line.invoiceLine().getId(), PreparedLine::qty));
        Optional<Return> duplicate = findRecentDuplicate(invoice.getId(), currentAccountId, status,
                refundRate, totalRefund, reason, qtyByInvoiceLine);
        if (duplicate.isPresent()) {
            Return existing = duplicate.get();
            return SlipCreateOutcome.duplicate(existing.getId(), "Phiếu trả hàng "
                    + existing.getReturnCode() + " với đúng nội dung này đã được lập lúc "
                    + formatInstant(existing.getReturnDate())
                    + ". Hệ thống KHÔNG tạo thêm phiếu mới — đây là phiếu đã lưu.");
        }

        Return ret = new Return();
        ret.setReturnCode(temporaryCode());
        ret.setInvoiceID(invoice);
        ret.setPurchaseID(null);
        ret.setReturnedBy(creator);
        ret.setReturnDate(nowVn());
        ret.setReturnType(TYPE_CUSTOMER);
        // Phiếu trả CHỈ TÍNH tiền, không chi tiền: cách chi (tiền mặt / chuyển khoản) là dữ liệu của
        // phiếu chi bên Kế toán. 3 cột refundCash/refundBanking/refundCredit đã bị bỏ khỏi
        // bảng `return` — phiếu trả chỉ còn lưu tổng phải hoàn (totalRefund).
        ret.setTotalRefund(totalRefund);
        ret.setAppliedRefundRate(refundRate);
        // Số dự kiến cấn trừ vào công nợ hóa đơn gốc. Chốt lại theo dư nợ tại thời điểm DUYỆT
        // (xem applyDebtOffset) — phiếu nháp chỉ giữ số ước tính để màn chi tiết có gì hiển thị.
        ret.setOffsetDebtAmount(computeDebtOffset(invoice, totalRefund));
        ret.setReason(reason);
        ret.setNote(trimToNull(request.getNote()));
        ret.setStatus(status);
        if (approvedNow) {
            ret.setApprovedAt(nowVn());
        }

        Return savedReturn = returnRepository.save(ret);
        // Mã thật = TH- + id do DB cấp, ghi ngay sau INSERT (cùng transaction).
        savedReturn.setReturnCode(formatCode(savedReturn.getId()));

        List<Returndetail> details = new ArrayList<>();
        for (PreparedLine line : prepared.values()) {
            Returndetail detail = new Returndetail();
            detail.setReturnID(savedReturn);
            detail.setInvoiceDetailID(line.invoiceLine());
            detail.setProductID(line.invoiceLine().getProductID());
            detail.setProductUnitID(line.invoiceLine().getProductUnitID());
            // Temporarily the batch it was sold from; repointed to a fresh return-batch on approval.
            detail.setBatchID(line.invoiceLine().getBatchID());
            detail.setReturnQty(line.qty());
            detail.setBaseQtyRestored(line.baseQtyRestored());
            detail.setUnitSellPrice(line.unitSellPrice());
            detail.setLineRefund(line.lineRefund());
            // originalLineValue = giá trị GỐC 100%, lineRefund = số thực hoàn (gốc × tỷ lệ hoàn).
            // Chênh lệch 2 cột là phần nhà thuốc giữ lại — vẫn là doanh thu chịu thuế bình thường.
            detail.setOriginalLineValue(line.originalLineValue());
            detail.setRestockable(line.restockable());
            details.add(returndetailRepository.save(detail));
        }

        if (approvedNow) {
            applyReturnEffect(savedReturn, details);
            // Chốt lại trạng thái sau khi bù trừ công nợ đã ghi: hết nghĩa vụ tiền thì đi thẳng
            // "Hoàn thành", không phải "Nợ". Xem settledStatusOf().
            savedReturn.setStatus(settledStatusOf(savedReturn));
        } else if (ReturnStatus.PENDING.equals(
                savedReturn.getStatus()
        )) {
            workflowNotificationService
                    .returnPending(savedReturn);
        }

        return SlipCreateOutcome.created(savedReturn.getId());
    }

    /**
     * Tìm phiếu trả vừa lập có nội dung TRÙNG KHÍT với phiếu sắp tạo — dấu hiệu của một cú bấm Tạo
     * lặp lại chứ không phải một phiếu mới.
     *
     * <p>Cố ý so khớp toàn bộ nội dung (hóa đơn gốc, người lập, trạng thái đích, tỷ lệ hoàn, tổng
     * tiền, lý do, và từng dòng hàng với đúng số lượng) chứ KHÔNG chỉ so "cùng hóa đơn": khách trả
     * thêm một mặt hàng khác ngay sau đó là việc hợp lệ, chặn nhầm là dược sĩ không lập được phiếu.
     * Trùng khít tới từng dòng thì gần như chắc chắn là lần gửi lại của cùng một biểu mẫu.</p>
     *
     * <p>Cửa sổ {@link #DUPLICATE_WINDOW} đo trên {@code returnDate}, phải lấy mốc bằng
     * {@link #nowVn()} — cột đó lưu giờ VN gắn nhãn UTC (xem javadoc của {@code nowVn}), so với
     * {@code Instant.now()} là lệch 7 tiếng và cửa sổ thành vô nghĩa.</p>
     */
    private Optional<Return> findRecentDuplicate(Integer invoiceId,
                                                 Integer creatorId,
                                                 String status,
                                                 BigDecimal refundRate,
                                                 BigDecimal totalRefund,
                                                 String reason,
                                                 Map<Integer, Integer> qtyByInvoiceLine) {
        Instant since = nowVn().minus(DUPLICATE_WINDOW);
        for (Return candidate : returnRepository.findByInvoiceID_IdOrderByReturnDateDesc(invoiceId)) {
            if (candidate.getReturnDate() == null || candidate.getReturnDate().isBefore(since)) {
                // Danh sách đã sắp giảm dần theo ngày nên gặp phiếu ngoài cửa sổ là dừng được.
                break;
            }
            if (candidate.getReturnedBy() == null
                    || !Objects.equals(candidate.getReturnedBy().getId(), creatorId)
                    || !status.equals(candidate.getStatus())
                    || !sameAmount(candidate.getAppliedRefundRate(), refundRate)
                    || !sameAmount(candidate.getTotalRefund(), totalRefund)
                    || !reason.equals(candidate.getReason())) {
                continue;
            }
            if (qtyByInvoiceLine.equals(returnedQtyByInvoiceLine(candidate.getId()))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Các dòng của một phiếu trả, dạng {@code invoiceDetailId -> returnQty}, để so khớp nội dung. */
    private Map<Integer, Integer> returnedQtyByInvoiceLine(Integer returnId) {
        Map<Integer, Integer> qtyByLine = new LinkedHashMap<>();
        for (Returndetail detail : returndetailRepository.findByReturnIdWithRelations(returnId)) {
            if (detail.getInvoiceDetailID() != null) {
                qtyByLine.put(detail.getInvoiceDetailID().getId(), detail.getReturnQty());
            }
        }
        return qtyByLine;
    }

    /** So hai số tiền theo GIÁ TRỊ, không theo scale — {@code 80} và {@code 80.00} phải là một. */
    private boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    // ------------------------------------------------------------------ submit / approve / reject

    /** Sends a Nháp slip forward: Owner auto-approves to Nợ (and restocks), Pharmacist moves to Chờ duyệt. */
    @Transactional
    public void submit(Integer returnId, boolean isOwner) {
        Return ret = requireReturn(returnId);
        if (!isStatus(getStatusName(ret), ReturnStatus.DRAFT)) {
            throw new IllegalArgumentException("Chỉ có thể gửi duyệt phiếu đang ở trạng thái nháp");
        }
        if (isOwner) {
            markApproved(ret);
        } else {
            ret.setStatus(ReturnStatus.PENDING);
            returnRepository.save(ret);
            workflowNotificationService.returnPending(ret);
        }
    }

    /** Owner approves a pending slip → Nợ, restoring stock and updating the invoice's return status. */
    @Transactional
    public void approve(Integer returnId) {
        Return ret = requireReturn(returnId);
        if (!isStatus(getStatusName(ret), ReturnStatus.PENDING)) {
            throw new IllegalArgumentException("Chỉ có thể duyệt phiếu đang ở trạng thái chờ duyệt");
        }
        markApproved(ret);
        workflowNotificationService.returnApproved(ret);
    }

    /** Owner rejects a pending slip → Từ chối. No stock change. */
    @Transactional
    public void reject(Integer returnId) {
        Return ret = requireReturn(returnId);
        if (!isStatus(getStatusName(ret), ReturnStatus.PENDING)) {
            throw new IllegalArgumentException("Chỉ có thể từ chối phiếu đang ở trạng thái chờ duyệt");
        }
        ret.setStatus(ReturnStatus.REJECTED);
        ret.setApprovedAt(nowVn());
        returnRepository.save(ret);
        workflowNotificationService.returnRejected(ret);
    }

    private void markApproved(Return ret) {
        // DEBT là trạng thái TẠM ở đây, chỉ để applyReturnEffect chạy đúng nhánh "đã duyệt".
        // Trạng thái cuối được chốt lại ngay bên dưới, sau khi bù trừ công nợ đã ghi xong.
        ret.setStatus(ReturnStatus.DEBT);
        ret.setApprovedAt(nowVn());
        returnRepository.save(ret);
        applyReturnEffect(ret, returndetailRepository.findByReturnIdWithRelations(ret.getId()));
        ret.setStatus(settledStatusOf(ret));
        returnRepository.save(ret);
    }

    /**
     * Hoàn kho cho phiếu đã duyệt, rồi thay thế hoàn toàn hóa đơn gốc bằng một hóa đơn "Thay thế" mang
     * phần còn lại — <em>kể cả khoản nợ</em>, nợ của gốc bị xoá về 0 (xem {@link #createReplacementInvoice}).
     *
     * <p><strong>Không đụng quỹ:</strong> phiếu chỉ tính và lưu số tiền; việc chi trả thật là của phiếu chi.</p>
     *
     * <p>Ca làm việc lấy theo <strong>người TẠO phiếu</strong>, không phải người duyệt: giao dịch thật với
     * khách xảy ra lúc lập phiếu, còn Owner có thể duyệt sau ở ca khác — lấy theo người duyệt là gắn phiếu
     * nhầm ca (hoặc đẻ thêm một ca lạ cho Owner).</p>
     */
    private void applyReturnEffect(Return ret, List<Returndetail> details) {
        Shiftreport shift = shiftreportService.ensureOpenShiftFor(ret.getReturnedBy().getId());
        ret.setShiftReportID(shift);

        Invoice original = ret.getInvoiceID();
        for (Returndetail detail : details) {
            Invoicedetail invoiceLine = detail.getInvoiceDetailID();
            int already = invoiceLine.getReturnedQty() != null ? invoiceLine.getReturnedQty() : 0;
            invoiceLine.setReturnedQty(already + detail.getReturnQty());
            invoicedetailRepository.save(invoiceLine);

            if (Boolean.TRUE.equals(detail.getRestockable()) && detail.getBaseQtyRestored() != null
                    && detail.getBaseQtyRestored() > 0) {
                Batch returnBatch = cloneReturnBatch(detail.getBatchID(), detail.getBaseQtyRestored(), ret);
                detail.setBatchID(returnBatch);
                returndetailRepository.save(detail);
            }
        }

        // Bù trừ TRƯỚC khi phát hành hóa đơn điều chỉnh/thay thế: hóa đơn thay thế kế thừa phần nợ CÒN LẠI
        // sau khi đã cấn trừ, nếu chạy sau thì nó sẽ ôm nguyên số nợ chưa trừ.
        applyDebtOffset(ret, original);

        // MỌI phiếu trả đều phát hành hóa đơn THAY THẾ, không phân biệt hóa đơn gốc đã ký hay chưa.
        createReplacementInvoice(ret, original, details);

        // Cột riêng invoice.returnStatus nên ghi được cho CẢ hóa đơn đã ký lẫn chưa ký.
        updateInvoiceReturnStatus(original);

        returnRepository.save(ret);
    }

    /**
     * Hóa đơn gốc bị vô hiệu hoàn toàn nên clone TOÀN BỘ phần còn lại sang một hóa đơn THAY THẾ mới. Nợ
     * của gốc xoá về 0 và chuyển sang hóa đơn mới; sai sót về sau phải tham chiếu bản thay thế, không
     * phải bản gốc (chặn bởi {@link #isSupersededByReturn}). Trả hết + hoàn 100% thì không phát hành gì.
     *
     * <p><b>Khi tỷ lệ hoàn &lt; 100%</b>, hóa đơn thay thế mang đúng phần khách THỰC GIỮ, và một dòng
     * vừa bị trả một phần được tách thành HAI: <b>hàng còn lại</b> (thành tiền = giá trị gốc của đúng số
     * hàng đó) và <b>phần giữ lại</b> ({@code quantity = 0}).</p>
     *
     * <p><b>Bất biến:</b> dòng {@code quantity = 0} ⇔ <b>tiền không kèm hàng</b>, và mọi khoản như vậy
     * của cùng một (sản phẩm, đơn vị, lô) gộp vào đúng MỘT dòng ({@link #moneyOnlyLine}) — gồm cả phần
     * giữ lại của các lần trả TRƯỚC và phần tiền vốn không gắn hộp nào (dòng bán bị tách theo lô rồi làm
     * tròn về SL 0). Bỏ sót nhóm thứ hai là tổng hóa đơn ≠ tổng các dòng.</p>
     *
     * <p><b>Vì sao phải tách:</b> nếu một dòng ôm cả hai thì phần giữ lại nằm lẫn trong giá trị số hàng
     * còn lại, nên <b>lần trả sau lại hoàn tiếp một phần của chính khoản đã giữ</b> — trả 5 hộp làm 2 lần
     * ở mức 80% thì nhà thuốc chỉ còn giữ 13,6% thay vì 20%, trả từng hộp một thì tụt còn 5,9%.</p>
     *
     * <p>Dòng "phần giữ lại" không bao giờ trả tiếp được: {@code loadInvoiceLines} lọc bỏ dòng có số
     * lượng trả được bằng 0.</p>
     */
    private void createReplacementInvoice(Return ret, Invoice original, List<Returndetail> details) {
        Map<Integer, Returndetail> returnedByLine = details.stream()
                .collect(Collectors.toMap(d -> d.getInvoiceDetailID().getId(), d -> d, (a, b) -> a));

        // Giữ dòng còn hàng, HOẶC dòng đã trả hết nhưng còn phần tiền giữ lại (hoàn < 100%). Trả hết +
        // hoàn 100% ⇒ không còn dòng nào ⇒ không phát hành hóa đơn "Thay thế" rỗng (vừa rác danh sách,
        // vừa lọt lại vào danh sách chọn để trả tiếp).
        List<Invoicedetail> remainingLines = invoiceLinesOf(original.getId()).stream()
                .filter(line -> remainingQtyOf(line) > 0
                        || goodsValueOf(line, returnedByLine).signum() > 0
                        || retainedValueOf(line, returnedByLine).signum() > 0)
                .toList();
        if (remainingLines.isEmpty()) {
            return;
        }

        BigDecimal refund = nz(ret.getTotalRefund());

        BigDecimal newSubtotal = nz(original.getSubtotal()).subtract(refund).max(BigDecimal.ZERO);
        BigDecimal newTotal = nz(original.getTotal()).subtract(refund).max(BigDecimal.ZERO);
        // Bản thay thế kế thừa TOÀN BỘ trạng thái còn lại của hóa đơn gốc — gồm cả công nợ: nợ chuyển sang
        // hóa đơn mới, hóa đơn gốc bị vô hiệu nên xóa nợ về 0. Đây là nghiệp vụ tạo hóa đơn mới từ hóa đơn
        // cũ (không phải dòng tiền của phiếu trả) nên chuyển NGUYÊN số nợ CÒN LẠI — phần đã cấn trừ vào
        // tiền hoàn thì applyDebtOffset đã trừ khỏi hóa đơn gốc TRƯỚC khi vào đây, không trừ hai lần.
        BigDecimal newDebt = nz(original.getDebtAmount());

        original.setDebtAmount(BigDecimal.ZERO);
        invoiceService.persistInvoice(original);

        Invoice repl = new Invoice();
        // Ký hiệu của bản thay thế phải là ký hiệu CHƯA KÝ. Hóa đơn gốc đã ký thì ký hiệu của nó đã bị
        // đổi K → C (có mã CQT) lúc ký; bản thay thế là hóa đơn MỚI, chưa gửi CQT (signAt/signBy để
        // trống), nên bê nguyên ký hiệu của gốc là hóa đơn tự nhận có mã CQT trong khi chưa hề được ký.
        repl.setInvoicePattern(toUnsignedInvoicePattern(original.getInvoicePattern()));
        repl.setInvoiceNumber(generateInvoiceNumber());
        repl.setDate(LocalDateTime.now(VN_ZONE));
        // Toàn bộ data khác của hóa đơn gốc (nhân viên bán, khách hàng, đơn thuốc) được giữ nguyên —
        // đây là bản thay thế của CHÍNH giao dịch đó, không phải giao dịch của người lập phiếu trả.
        repl.setEmployeeID(original.getEmployeeID());
        repl.setCustomerID(original.getCustomerID());
        repl.setInvoiceType(INVOICE_TYPE_REPLACEMENT);
        repl.setOriginalInvoiceID(original);
        repl.setReturnID(ret);
        repl.setPrescriptionRequired(original.getPrescriptionRequired());
        repl.setPrescriptionCode(original.getPrescriptionCode());
        repl.setDiscount(nz(original.getDiscount()));
        repl.setSubtotal(newSubtotal);
        repl.setTotal(newTotal);
        repl.setPaidByCash(nz(original.getPaidByCash()));
        repl.setPaidByBanking(nz(original.getPaidByBanking()));
        repl.setDebtAmount(newDebt);
        repl.setStatus(newDebt.compareTo(BigDecimal.ZERO) > 0 ? INVOICE_STATUS_DEBT : INVOICE_STATUS_COMPLETED);
        // Bản thay thế là hóa đơn MỚI của phần hàng khách còn giữ — chưa trả lần nào.
        repl.setReturnStatus(INVOICE_RETURN_NONE);
        repl.setNote(buildAdjustmentNote(ret, original, false));

        Invoice savedRepl = invoiceService.persistInvoice(repl);

        // Tiền không kèm hàng được GỘP theo sản phẩm + đơn vị + lô rồi mới ghi, thay vì mỗi dòng nguồn một
        // dòng: sau vài lần trả, hóa đơn sẽ có một loạt dòng "không kèm hàng" trông y hệt nhau của cùng một
        // sản phẩm, đọc rất rối mà không nói thêm được gì (chúng đều là tiền đã thu, không trả tiếp được).
        Map<String, Invoicedetail> moneyOnlyByKey = new LinkedHashMap<>();

        for (Invoicedetail line : remainingLines) {
            int remainingQty = remainingQtyOf(line);
            Returndetail matched = returnedByLine.get(line.getId());

            BigDecimal goodsSubtotal = goodsValueOf(line, returnedByLine);
            BigDecimal retained = retainedValueOf(line, returnedByLine);

            if (remainingQty > 0) {
                Invoicedetail goods = cloneLine(savedRepl, line);
                goods.setQuantity(remainingQty);
                goods.setBaseQtyDeducted(Math.max(0, line.getBaseQtyDeducted()
                        - (matched != null && matched.getBaseQtyRestored() != null
                                ? matched.getBaseQtyRestored() : 0)));
                goods.setSubtotal(goodsSubtotal);
                invoicedetailRepository.save(goods);

                addMoneyOnly(moneyOnlyByKey, savedRepl, line, retained);
                continue;
            }

            // Không còn hộp nào trên dòng này ⇒ MỌI đồng còn lại của nó đều là tiền KHÔNG kèm hàng (phần
            // giữ lại của lần trả này + phần vốn đã không gắn với hộp nào từ trước).
            addMoneyOnly(moneyOnlyByKey, savedRepl, line, goodsSubtotal.add(retained));
        }

        moneyOnlyByKey.values().forEach(invoicedetailRepository::save);
    }

    /** Cộng dồn tiền-không-kèm-hàng vào dòng gộp của cùng sản phẩm + đơn vị + lô. Bỏ qua khi bằng 0. */
    private void addMoneyOnly(Map<String, Invoicedetail> moneyOnlyByKey, Invoice replacement,
                              Invoicedetail source, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        String key = idOf(source.getProductID() != null ? source.getProductID().getProductID() : null)
                + "|" + idOf(source.getProductUnitID() != null ? source.getProductUnitID().getId() : null)
                + "|" + idOf(source.getBatchID() != null ? source.getBatchID().getId() : null);
        Invoicedetail existing = moneyOnlyByKey.get(key);
        if (existing == null) {
            moneyOnlyByKey.put(key, moneyOnlyLine(replacement, source, amount));
            return;
        }
        existing.setSubtotal(nz(existing.getSubtotal()).add(amount));
    }

    private String idOf(Integer id) {
        return id == null ? "-" : id.toString();
    }

    /**
     * Dòng TIỀN KHÔNG KÈM HÀNG của hóa đơn thay thế ({@code quantity = 0}): phần nhà thuốc giữ lại khi
     * hoàn &lt; 100%, cộng phần tiền của dòng cũ vốn đã không gắn với hộp nào.
     *
     * <p>Dòng này không bao giờ trả tiếp được — {@code loadInvoiceLines} lọc bỏ dòng có số lượng trả được
     * bằng 0 — nên tiền ở đây không bị hoàn thêm lần nữa.</p>
     */
    private Invoicedetail moneyOnlyLine(Invoice replacement, Invoicedetail source, BigDecimal amount) {
        Invoicedetail fee = cloneLine(replacement, source);
        fee.setQuantity(0);
        fee.setBaseQtyDeducted(0);
        fee.setSubtotal(amount);
        return fee;
    }

    /**
     * Dòng hàng của hóa đơn thay thế, phần dùng chung giữa dòng HÀNG CÒN LẠI và dòng PHẦN GIỮ LẠI.
     * Người gọi tự đặt {@code quantity / baseQtyDeducted / subtotal}.
     */
    /**
     * Đưa ký hiệu hóa đơn về dạng CHƯA KÝ: ký tự thứ 2 là {@code K} (không mã CQT), {@code C} là đã có mã
     * CQT — {@code InvoiceService.sign()} đổi K → C khi ký. Hóa đơn chưa ký thì gọi vào đây là không đổi gì.
     */
    private String toUnsignedInvoicePattern(String pattern) {
        if (pattern == null || pattern.length() < 2) {
            return pattern;
        }
        return pattern.charAt(0) + "K" + pattern.substring(2);
    }

    private Invoicedetail cloneLine(Invoice replacement, Invoicedetail source) {
        Invoicedetail clone = new Invoicedetail();
        clone.setInvoiceID(replacement);
        clone.setProductID(source.getProductID());
        clone.setProductUnitID(source.getProductUnitID());
        clone.setBatchID(source.getBatchID());
        clone.setUnitName(source.getUnitName());
        clone.setUnitSellPrice(source.getUnitSellPrice());
        clone.setReturnedQty(0);
        clone.setNote(source.getNote());
        return clone;
    }

    // ------------------------------------------------------------------ bù trừ công nợ (netting)

    /**
     * Số tiền hoàn được cấn trừ vào công nợ của chính hóa đơn gốc:
     * {@code offsetDebtAmount = MIN(totalRefund, dư nợ hiện tại)}. Trả 0 khi nhà thuốc tắt
     * {@code Financialsetting.autoOffsetDebtOnRefund} — lúc đó tiền hoàn và công nợ được xử lý tách rời
     * qua phiếu thu/phiếu chi.
     */
    private BigDecimal computeDebtOffset(Invoice invoice, BigDecimal totalRefund) {
        if (!isAutoOffsetDebt()) {
            return BigDecimal.ZERO;
        }
        BigDecimal debt = nz(invoice != null ? invoice.getDebtAmount() : null);
        return debt.min(nz(totalRefund)).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Bù trừ khi phiếu được duyệt: chốt lại {@code offsetDebtAmount} theo dư nợ TẠI THỜI ĐIỂM DUYỆT
     * (khách có thể đã trả bớt nợ giữa lúc lập phiếu và lúc Owner duyệt) rồi trừ thẳng vào
     * {@code Invoice.debtAmount} trong cùng transaction. Phần còn lại là tiền thật còn phải hoàn khách,
     * do phiếu chi bên Kế toán chi ra; bằng 0 thì phiếu tất toán ngay (xem {@link #settledStatusOf}).
     *
     * <p><strong>KHÔNG sinh cặp Income/Expense cho phần cấn trừ</strong> — phần cấn trừ đã được thể hiện
     * bằng chính hóa đơn thay thế, chỉ phần tiền thật chi ra mới thành một phiếu chi.</p>
     */
    private void applyDebtOffset(Return ret, Invoice invoice) {
        BigDecimal offset = computeDebtOffset(invoice, ret.getTotalRefund());
        ret.setOffsetDebtAmount(offset);
        if (offset.signum() <= 0 || invoice == null) {
            return;
        }
        invoice.setDebtAmount(nz(invoice.getDebtAmount()).subtract(offset).max(BigDecimal.ZERO));
        invoiceService.persistInvoice(invoice);
    }

    /**
     * Tiền thật còn phải hoàn khách sau khi đã cấn trừ vào công nợ hóa đơn gốc. Bằng 0 nghĩa là phiếu
     * không còn nghĩa vụ tiền nào — công nợ đã nuốt trọn khoản hoàn.
     *
     * <p>Chỉ có nghĩa SAU khi {@link #applyDebtOffset} đã chạy; trước đó {@code offsetDebtAmount} mới
     * là số ước tính lúc lập phiếu.</p>
     */
    private BigDecimal cashRefundDue(Return ret) {
        return nz(ret.getTotalRefund()).subtract(nz(ret.getOffsetDebtAmount())).max(BigDecimal.ZERO);
    }

    /**
     * Trạng thái của phiếu vừa được duyệt: {@link ReturnStatus#DEBT} nếu còn phải hoàn tiền,
     * {@link ReturnStatus#COMPLETED} nếu bù trừ công nợ đã nuốt trọn khoản hoàn.
     *
     * <p><strong>Phải gọi SAU {@link #applyReturnEffect}</strong>, vì {@code offsetDebtAmount} chỉ được
     * chốt theo dư nợ thật bên trong đó. Gọi sớm hơn là đọc phải số ước tính lúc lập phiếu.</p>
     *
     * <p>Đây là đường THỨ NHẤT đưa phiếu vào {@link ReturnStatus#COMPLETED}; đường thứ hai — phiếu chi
     * hoàn tiền trả xong thì chuyển tiếp — xem {@link #syncStatusAfterRefundPayment(Integer)}.</p>
     */
    private String settledStatusOf(Return ret) {
        return cashRefundDue(ret).signum() > 0 ? ReturnStatus.DEBT : ReturnStatus.COMPLETED;
    }

    /**
     * Đường THỨ HAI vào {@link ReturnStatus#COMPLETED}: được {@code ExpenseService.confirmPayment()}
     * gọi ngay khi một phiếu chi {@code RETURN_REFUND_PAYOUT} vừa thực chi thật.
     *
     * <p>Chỉ chuyển {@link ReturnStatus#DEBT} → {@link ReturnStatus#COMPLETED} khi tổng các phiếu chi
     * ĐÃ THỰC CHI ({@code ExpenseService.disbursedRefundAmount()}) phủ hết {@link #cashRefundDue}.
     * Cố ý dùng "đã thực chi", không dùng "đã cam kết" ({@code committedByReturnId()}/
     * {@code outstandingRefundByReturnId()}) — nếu không phiếu trả sẽ tất toán ngay lúc phiếu chi
     * được TẠO, trước cả khi Owner xác nhận thanh toán.</p>
     *
     * <p>No-op cho một phiếu không ở {@link ReturnStatus#DEBT} (đã tất toán sẵn, còn Nháp/Chờ duyệt,
     * bị Từ chối…) — không có gì để đồng bộ.</p>
     */
    @Transactional
    public void syncStatusAfterRefundPayment(Integer returnId) {
        if (returnId == null) {
            return;
        }
        Return ret = returnRepository.findById(returnId).orElse(null);
        if (ret == null || !ReturnStatus.DEBT.equals(ret.getStatus())) {
            return;
        }
        BigDecimal stillOwed = cashRefundDue(ret).subtract(expenseService.disbursedRefundAmount(returnId));
        if (stillOwed.compareTo(BigDecimal.ZERO) <= 0) {
            ret.setStatus(ReturnStatus.COMPLETED);
            returnRepository.save(ret);
        }
    }

    /** Số lượng của dòng hóa đơn còn CHƯA trả (đã trừ mọi lần trả trước đó). */
    private int remainingQtyOf(Invoicedetail line) {
        int quantity = line.getQuantity() != null ? line.getQuantity() : 0;
        int returned = line.getReturnedQty() != null ? line.getReturnedQty() : 0;
        return Math.max(0, quantity - returned);
    }

    /**
     * Giá trị của phần HÀNG còn lại trên dòng = thành tiền cũ − giá trị GỐC 100% của phần vừa trả (KHÔNG
     * phải trừ số đã hoàn). Trừ theo giá trị gốc thì phần nhà thuốc giữ lại không nằm lẫn trong giá trị số
     * hàng còn lại, nhờ đó lần trả sau tính trên đúng căn cứ — xem {@link #createReplacementInvoice}.
     */
    private BigDecimal goodsValueOf(Invoicedetail line, Map<Integer, Returndetail> returnedByLine) {
        Returndetail matched = returnedByLine.get(line.getId());
        BigDecimal returnedValue = matched != null ? nz(matched.getOriginalLineValue()) : BigDecimal.ZERO;
        return nz(line.getSubtotal()).subtract(returnedValue).max(BigDecimal.ZERO);
    }

    /**
     * Phần tiền của dòng nhà thuốc GIỮ LẠI ở lần trả này = giá trị gốc dòng trả − số thực hoàn (tức phần
     * {@code 100% − appliedRefundRate}). Bằng 0 khi hoàn đủ 100%.
     */
    private BigDecimal retainedValueOf(Invoicedetail line, Map<Integer, Returndetail> returnedByLine) {
        Returndetail matched = returnedByLine.get(line.getId());
        if (matched == null) {
            return BigDecimal.ZERO;
        }
        return nz(matched.getOriginalLineValue()).subtract(nz(matched.getLineRefund())).max(BigDecimal.ZERO);
    }

    /**
     * Hóa đơn F0 — gốc đầu tiên của cả chuỗi thay thế. Đi ngược {@code originalInvoiceID} cho tới hóa đơn
     * không có bản trước nữa.
     *
     * <p>Phải lần từng bước vì cột {@code invoice.rootInvoiceID} (trỏ thẳng về F0) đã bị bỏ khỏi bảng.
     * Chuỗi thực tế rất ngắn (mỗi lần trả hàng thêm đúng 1 bậc), nhưng vẫn chặn vòng lặp vô hạn bằng
     * {@code MAX_INVOICE_CHAIN_DEPTH} phòng dữ liệu bẩn trỏ vòng.</p>
     */
    private Invoice rootOf(Invoice invoice) {
        Invoice current = invoice;
        for (int hops = 0; hops < MAX_INVOICE_CHAIN_DEPTH; hops++) {
            Invoice previous = current.getOriginalInvoiceID();
            if (previous == null || previous.getId() == null || previous.getId().equals(current.getId())) {
                return current;
            }
            current = previous;
        }
        return current;
    }

    /**
     * Ghi trạng thái trả hàng vào cột riêng {@code invoice.returnStatus} (NONE/PARTIAL/FULL) và trả
     * {@code status} về thuần nợ/vòng đời ("Còn nợ" / "Hoàn thành").
     *
     * <p>Hai cột tách riêng vì một hóa đơn có thể VỪA còn nợ VỪA đã trả hàng 1 phần — một cột không
     * chứa nổi 2 nghĩa. {@code returnStatus} chỉ là bản CACHE, nguồn đúng vẫn là
     * {@link #invoiceReturnCode} tính động, nên dữ liệu cũ chưa backfill cũng không sai.</p>
     *
     * <p><b>Việc ký KHÔNG còn nằm trong {@code status}</b> — nó có 2 cột riêng {@code signAt}/{@code signBy}.
     * Hóa đơn đã ký vẫn được tính lại {@code status} theo công nợ như mọi hóa đơn khác; chỉ DỮ LIỆU CŨ còn
     * giữ chuỗi "Đã ký" trong {@code status} mới không bị ghi đè, xem {@link #hasLegacySignedStatus}.</p>
     */
    private void updateInvoiceReturnStatus(Invoice invoice) {
        if (invoice == null) {
            return;
        }
        invoice.setReturnStatus(invoiceReturnCode(invoice));
        if (!hasLegacySignedStatus(invoice)) {
            invoice.setStatus(nz(invoice.getDebtAmount()).signum() > 0
                    ? INVOICE_STATUS_DEBT : INVOICE_STATUS_COMPLETED);
        }
        invoiceService.persistInvoice(invoice);
    }

    private Batch cloneReturnBatch(Batch original, int quantity, Return ret) {
        Batch batch = new Batch();
        Integer origBatchId = original != null ? original.getId() : null;
        // Mã lô hàng trả RT-{id phiếu trả}-L{id lô gốc}, vd RT-000004-L4 — truy ngược được cả phiếu trả
        // lẫn lô đã bán ra ngay trên mã lô.
        batch.setBatchCode(truncate("RT-" + String.format("%06d", ret.getId())
                + "-L" + (origBatchId != null ? origBatchId : 0), 50));
        batch.setBatchName(returnBatchName(original, ret));
        batch.setProductID(original != null ? original.getProductID() : null);
        // Giữ liên kết về ĐÚNG dòng phiếu nhập đã mua hàng này: khách trả lại thì vẫn phải trả về NCC đó
        // được, mà để null là lô này vô hình với màn trả hàng NCC (bên đó chỉ nhìn lô có phiếu nhập).
        // KHÔNG phải "nhập hàng lần hai" — không cộng công nợ NCC, không sinh phiếu nhập mới.
        batch.setPurchaseDetailID(original != null ? original.getPurchaseDetailID() : null);
        batch.setStorageQuantity(quantity);
        batch.setImportUnitID(original != null ? original.getImportUnitID() : null);
        // Số lượng THỰC được trả về, quy sang đơn vị nhập — KHÔNG copy của lô gốc: lô gốc nhập 100 Hộp mà
        // khách chỉ trả 5 Hộp thì màn Lịch sử tồn kho vẫn hiện "+100 Hộp" (nó lấy thẳng cột này), và
        // PurchaseinvoiceService.batchWasTouchedSinceImport cũng so sai.
        batch.setImportQtyInUnit(toImportUnitQuantity(quantity, original));
        batch.setImportPrice(original != null && original.getImportPrice() != null
                ? original.getImportPrice() : BigDecimal.ZERO);
        batch.setImportPricePerBase(original != null && original.getImportPricePerBase() != null
                ? original.getImportPricePerBase() : BigDecimal.ZERO);
        // ⚠️ PHẢI GIỮ nowVn(), ĐỪNG "sửa cho đúng" thành Instant.now(): nowVn() nhét giờ VN vào một
        // Instant gắn nhãn UTC, và ProductService.formatBatchImportDate() đã bù trừ đúng theo quy ước đó
        // (nhận diện lô mã "RT-" rồi trừ 7 tiếng). Đổi một đầu là ngày nhập hiện sớm hơn thật 7 tiếng —
        // phải sửa đồng thời cả hai đầu, trong đợt dọn quy ước nowVn() của cả dự án.
        batch.setImportDate(nowVn());
        batch.setProductionDate(original != null ? original.getProductionDate() : null);
        batch.setExpirationDate(original != null ? original.getExpirationDate() : null);
        batch.setLotNumber(original != null ? original.getLotNumber() : null);
        batch.setStatus(true);

        batch.setNote(
                "Hàng trả từ phiếu "
                        + formatCode(ret.getId())
        );

        Batch savedBatch =
                batchRepository.save(batch);
        if (inventoryAlertEventService != null
                && savedBatch.getProductID() != null) {

            inventoryAlertEventService
                    .checkBatchAfterCommit(
                            savedBatch.getProductID()
                                    .getProductID(),
                            savedBatch.getId()
                    );
        }

        return savedBatch;
    }

    /**
     * Tên lô hàng trả = <strong>{@code <số lô gốc>-TH<id phiếu trả>}</strong>, ví dụ
     * {@code LOT-002-TH000001}.
     *
     * <p>Dựng từ {@code lotNumber} chứ không phải {@code batchName} của lô gốc: {@code batchName} của
     * lô nhập còn kèm tên sản phẩm ở đầu ("Bifina R Health Aid - LOT-002"), nối thêm vào sẽ vừa dài quá
     * 50 ký tự vừa lặp tên sản phẩm — trong khi cột này đứng cạnh cột tên sản phẩm rồi.</p>
     *
     * <p><strong>Đuôi mã phiếu trả là bắt buộc:</strong> hàng khách trả về đúng là lô {@code LOT-002}
     * thật nhưng nằm ở một dòng {@code batch} RIÊNG, mà cùng một sản phẩm không được có hai lô trùng
     * tên. Lấy mã phiếu trả thì vừa duy nhất vừa truy ngược được phiếu nào sinh ra lô.</p>
     */
    private String returnBatchName(Batch original, Return ret) {
        String lot = original != null ? trimToNull(original.getLotNumber()) : null;
        if (lot == null) {
            // Lô gốc không ghi số lô — lùi về tên lô gốc để vẫn còn manh mối truy nguồn.
            lot = original != null ? trimToNull(original.getBatchName()) : null;
        }
        String suffix = "TH" + String.format("%06d", ret.getId());
        return truncate(lot == null ? suffix : lot + "-" + suffix, 50);
    }

    /**
     * Quy số lượng ở ĐƠN VỊ CƠ SỞ về đơn vị nhập của lô, cho cột {@code batch.importQtyInUnit}.
     *
     * <p>Ví dụ lô nhập theo Hộp (1 Hộp = 20 Cái), khách trả lại 100 Cái ⇒ ghi 5 Hộp. Nhờ vậy màn Lịch
     * sử tồn kho hiện "+5 Hộp" đúng bằng 100 Cái thật sự vào kho, thay vì "+100 Hộp".</p>
     *
     * <p>Không có đơn vị nhập / tỉ lệ quy đổi thì trả nguyên số cơ sở (coi tỉ lệ = 1). Phép chia làm
     * tròn HALF_UP: hàng chỉ nhập lại kho khi bán bằng đơn vị đóng gói mặc định nên gần như luôn chia
     * hết; nếu lệch thì chỉ lệch ở con số hiển thị, {@code storageQuantity} vẫn là số cơ sở chính xác.</p>
     */
    private Integer toImportUnitQuantity(int baseQuantity, Batch original) {
        Productunit importUnit = original != null ? original.getImportUnitID() : null;
        BigDecimal ratio = importUnit != null ? importUnit.getRatio() : null;
        if (ratio == null || ratio.compareTo(BigDecimal.ZERO) <= 0) {
            return baseQuantity;
        }
        return BigDecimal.valueOf(baseQuantity)
                .divide(ratio, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    /**
     * NONE / PARTIAL / FULL derived from how much of the invoice's lines have been returned —
     * {@code Σ returnedQty} vs {@code Σ quantity}.
     *
     * <p>Đây là NGUỒN ĐÚNG, tính động mỗi lần đọc; cột {@code invoice.returnStatus} chỉ là bản cache
     * ghi lại kết quả này lúc duyệt phiếu (xem {@link #updateInvoiceReturnStatus}) để báo cáo/lọc cho
     * nhanh. Nhờ vậy hóa đơn cũ chưa backfill cột vẫn được đánh giá đúng.</p>
     */
    private String invoiceReturnCode(Invoice invoice) {
        return returnCodeOf(invoiceLinesOf(invoice.getId()));
    }

    /** Bản nhận sẵn dòng chi tiết, cho người gọi đã đọc chúng một lần rồi (xem {@link ReturnableCandidate}). */
    private String returnCodeOf(List<Invoicedetail> lines) {
        if (lines.isEmpty()) {
            // Hóa đơn không có dòng nào thì không còn gì để trả — coi như đã trả hết để nó bị loại khỏi
            // danh sách chọn (chặn cả hóa đơn "Thay thế" rỗng do bản cũ sinh ra trước khi fix).
            return INVOICE_RETURN_FULL;
        }
        boolean anyReturned = false;
        boolean allReturned = true;
        for (Invoicedetail line : lines) {
            int returned = line.getReturnedQty() != null ? line.getReturnedQty() : 0;
            int quantity = line.getQuantity() != null ? line.getQuantity() : 0;
            if (returned > 0) {
                anyReturned = true;
            }
            if (returned < quantity) {
                allReturned = false;
            }
        }
        return allReturned ? INVOICE_RETURN_FULL
                : (anyReturned ? INVOICE_RETURN_PARTIAL : INVOICE_RETURN_NONE);
    }

    private boolean isFullyReturned(Invoice invoice) {
        return INVOICE_RETURN_FULL.equals(invoiceReturnCode(invoice));
    }

    // ------------------------------------------------------------------ detail

    @Transactional(readOnly = true)
    public ReturnDetailPageResponse getDetail(Integer returnId) {
        Return ret = returnRepository.findByIdWithRelations(returnId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng"));

        List<Returndetail> details = returndetailRepository.findByReturnIdWithRelations(returnId);
        List<ReturnDetailItemResponse> items = details.stream().map(this::toDetailItem).toList();

        int totalQuantity = details.stream()
                .map(Returndetail::getReturnQty)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        BigDecimal totalOriginalValue = details.stream()
                .map(Returndetail::getOriginalLineValue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String statusName = getStatusName(ret);
        Invoice invoice = ret.getInvoiceID();
        Customer customer = invoice != null ? invoice.getCustomerID() : null;
        // Đã trừ phiếu chi RETURN_REFUND_PAYOUT còn sống — 0 nghĩa là đã hoàn đủ, không phải "chưa
        // ai chi" (xem ExpenseService.outstandingRefundByReturnId).
        BigDecimal cashRefundDue = expenseService.outstandingRefundByReturnId(List.of(ret))
                .getOrDefault(ret.getId(), BigDecimal.ZERO);

        return new ReturnDetailPageResponse(
                ret.getId(),
                formatCode(ret.getId()),
                ret.getReturnDate(),
                formatInstant(ret.getReturnDate()),
                invoice != null ? invoice.getId() : null,
                invoice != null ? invoice.getInvoiceNumber() : "—",
                customer != null ? customer.getName() : "Khách lẻ",
                ret.getReturnedBy() != null ? ret.getReturnedBy().getName() : "Không rõ",
                ret.getReturnType(),
                returnTypeDisplay(ret.getReturnType()),
                ret.getReason(),
                ret.getNote(),
                statusName,
                statusCssClass(statusName),
                formatInstant(ret.getApprovedAt()),
                details.size(),
                totalQuantity,
                ret.getTotalRefund(),
                ret.getOffsetDebtAmount(),
                cashRefundDue,
                ret.getAppliedRefundRate(),
                // Tổng giá trị gốc 100% của hàng trả, và phần nhà thuốc giữ lại (chênh do tỷ lệ hoàn < 100%).
                totalOriginalValue,
                totalOriginalValue.subtract(nz(ret.getTotalRefund())).max(BigDecimal.ZERO),
                isStatus(statusName, ReturnStatus.COMPLETED),
                items);
    }

    // ------------------------------------------------------------------ mapping helpers

    private ReturnListItemResponse toListItem(Return ret, List<Returndetail> details,
                                              Map<Integer, BigDecimal> outstandingRefund) {
        Invoice invoice = ret.getInvoiceID();
        Customer customer = invoice != null ? invoice.getCustomerID() : null;
        String statusName = getStatusName(ret);

        return new ReturnListItemResponse(
                ret.getId(),
                formatCode(ret.getId()),
                ret.getReturnDate(),
                formatInstant(ret.getReturnDate()),
                invoice != null ? invoice.getInvoiceNumber() : "—",
                customer != null ? customer.getName() : "Khách lẻ",
                ret.getReturnedBy() != null ? ret.getReturnedBy().getName() : "Không rõ",
                details.size(),
                ret.getTotalRefund(),
                ret.getOffsetDebtAmount(),
                // Đã trừ phiếu chi RETURN_REFUND_PAYOUT còn sống — cùng công thức với màn chi tiết
                // để hai màn không bao giờ lệch số (xem ExpenseService.outstandingRefundByReturnId).
                outstandingRefund.getOrDefault(ret.getId(), BigDecimal.ZERO),
                ret.getReturnType(),
                returnTypeDisplay(ret.getReturnType()),
                statusName,
                statusCssClass(statusName),
                isApprovedStatus(statusName),
                isStatus(statusName, ReturnStatus.COMPLETED));
    }

    /** Phiếu đã qua bước duyệt — cả "Nợ" (còn phải hoàn) lẫn "Hoàn thành" (đã tất toán). */
    private boolean isApprovedStatus(String statusName) {
        return isStatus(statusName, ReturnStatus.DEBT) || isStatus(statusName, ReturnStatus.COMPLETED);
    }

    private ReturnDetailItemResponse toDetailItem(Returndetail detail) {
        Product product = detail.getProductID();
        Productunit unit = detail.getProductUnitID();
        Batch batch = detail.getBatchID();
        boolean restockable = Boolean.TRUE.equals(detail.getRestockable());

        return new ReturnDetailItemResponse(
                product != null ? product.getProductID() : null,
                product != null ? product.getName() : "Không rõ",
                // TÊN lô, không phải SỐ lô — xem javadoc của ReturnDetailItemResponse.batchName.
                batch != null ? batch.getBatchName() : "",
                batch != null ? formatLocalDate(batch.getExpirationDate()) : "",
                unit != null ? unit.getUnitName() : "",
                detail.getReturnQty(),
                detail.getUnitSellPrice(),
                detail.getOriginalLineValue(),
                detail.getLineRefund(),
                restockable,
                restockable ? "Nhập lại kho" : "Không nhập lại");
    }

    private ReturnInvoiceLineResponse toInvoiceLine(Invoicedetail line) {
        Product product = line.getProductID();
        Batch batch = line.getBatchID();
        int already = line.getReturnedQty() != null ? line.getReturnedQty() : 0;

        return new ReturnInvoiceLineResponse(
                line.getId(),
                product != null ? product.getProductID() : null,
                product != null ? product.getName() : "Không rõ",
                batch != null ? batch.getBatchName() : "",
                batch != null ? formatLocalDate(batch.getExpirationDate()) : "",
                line.getUnitName(),
                line.getQuantity(),
                already,
                line.getQuantity() - already,
                line.getUnitSellPrice(),
                isRestockableUnit(line));
    }

    /**
     * Restockable only when the sold unit is the manufacturer's default packaging unit
     * ({@code productunit.isDefault}). Loose units (isDefault=false) cannot go back to
     * stock; medical-device "máy" / prescription invoices are already blocked from return
     * upstream. This is hard-coded (no manual checkbox).
     */
    private boolean isRestockableUnit(Invoicedetail line) {
        Productunit unit = line.getProductUnitID();
        return unit != null && Boolean.TRUE.equals(unit.getIsDefault());
    }

    /**
     * Builds a priced return line.
     *
     * <p><b>Tỷ lệ hoàn:</b> {@code originalLineValue} là giá trị GỐC 100% (prorate từ dòng hóa đơn bán),
     * {@code lineRefund = originalLineValue × refundRate}. Chênh lệch giữa hai số là phần nhà thuốc GIỮ
     * LẠI — vẫn là doanh thu bình thường.</p>
     *
     * <p><b>KHÔNG tách net/thuế:</b> hộ kinh doanh tính GTGT bằng {@code doanh thu × tỷ lệ %} ở mọi nhóm,
     * không khấu trừ đầu ra/đầu vào ⇒ không có số thuế nào trên dòng trả để giảm trừ. Doanh thu tính thuế
     * của kỳ suy từ tổng các hóa đơn CÒN HIỆU LỰC, không phải từ phiếu trả.</p>
     */
    private PreparedLine preparedLineOf(Invoicedetail line, int qty, boolean restockable, BigDecimal refundRate) {
        BigDecimal originalLineValue = grossRefundOf(line, qty);
        BigDecimal gross = applyRefundRate(originalLineValue, refundRate);
        return new PreparedLine(line, qty, restockable, gross, originalLineValue);
    }

    /** {@code lineRefund = originalLineValue × rate%}, làm tròn về đồng. */
    private BigDecimal applyRefundRate(BigDecimal originalLineValue, BigDecimal refundRate) {
        if (refundRate == null || refundRate.compareTo(FULL_REFUND_RATE) >= 0) {
            return originalLineValue;
        }
        return originalLineValue.multiply(refundRate)
                .divide(FULL_REFUND_RATE, 2, RoundingMode.HALF_UP);
    }

    /** Gross (VAT-inclusive) refund of {@code qty} units, prorated from the sale line's gross subtotal. */
    private BigDecimal grossRefundOf(Invoicedetail line, int qty) {
        Integer soldQty = line.getQuantity();
        if (line.getSubtotal() != null && soldQty != null && soldQty > 0) {
            BigDecimal ratio = BigDecimal.valueOf(qty).divide(BigDecimal.valueOf(soldQty), 10, RoundingMode.HALF_UP);
            return line.getSubtotal().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal unit = line.getUnitSellPrice() != null ? line.getUnitSellPrice() : BigDecimal.ZERO;
        return unit.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ invoice read-only access

    /**
     * All detail lines of one invoice. Dùng finder CÓ SẴN của module Bán hàng thay vì
     * {@code findAll()} + lọc trong bộ nhớ: hàm này được gọi cho TỪNG hóa đơn khi dựng danh sách
     * chọn để trả, nên quét cả bảng mỗi lần là quét bảng × số hóa đơn.
     */
    private List<Invoicedetail> invoiceLinesOf(Integer invoiceId) {
        return invoicedetailRepository.findByInvoiceIdWithRelations(invoiceId);
    }

    /**
     * Returnable when: completed or still owing (chưa ký, TH1), signed (đã ký, TH2), or already partially
     * returned. A TH1 partial return moves the status to "Đã trả hàng 1 phần"; a TH2 (signed) partial
     * return keeps "Đã ký" — both stay eligible for further partial returns. "Còn nợ" must be included —
     * an invoice with an unpaid balance is exactly the case a return needs to be able to touch (BA
     * 2026-07-25: the refund should cấn trừ that very debt).
     */
    private boolean isReturnEligibleStatus(Invoice invoice) {
        return isStatus(invoice.getStatus(), INVOICE_STATUS_COMPLETED)
                || isStatus(invoice.getStatus(), INVOICE_STATUS_DEBT)
                || isStatus(invoice.getStatus(), INVOICE_STATUS_SIGNED)
                // Dữ liệu cũ (trước khi có cột returnStatus) còn mang trạng thái trả trong status —
                // vẫn cho trả tiếp phần còn lại; hóa đơn đã trả HẾT bị chặn riêng bởi isFullyReturned.
                || isStatus(invoice.getStatus(), INVOICE_STATUS_RETURNED_PARTIAL)
                || isStatus(invoice.getStatus(), INVOICE_STATUS_RETURNED_FULL);
    }

    /**
     * Hóa đơn CŨ còn mang chuỗi "Đã ký" trong {@code status}. Chỉ dùng để KHÔNG ghi đè trạng thái của dữ
     * liệu cũ — <b>không phải</b> phép kiểm "hóa đơn đã ký hay chưa": nguồn đúng của việc ký nay là
     * {@code invoice.signAt} (xem {@code InvoiceService.sign()}), và một hóa đơn đã ký theo cách mới vẫn
     * phải được cập nhật {@code status} theo công nợ bình thường. Đổi hàm này sang đọc {@code signAt} sẽ
     * làm ĐÓNG BĂNG {@code status} của mọi hóa đơn đã ký — trả hàng xong nợ về 0 mà vẫn hiện "Còn nợ".
     */
    private boolean hasLegacySignedStatus(Invoice invoice) {
        return invoice != null && isStatus(invoice.getStatus(), INVOICE_STATUS_SIGNED);
    }

    /**
     * A return target must be a sale or replacement invoice — never an adjustment invoice (it carries only
     * negative delta lines, nothing sellable to return again). (invoiceType is NOT NULL.)
     *
     * <p><strong>"Hóa đơn GTGT" cũng là hóa đơn BÁN HÀNG.</strong> {@code InvoiceService.createSaleInvoice}
     * gán loại này thay cho "Bán hàng" khi nhà thuốc đang ở Nhóm 3+ — cùng một nghiệp vụ bán, chỉ khác
     * hình thức chứng từ. Thiếu nó ở đây thì khi chuyển sang Nhóm 3, MỌI hóa đơn bán mới đều không trả
     * lại được và danh sách chọn ở màn trả hàng rỗng trơn.</p>
     */
    private boolean isNormalInvoice(Invoice invoice) {
        if (invoice == null) {
            return false;
        }
        String type = invoice.getInvoiceType();
        return type == null
                || INVOICE_TYPE_NORMAL.equalsIgnoreCase(type)
                || INVOICE_TYPE_NORMAL_LEGACY.equalsIgnoreCase(type)
                || INVOICE_TYPE_VAT.equalsIgnoreCase(type)
                || INVOICE_TYPE_REPLACEMENT.equalsIgnoreCase(type);
    }

    /**
     * <b>Đã trả hàng ⇒ bản gốc hết hiệu lực.</b> Trả một phần cũng vậy: phần hàng khách còn giữ đã được
     * chuyển sang hóa đơn THAY THẾ, nên muốn trả tiếp phải trả trên bản thay thế đó.
     *
     * <p>Đọc {@code returnCode} là đủ, không phải đi tìm hóa đơn "Thay thế" nào trỏ ngược về hóa đơn này:
     * hai cách cho cùng kết quả (hóa đơn thay thế chỉ phát hành CÙNG LÚC với việc cộng {@code returnedQty}
     * trên hóa đơn gốc) nhưng cách kia phải quét toàn bảng {@code invoice} cho MỖI hóa đơn được kiểm. Ca
     * trả HẾT + hoàn 100% không phát hành hóa đơn thay thế nào, {@code returnCode} = FULL vẫn chặn đúng.</p>
     */
    private boolean isSupersededByReturn(String returnCode) {
        return !INVOICE_RETURN_NONE.equals(returnCode);
    }

    private boolean isReturnable(Invoice invoice, int windowDays) {
        return isReturnable(invoice, windowDays, invoiceReturnCode(invoice));
    }

    /**
     * Bản nhận sẵn {@code returnCode} để người gọi tính MỘT lần rồi dùng lại — xem
     * {@link #listReturnableInvoices}, nơi con số này vừa dùng để lọc vừa dùng để hiển thị.
     */
    private boolean isReturnable(Invoice invoice, int windowDays, String returnCode) {
        if (!isNormalInvoice(invoice)) {
            return false;
        }
        if (!isReturnEligibleStatus(invoice)) {
            return false;
        }
        if (Boolean.TRUE.equals(invoice.getPrescriptionRequired())) {
            return false;
        }
        if (isSupersededByReturn(returnCode)) {
            return false;
        }
        if (isInClosedTaxPeriod(invoice)) {
            return false;
        }
        if (hasInvoiceDiscount(invoice)) {
            return false;
        }
        // KHÔNG chặn hóa đơn còn nợ: khách còn nợ vẫn được trả hàng, tiền hoàn cấn trừ thẳng vào khoản nợ
        // đó (netting — xem applyDebtOffset). Đặc tả bổ sung 27/07 mục 3, và PISMS_Xu_ly_Cong_no sheet
        // "Công nợ Khách hàng" ca 3/4/5: "phần mềm KHÔNG cần bắt người dùng thanh toán xong rồi mới xử lý
        // trả hàng". Gate cũ (bắt trả hết nợ) đã được gỡ ngày 28/07 theo đúng tài liệu này.
        return withinReturnWindow(effectiveSaleDate(invoice), windowDays);
    }

    /**
     * Hóa đơn có chiết khấu cấp hóa đơn thì KHÔNG cho trả hàng.
     *
     * <p><b>Vì sao chặn:</b> tiền hoàn được tính từ {@code invoicedetail.subtotal} — giá niêm yết, chưa
     * trừ chiết khấu — trong khi khách chỉ thật sự trả {@code total = subtotal − discount}. Trả một phần
     * là hoàn thừa đúng bằng phần chiết khấu của số hàng đó, và hóa đơn thay thế còn giữ nguyên trọn
     * khoản chiết khấu trên số hàng còn lại ⇒ khách hưởng chiết khấu hai lần.</p>
     *
     * <p>Ví dụ đo được: hóa đơn 5 hộp, subtotal 500.000 − chiết khấu 50.000 = khách trả 450.000. Trả 2/5
     * hộp ở mức hoàn 100% ⇒ hệ thống hoàn 200.000 trong khi đúng ra chỉ 180.000.</p>
     *
     * <p>Chặn ở đây là biện pháp tạm cho tới khi tiền hoàn được nhân theo hệ số
     * {@code total / subtotal}; xem ghi chú cùng chủ đề ở {@link #createReplacementInvoice}.</p>
     */
    private boolean hasInvoiceDiscount(Invoice invoice) {
        return invoice != null
                && nz(invoice.getDiscount()).compareTo(BigDecimal.ZERO) > 0;
    }

    private void assertReturnable(Invoice invoice) {
        if (!isNormalInvoice(invoice)) {
            throw new IllegalArgumentException("Chỉ trả được hóa đơn bán hàng (không phải hóa đơn điều chỉnh)");
        }
        if (!isReturnEligibleStatus(invoice)) {
            throw new IllegalArgumentException("Chỉ trả được hóa đơn đã hoàn thành");
        }
        if (Boolean.TRUE.equals(invoice.getPrescriptionRequired())) {
            throw new IllegalArgumentException("Không thể trả hóa đơn thuốc kê đơn");
        }
        String returnCode = invoiceReturnCode(invoice);
        if (INVOICE_RETURN_FULL.equals(returnCode)) {
            throw new IllegalArgumentException("Hóa đơn này đã được trả toàn bộ");
        }
        if (isSupersededByReturn(returnCode)) {
            throw new IllegalArgumentException(
                    "Hóa đơn này đã được trả hàng một phần nên không còn hiệu lực — "
                            + "vui lòng chọn hóa đơn thay thế mới nhất");
        }
        LocalDate closedThrough = lastClosedPeriodEnd();
        if (isInClosedTaxPeriod(invoice)) {
            throw new IllegalArgumentException("Hóa đơn này thuộc kỳ thuế đã chốt (đã chốt đến hết "
                    + closedThrough.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    + ") nên không trả hàng được. Doanh thu của kỳ đó đã kê khai — "
                    + "vui lòng xử lý bằng chứng từ điều chỉnh của kế toán.");
        }
        if (hasInvoiceDiscount(invoice)) {
            throw new IllegalArgumentException("Hóa đơn này có chiết khấu nên không trả hàng được — "
                    + "tiền hoàn tính theo giá niêm yết sẽ nhiều hơn số khách đã trả. "
                    + "Vui lòng liên hệ chủ nhà thuốc để xử lý riêng.");
        }
        int windowDays = getReturnWindowDays();
        if (!withinReturnWindow(effectiveSaleDate(invoice), windowDays)) {
            throw new IllegalArgumentException("Quá thời hạn trả hàng (chỉ trong "
                    + windowDays + " ngày kể từ ngày lập hóa đơn gốc)");
        }
    }

    /**
     * Hóa đơn nằm trong kỳ thuế ĐÃ CHỐT thì không cho trả hàng nữa.
     *
     * <p><b>Vì sao chặn:</b> trả hàng phát hành hóa đơn THAY THẾ mang ngày HÔM NAY, đồng thời làm bản gốc
     * hết hiệu lực. Nếu bản gốc thuộc quý đã chốt thì con số của quý đó đã kê khai và đóng băng trong
     * {@code Taxperiodsnapshot}, trong khi bản thay thế lại tính là doanh thu MỚI của quý hiện tại ⇒
     * <b>cùng một lô hàng bị khai doanh thu hai lần</b>.</p>
     *
     * <p><b>Mốc đo là snapshot THẬT, không phải {@code nextPeriodToClose()}</b>: khi chưa chốt kỳ nào,
     * hàm đó trả về quý HIỆN TẠI, nên đo theo nó sẽ coi mọi hóa đơn của các quý trước là "đã chốt" và
     * khóa sạch màn trả hàng.</p>
     */
    private boolean isInClosedTaxPeriod(Invoice invoice) {
        LocalDate closedThrough = lastClosedPeriodEnd();
        if (closedThrough == null || invoice == null || invoice.getDate() == null) {
            return false;
        }
        return !invoice.getDate().toLocalDate().isAfter(closedThrough);
    }

    /** Ngày cuối của kỳ thuế đã chốt gần nhất; {@code null} khi chưa kỳ nào được chốt. */
    private LocalDate lastClosedPeriodEnd() {
        return taxperiodsnapshotRepository.findFirstByOrderByStartDateDescIdDesc()
                .map(Taxperiodsnapshot::getEndDate)
                .orElse(null);
    }

    /**
     * Hạn trả hàng lấy từ {@code Financialsetting.returnPolicyMaxDays} (cấu hình được, không hardcode).
     * Để trống / NULL / số âm ⇒ KHÔNG giới hạn; {@code 0} là giá trị thật, nghĩa là chỉ trả trong ngày.
     * Public để màn tạo hiện đúng chính sách thay vì một con số cứng.
     */
    @Transactional(readOnly = true)
    public int getReturnWindowDays() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(Financialsetting::getReturnPolicyMaxDays)
                .filter(days -> days >= 0)
                .orElse(RETURN_WINDOW_UNLIMITED);
    }

    /**
     * Tỷ lệ hoàn MẶC ĐỊNH toàn hệ thống, từ {@code Financialsetting.returnProductOnInvoiceValueRate}.
     * Chưa cấu hình (NULL) hoặc ≤ 0 ⇒ hoàn 100%: không có chính sách giữ lại thì không được tự ý giữ tiền
     * của khách. Giá trị &gt; 100 bị kẹp về 100.
     *
     * <p>Trả về SỐ NGUYÊN phần trăm (xem {@link #resolveRefundRate}). Cột thiết lập là {@code decimal(5,2)}
     * nên vẫn có thể chứa số lẻ của dữ liệu cũ; số lẻ đó được làm tròn chứ không đẩy ra màn tạo, để ô
     * "% hoàn" không bao giờ hiện giá trị mà chính nó từ chối lúc gửi.</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal getDefaultRefundRate() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(Financialsetting::getReturnProductOnInvoiceValueRate)
                .filter(rate -> rate.signum() > 0)
                .map(rate -> rate.min(FULL_REFUND_RATE))
                .orElse(FULL_REFUND_RATE)
                .setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Có tự động cấn trừ tiền hoàn vào công nợ hóa đơn hay không —
     * {@code Financialsetting.autoOffsetDebtOnRefund}, mặc định BẬT (cột {@code DEFAULT 1}).
     */
    @Transactional(readOnly = true)
    public boolean isAutoOffsetDebt() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(Financialsetting::getAutoOffsetDebtOnRefund)
                .orElse(Boolean.TRUE);
    }

    /**
     * Tỷ lệ hoàn thực áp cho phiếu đang lập: người dùng nhập gì thì dùng nấy (0 &lt; rate ≤ 100), bỏ trống
     * thì lấy mặc định của hệ thống. Tỷ lệ nằm ở HEADER phiếu nên áp đồng loạt mọi dòng — muốn mỗi sản
     * phẩm một tỷ lệ khác nhau thì phải lập nhiều phiếu.
     *
     * <p><b>Chỉ nhận SỐ NGUYÊN phần trăm.</b> Đây là con số hai bên thỏa thuận miệng tại quầy ("hoàn
     * 80%"), không phải kết quả tính toán — phần lẻ chỉ đẻ ra số tiền lẻ khó đối chiếu. Chặn chứ không
     * làm tròn giúp: làm tròn là âm thầm đổi số tiền hoàn so với con số người lập đã gõ.</p>
     */
    private BigDecimal resolveRefundRate(BigDecimal requested) {
        if (requested == null) {
            return getDefaultRefundRate();
        }
        if (requested.signum() <= 0 || requested.compareTo(FULL_REFUND_RATE) > 0) {
            throw new IllegalArgumentException("Tỷ lệ hoàn phải lớn hơn 0 và không vượt quá 100%");
        }
        if (requested.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "Tỷ lệ hoàn phải là số nguyên phần trăm (ví dụ 80), không nhập số lẻ");
        }
        return requested.setScale(0, RoundingMode.UNNECESSARY);
    }

    /** The window as the create screen phrases it: "trong 3 ngày" / "không giới hạn thời gian". */
    @Transactional(readOnly = true)
    public String getReturnWindowLabel() {
        int days = getReturnWindowDays();
        return days == RETURN_WINDOW_UNLIMITED ? "không giới hạn thời gian" : "trong " + days + " ngày";
    }

    private boolean withinReturnWindow(LocalDateTime invoiceDate, int windowDays) {
        if (invoiceDate == null) {
            return false;
        }
        if (windowDays == RETURN_WINDOW_UNLIMITED) {
            return true;
        }
        LocalDate cutoff = LocalDate.now(VN_ZONE).minusDays(windowDays);
        return !toLocalDate(invoiceDate).isBefore(cutoff);
    }

    /**
     * The date the return window is measured from: the ROOT invoice's date, not this one's own —
     * a replacement invoice is reissued at approval time, but the customer's actual purchase (and the
     * clock the window runs on) is whenever the root sale happened. Nếu không đo từ F0 thì mỗi lần phát
     * hành hóa đơn thay thế là RESET lại đồng hồ hạn trả, khách trả vòng 2 được cộng thêm thời gian.
     */
    private LocalDateTime effectiveSaleDate(Invoice invoice) {
        return rootOf(invoice).getDate();
    }

    // ------------------------------------------------------------------ filtering / formatting

    private boolean matchesKeyword(Return ret, List<Returndetail> details, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        Invoice invoice = ret.getInvoiceID();
        Customer customer = invoice != null ? invoice.getCustomerID() : null;
        if (containsNormalized(formatCode(ret.getId()), normalizedKeyword)
                || containsNormalized(invoice != null ? invoice.getInvoicePattern() : null, normalizedKeyword)
                || containsNormalized(customer != null ? customer.getName() : null, normalizedKeyword)
                || containsNormalized(ret.getReason(), normalizedKeyword)
                || containsNormalized(getStatusName(ret), normalizedKeyword)
                || containsNormalized(ret.getReturnedBy() != null ? ret.getReturnedBy().getName() : null, normalizedKeyword)) {
            return true;
        }
        return details.stream().anyMatch(detail -> {
            Product product = detail.getProductID();
            return product != null
                    && (containsNormalized(product.getName(), normalizedKeyword)
                    || containsNormalized(product.getCode(), normalizedKeyword)
                    || containsNormalized(product.getBarcode(), normalizedKeyword));
        });
    }

    /**
     * Cùng luật với bộ lọc phía màn hình (xem {@link #searchTextOf}): số hóa đơn, tên khách, tên/mã
     * sản phẩm còn trả được — KHÔNG có số điện thoại.
     */
    private boolean matchesInvoiceKeyword(ReturnableCandidate candidate, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        return containsNormalized(searchTextOf(candidate.invoice(), candidate.lines()), normalizedKeyword);
    }

    private boolean matchesDate(Return ret, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        if (ret.getReturnDate() == null) {
            return false;
        }
        LocalDate date = toLocalDate(ret.getReturnDate());
        if (from != null && date.isBefore(from)) {
            return false;
        }
        return to == null || !date.isAfter(to);
    }

    private long countByStatus(List<Return> returns, String statusName) {
        return returns.stream().filter(ret -> isStatus(getStatusName(ret), statusName)).count();
    }

    private String getStatusName(Return ret) {
        return ret.getStatus() != null ? ret.getStatus() : "Không rõ";
    }

    private boolean isStatus(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    private String statusCssClass(String statusName) {
        if (isStatus(statusName, ReturnStatus.DEBT)) {
            return "status-debt";
        }
        if (isStatus(statusName, ReturnStatus.COMPLETED)) {
            return "status-completed";
        }
        if (isStatus(statusName, ReturnStatus.REJECTED)) {
            return "status-rejected";
        }
        if (isStatus(statusName, ReturnStatus.PENDING)) {
            return "status-pending";
        }
        if (isStatus(statusName, ReturnStatus.DRAFT)) {
            return "status-draft";
        }
        return "status-default";
    }

    private String returnTypeDisplay(String type) {
        if (type == null) {
            return "—";
        }
        return TYPE_CUSTOMER.equalsIgnoreCase(type) ? "Khách hàng" : type;
    }

    private String returnStatusDisplay(String returnStatus) {
        if (returnStatus == null || returnStatus.isBlank()) {
            return "Chưa trả";
        }
        return switch (returnStatus.toUpperCase(Locale.ROOT)) {
            case INVOICE_RETURN_PARTIAL -> "Trả một phần";
            case INVOICE_RETURN_FULL -> "Đã trả toàn bộ";
            default -> "Chưa trả";
        };
    }

    private String productName(Invoicedetail line) {
        Product product = line.getProductID();
        return product != null && product.getName() != null ? product.getName() : "Sản phẩm";
    }

    /**
     * Mã tạm chỉ để qua ràng buộc {@code NOT NULL UNIQUE} lúc INSERT (chưa biết id nên chưa dựng được
     * mã thật); lưu xong ghi lại theo id DB cấp, và không bao giờ commit ra ngoài vì cùng transaction.
     * Cách cũ {@code max(id)+1} là đọc-rồi-ghi: hai người tạo cùng lúc nhận cùng số, người sau ăn lỗi.
     */
    private String temporaryCode() {
        return "TMP-" + UUID.randomUUID();
    }

    private String formatCode(Integer id) {
        return id == null ? "TH-000000" : "TH-" + String.format("%06d", id);
    }

    /** Unique sale-invoice number — 8 chữ số, không prefix (mirrors InvoiceService). */
    private String generateInvoiceNumber() {
        long maxNumber = invoiceRepository.findAll().stream()
                .map(Invoice::getInvoiceNumber)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(number -> !number.isEmpty())
                .map(number -> number.replaceAll("\\D", ""))
                .filter(digits -> !digits.isEmpty())
                .mapToLong(Long::parseLong)
                .max()
                .orElse(0L);
        long next = maxNumber + 1;
        if (next > 99_999_999L) {
            throw new IllegalStateException("Đã hết dãy số hóa đơn 8 chữ số");
        }
        return String.format("%08d", next);
    }

    private BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private String safeStr(String value) {
        return value != null ? value : "";
    }

    /**
     * Nội dung hóa đơn điều chỉnh / thay thế theo NĐ 70/2025: ghi rõ mẫu số, ký hiệu, số, ngày của hóa
     * đơn gốc và trả toàn bộ hay một phần. HĐ đã ký → "Điều chỉnh giảm cho…", chưa ký → "Thay thế cho…".
     *
     * <p>VD: "Thay thế cho hóa đơn Mẫu số 2, ký hiệu K26MYY, số 00000001, ngày 16 tháng 07 năm 2026,
     * do người mua trả lại hàng một phần (phiếu trả TH-000002)".</p>
     */
    private String buildAdjustmentNote(Return ret, Invoice original, boolean signed) {
        String pattern = safeStr(original.getInvoicePattern());
        // invoicePattern 7 ký tự = mẫu số (1 ký tự đầu) + ký hiệu (6 ký tự còn lại).
        String formNo = pattern.isEmpty() ? "" : pattern.substring(0, 1);
        String serial = pattern.length() > 1 ? pattern.substring(1) : "";
        String scope = isFullyReturned(original) ? "toàn bộ" : "một phần";
        String verb = signed ? "Điều chỉnh giảm cho" : "Thay thế cho";
        return verb + " hóa đơn Mẫu số " + formNo + ", ký hiệu " + serial
                + ", số " + safeStr(original.getInvoiceNumber()) + ", " + formatVnDateWords(original.getDate())
                + ", do người mua trả lại hàng " + scope + " (phiếu trả " + formatCode(ret.getId()) + ")";
    }

    /** "ngày dd tháng MM năm yyyy" theo giờ VN — dùng cho nội dung hóa đơn điều chỉnh. */
    private String formatVnDateWords(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        LocalDate date = toLocalDate(dateTime);
        return String.format("ngày %02d tháng %02d năm %04d",
                date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    private Return requireReturn(Integer returnId) {
        return returnRepository.findByIdWithRelations(returnId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng"));
    }

    /** Giờ VN "hiện tại" gán lên UTC — cùng quy ước lưu với InvoiceService/purchase. */
    private Instant nowVn() {
        return LocalDateTime.now(VN_ZONE).toInstant(ZoneOffset.UTC);
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        // Đọc lại bằng UTC vì thời gian được lưu theo giờ VN gán lên UTC.
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneOffset.UTC)
                .format(instant);
    }

    /** Invoice.date is stored as a VN wall-clock LocalDateTime — format directly, no zone conversion. */
    private String formatLocalDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(dateTime);
    }

    private String formatLocalDate(LocalDate date) {
        return date == null ? "" : date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private LocalDate toLocalDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private LocalDate toLocalDate(LocalDateTime dateTime) {
        return dateTime.toLocalDate();
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
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
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

    /** A validated, priced return line ready to be persisted (no VAT split — see {@link #preparedLineOf}). */
    private record PreparedLine(Invoicedetail invoiceLine, int qty, boolean restockable,
                                BigDecimal lineRefund, BigDecimal originalLineValue) {
        BigDecimal unitSellPrice() {
            return invoiceLine.getUnitSellPrice() != null ? invoiceLine.getUnitSellPrice() : BigDecimal.ZERO;
        }

        int baseQtyRestored() {
            // Nhập lại kho = SỐ ĐƠN VỊ BÁN trả × tỉ lệ quy đổi của đơn vị đó (đơn vị cơ sở / đơn vị bán).
            // KHÔNG suy từ baseQtyDeducted/quantity: khi bán 1 đơn vị nhưng tồn phải gom FIFO qua nhiều lô,
            // màn bán hàng tách chunk và sinh 1 dòng "quantity=0" ôm phần base lẻ
            // Quy theo ratio thì trả đơn vị bán luôn nhập lại đủ, bất kể sale tách chunk thế nào.
            Productunit unit = invoiceLine.getProductUnitID();
            if (unit != null && unit.getRatio() != null && unit.getRatio().compareTo(BigDecimal.ZERO) > 0) {
                return unit.getRatio().multiply(BigDecimal.valueOf(qty)).setScale(0, RoundingMode.HALF_UP).intValue();
            }
            // Dự phòng khi thiếu ratio: suy từ baseQtyDeducted của dòng gốc (như cũ).
            Integer baseDeducted = invoiceLine.getBaseQtyDeducted();
            int quantity = invoiceLine.getQuantity() != null ? invoiceLine.getQuantity() : 0;
            if (baseDeducted == null || quantity <= 0) {
                return qty;
            }
            return baseDeducted * qty / quantity;
        }
    }
}
