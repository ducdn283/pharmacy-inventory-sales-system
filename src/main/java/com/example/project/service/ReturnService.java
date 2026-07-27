package com.example.project.service;

import com.example.project.constant.ReturnStatus;
import com.example.project.dto.request.ReturnCreateRequest;
import com.example.project.dto.request.ReturnLineRequest;
import com.example.project.dto.response.*;
import com.example.project.entity.*;
import com.example.project.repository.*;
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
 * Single service for the <em>customer</em> return feature: listing/searching, detail, creation and
 * the approve/reject workflow. Supplier returns (via {@code purchaseID}) are a later phase.
 *
 * <p>Statuses (see {@link ReturnStatus}): Nháp → Chờ duyệt → Nợ / Từ chối. There is no "Duyệt"
 * state — an approved return becomes a payable ("Nợ") because the pharmacy now owes the customer the
 * not-yet-paid refund. A Pharmacist submits to {@code Chờ duyệt}; the Owner approves to {@code Nợ}
 * (and Owner-created slips auto-approve straight to {@code Nợ}).</p>
 *
 * <p><strong>Approval changes stock</strong> (unlike the stock-adjustment slip): each restockable
 * line is returned into a brand-new batch cloned from the one it was sold from — returned goods are
 * kept in their own batch for traceability (there is no "returned" flag on {@code batch}). The
 * original invoice's {@code returnStatus} and each line's {@code returnedQty} are updated in the same
 * transaction. The cash payout itself lives on a separate Expense (phiếu chi), handled later; this
 * service only records the refund amounts on the slip.</p>
 */
@Service
public class ReturnService {

    /**
     * "No return window at all" — the meaning the BA gave {@code Financialsetting.returnPolicyMaxDays}
     * when it is left blank ("int, DEFAULT NULL (không giới hạn nếu để trống)", Logic_Thu_Chi sheet 11,
     * 2026-07-27). The policy lives entirely in the financial setting, which the Owner edits on the
     * financial-settings screen — there is no hard-coded fallback here on purpose.
     */
    private static final int RETURN_WINDOW_UNLIMITED = -1;

    // Invoice.date is stored as VN wall-clock LocalDateTime (see InvoiceService) — the adjustment
    // invoice's own date must use the same convention, not a real UTC Instant.
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Only completed sale invoices are returnable. Matched accent/case-insensitively. */
    private static final String INVOICE_STATUS_COMPLETED = "Hoàn thành";
    /** An invoice still carrying an unpaid balance (mirrors InvoiceService.STATUS_DEBT). */
    private static final String INVOICE_STATUS_DEBT = "Còn nợ";
    // A signed invoice ("Đã ký") has been pushed to the tax authority — it must NOT be edited, so a
    // return against it emits an adjustment invoice (TH2) instead of touching the original.
    private static final String INVOICE_STATUS_SIGNED = "Đã ký";
    // The sale invoice no longer has a separate returnStatus column (removed by DB) — the return
    // state is written back into invoice.status using these values, per the DB owner (2026-07-14).
    private static final String INVOICE_STATUS_RETURNED_FULL = "Đã trả hàng toàn bộ";
    private static final String INVOICE_STATUS_RETURNED_PARTIAL = "Đã trả hàng 1 phần";

    // Only sale/replacement invoices are returnable. DB invoiceType: Bán hàng/Điều chỉnh/Thay thế — a
    // return must not be opened against an adjustment invoice (the negative slip emitted by TH2, no
    // returnable lines of its own).
    private static final String INVOICE_TYPE_NORMAL = "Bán hàng";
    /** Legacy DB value before invoiceType was stored in Vietnamese. */
    private static final String INVOICE_TYPE_NORMAL_LEGACY = "normal";
    private static final String INVOICE_TYPE_ADJUSTMENT = "Điều chỉnh";
    /** Legacy DB value before invoiceType was stored in Vietnamese. */
    private static final String INVOICE_TYPE_ADJUSTMENT_LEGACY = "adjustment";
    // Emitted for a return against an UNSIGNED original (TH1) — distinct from an
    // adjustment because it fully supersedes/invalidates the original (see isInvalidatedByReplacement),
    // carrying over ALL of its remaining data (not just a negative delta) including any unpaid debt.
    private static final String INVOICE_TYPE_REPLACEMENT = "Thay thế";

    private static final String INVOICE_RETURN_NONE = "NONE";
    private static final String INVOICE_RETURN_PARTIAL = "PARTIAL";
    private static final String INVOICE_RETURN_FULL = "FULL";

    // returnType no longer describes HOW the money moves the return screen does not
    // touch cash at all) — it only says WHO the goods went back to. Supplier slips use SUPPLIER.
    private static final String TYPE_CUSTOMER = "CUSTOMER";

    // Product types (Type.sortType / Type.name) that cannot be returned. Compared
    // accent/case-insensitively against normalize(...). Medical-device "máy" carries a warranty so it
    // is handled via warranty, not return; a combo is a bundle and is not taken back.
    private static final String SORT_COMBO = "combo";
    private static final String SORT_MEDICAL_DEVICE = "thiet bi y te";
    private static final String DEVICE_MACHINE_MARK = "may";

    private final ReturnRepository returnRepository;
    private final ReturndetailRepository returndetailRepository;
    private final AccountRepository accountRepository;
    private final BatchRepository batchRepository;
    // Sales invoice / detail rows are read for the TH1 flow; for TH2 (signed invoice) we also *create*
    // an adjustment invoice row here (see createAdjustmentInvoice) — mirroring how cloneReturnBatch
    // creates a Batch — without calling into InvoiceService.
    private final InvoiceRepository invoiceRepository;
    private final InvoicedetailRepository invoicedetailRepository;
    // Read-only: the pharmacy's return policy (returnPolicyMaxDays) — see getReturnWindowDays().
    private final FinancialsettingRepository financialsettingRepository;
    // Lazily opens/reuses the acting account's shift the moment a return is actually approved
    // (becomes Nợ) — mirrors the same hook on the Invoice side (see ShiftreportService).
    private final ShiftreportService shiftreportService;

    public ReturnService(ReturnRepository returnRepository,
                         ReturndetailRepository returndetailRepository,
                         AccountRepository accountRepository,
                         BatchRepository batchRepository,
                         InvoiceRepository invoiceRepository,
                         InvoicedetailRepository invoicedetailRepository,
                         FinancialsettingRepository financialsettingRepository,
                         ShiftreportService shiftreportService) {
        this.returnRepository = returnRepository;
        this.returndetailRepository = returndetailRepository;
        this.accountRepository = accountRepository;
        this.batchRepository = batchRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoicedetailRepository = invoicedetailRepository;
        this.financialsettingRepository = financialsettingRepository;
        this.shiftreportService = shiftreportService;
    }

    // ------------------------------------------------------------------ list / search

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

        List<ReturnListItemResponse> filtered = returns.stream()
                // Exclude supplier returns (purchaseID set) — they share the table but have their own screens.
                .filter(ret -> ret.getInvoiceID() != null)
                .filter(ret -> matchesKeyword(ret, detailMap.getOrDefault(ret.getId(), List.of()), normalizedKeyword))
                .filter(ret -> matchesDate(ret, from, to))
                .filter(ret -> status == null || status.isBlank() || isStatus(getStatusName(ret), status))
                .sorted(Comparator.comparing(Return::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ret -> toListItem(ret, detailMap.getOrDefault(ret.getId(), List.of())))
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

        return invoiceRepository.findAll().stream()
                .filter(invoice -> isReturnable(invoice, windowDays))
                .filter(invoice -> matchesInvoiceKeyword(invoice, normalizedKeyword))
                .sorted(Comparator.comparing(Invoice::getDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(invoice -> new ReturnableInvoiceResponse(
                        invoice.getId(),
                        // Số hóa đơn (HD00000x) — duy nhất, để phân biệt; KHÔNG dùng invoicePattern.
                        invoice.getInvoiceNumber(),
                        formatLocalDateTime(invoice.getDate()),
                        invoice.getEmployeeID() != null ? invoice.getEmployeeID().getName() : "Không rõ",
                        invoice.getCustomerID() != null ? invoice.getCustomerID().getName() : "Khách lẻ",
                        invoice.getTotal(),
                        returnStatusDisplay(invoiceReturnCode(invoice))))
                .toList();
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
     * Whether a product may be returned at all, by its {@link Type}. Blocks combos and the
     * medical-device "máy" sub-type (warranty items); other medical devices (có hạn / không hạn) and
     * all drugs / goods are allowed. Unknown/missing type → allowed (don't over-block).
     */
    private boolean isReturnableProductType(Product product) {
        if (product == null || product.getTypeID() == null) {
            return true;
        }
        Type type = product.getTypeID();
        String sort = normalize(type.getSortType());
        String name = normalize(type.getName());
        if (SORT_COMBO.equals(sort)) {
            return false;
        }
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
     * @return the id of the created slip, for the redirect.
     */
    @Transactional
    public Integer createReturn(ReturnCreateRequest request,
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
                        + "\" không được phép trả (thiết bị y tế máy hoặc combo)");
            }
            int alreadyReturned = line.getReturnedQty() != null ? line.getReturnedQty() : 0;
            int returnable = line.getQuantity() - alreadyReturned;
            int qty = item.getReturnQty();
            if (qty > returnable) {
                throw new IllegalArgumentException("Số lượng trả của \"" + productName(line)
                        + "\" vượt quá số còn có thể trả (" + returnable + ")");
            }
            // Restockable is hard-coded by item type: only the manufacturer's default
            // packaging unit (productunit.isDefault) goes back to stock; loose units do not. No manual
            // checkbox — the client value is ignored. (Combo / medical-device "máy" / prescription
            // invoices are already blocked from return upstream.)
            boolean restockable = isRestockableUnit(line);
            prepared.put(line.getId(), preparedLineOf(line, qty, restockable));
        }

        if (prepared.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một dòng hàng cần trả");
        }

        BigDecimal totalRefund = prepared.values().stream()
                .map(PreparedLine::lineRefund)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalVATRefund = prepared.values().stream()
                .map(PreparedLine::vatAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String status = asDraft ? ReturnStatus.DRAFT : (isOwner ? ReturnStatus.DEBT : ReturnStatus.PENDING);
        boolean approvedNow = ReturnStatus.DEBT.equals(status);

        Return ret = new Return();
        ret.setReturnCode(generateCode());
        ret.setInvoiceID(invoice);
        ret.setPurchaseID(null);
        ret.setReturnedBy(creator);
        ret.setReturnDate(nowVn());
        ret.setReturnType(TYPE_CUSTOMER);
        // Phiếu trả CHỈ TÍNH tiền, không chi tiền: cách chi (tiền mặt / chuyển khoản) là dữ liệu của
        // phiếu chi bên Kế toán. 3 cột refundCash/refundBanking/refundCredit đã bị bỏ khỏi
        // bảng `return` — phiếu trả chỉ còn lưu tổng phải hoàn (totalRefund).
        ret.setTotalRefund(totalRefund);
        ret.setTotalVATRefund(totalVATRefund);
        // ⚠️ TODO — luôn hoàn 100%, CHƯA áp Financialsetting.returnProductOnInvoiceValueRate (local 80%).
        // Rà lại 2026-07-27 với bộ tài liệu BA mới (Huong_dan_tinh_thue sheet 12 §5A + sheet 09 §4,
        // Logic_Thu_Chi sheet 07/10): phần lớn thắc mắc đã có lời giải, ghi lại để khỏi phân tích lại:
        //   • Phần khách KHÔNG được hoàn VẪN là doanh thu chịu thuế bình thường ⇒ không cần Income/dòng
        //     riêng nào cho nó, không có tiền "biến mất khỏi sổ" như từng lo.
        //   • HĐ thay thế mang giá trị = phần khách thực giữ (ví dụ BA: mua 500k, hoàn 400k ⇒ HĐ 100k)
        //     — đúng bằng `gốc − refund` mà createReplacementInvoice đang tính, KHÔNG phải sửa.
        //   • HĐ điều chỉnh ghi âm đúng phần chênh lệch (= số tiền hoàn) — cũng đã đúng.
        //   • Số tiền hoàn và GIÁ VỐN là 2 luồng độc lập: batch mới luôn theo giá vốn gốc, COGS đảo đủ
        //     100% theo số lượng vật lý, KHÔNG nhân tỷ lệ hoàn (tài liệu cảnh báo riêng bẫy này).
        // Còn vướng đúng 2 chỗ nên chưa bật: (1) chưa có ô nhập % theo từng phiếu (tài liệu nói nhà thuốc
        // tự chỉnh mỗi lần, appliedRefundRate là bắt buộc chứ không phải tùy chọn); (2) trả HẾT hàng mà chỉ
        // hoàn 80% thì HĐ thay thế phải mang 20% giữ lại, nhưng lúc đó không còn dòng hàng nào —
        // createReplacementInvoice hiện return sớm, cần BA chốt dòng chi tiết ghi thế nào.
        // Chi tiết đầy đủ: phan-tich-tai-lieu/quyet-dinh-va-mau-thuan.md (TREO #2).
        ret.setAppliedRefundRate(new BigDecimal("100.00"));
        // Khách phải trả hết nợ hóa đơn mới được trả hàng (assertReturnable) → không còn gì để cấn trừ.
        ret.setOffsetDebtAmount(BigDecimal.ZERO);
        ret.setReason(request.getReason().trim());
        ret.setNote(trimToNull(request.getNote()));
        ret.setStatus(status);
        if (approvedNow) {
            ret.setApprovedAt(nowVn());
        }

        Return savedReturn = returnRepository.save(ret);

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
            // originalLineValue = giá trị GỐC 100% của dòng trả (trước khi áp tỷ lệ hoàn).
            // hoàn 100% nên = lineRefund; khi bật returnProductOnInvoiceValueRate (<100%)
            // thì lineRefund = originalLineValue × rate còn field này giữ mốc gốc để đối chiếu.
            detail.setOriginalLineValue(line.lineRefund());
            detail.setVatRate(line.vatRate());
            detail.setPreTaxAmount(line.preTaxAmount());
            detail.setVatAmount(line.vatAmount());
            detail.setRestockable(line.restockable());
            details.add(returndetailRepository.save(detail));
        }

        if (approvedNow) {
            applyReturnEffect(savedReturn, details);
        }

        return savedReturn.getId();
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
    }

    private void markApproved(Return ret) {
        ret.setStatus(ReturnStatus.DEBT);
        ret.setApprovedAt(nowVn());
        returnRepository.save(ret);
        applyReturnEffect(ret, returndetailRepository.findByReturnIdWithRelations(ret.getId()));
    }

    /**
     * Restores stock for an approved return: each restockable line goes into a fresh batch cloned from the
     * one it was sold from, and the original invoice line's {@code returnedQty} is bumped. Then, depending
     * on whether the original invoice is signed:
     * <ul>
     *   <li><b>TH1 (chưa ký):</b> the original is fully superseded — a REPLACEMENT invoice ("Thay thế") is
     *       emitted carrying its entire remaining data, <em>including any outstanding debt</em>, which moves
     *       onto the replacement while the original is zeroed (see {@link #createReplacementInvoice}).</li>
     *   <li><b>TH2 (đã ký):</b> the original stays valid in parallel — an ADJUSTMENT invoice ("Điều chỉnh")
     *       with negative lines is emitted (see {@link #createAdjustmentInvoice}); the signed original's
     *       {@code subtotal/total/paidBy*} are left untouched (already pushed to the tax authority).</li>
     * </ul>
     *
     * <p><strong>No cash moves here</strong>: re-issuing/adjusting invoices and carrying
     * their debt across is invoice bookkeeping, but the register (quỹ) is never touched — the slip only
     * computes and stores {@code totalRefund}. Paying the customer back is the Expense module's job — the
     * Expense slip is what records the actual payout and its cash/banking split.</p>
     *
     * <p>Also lazily opens/reuses the <strong>creator's</strong> (not the approver's) shift report right
     * here — the physical counter transaction with the customer happens when the slip is created, even
     * if an Owner reviews/approves it asynchronously later from elsewhere. Using the approver's account
     * here would misattach the return to whichever shift the Owner happens to be in at review time (or
     * spawn a stray extra shift for the Owner), instead of the shift the sale itself is already sitting
     * in. A return is a real transaction the moment it is approved ("phát sinh giao dịch là tạo báo cáo ca").</p>
     */
    private void applyReturnEffect(Return ret, List<Returndetail> details) {
        Shiftreport shift = shiftreportService.ensureOpenShiftFor(ret.getReturnedBy().getId());
        ret.setShiftReportID(shift);

        Invoice original = ret.getInvoiceID();
        boolean signed = isSigned(original);
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

        if (signed) {
            createAdjustmentInvoice(ret, original, details);
        } else {
            createReplacementInvoice(ret, original, details);
            // HĐ chưa ký (chưa gửi thuế) → cập nhật trạng thái trả của chính HĐ gốc ("Đã trả hàng 1
            // phần/toàn bộ"). HĐ đã ký giữ nguyên "Đã ký" (theo dõi phần đã trả qua returnedQty từng dòng).
            updateInvoiceReturnStatus(original);
        }

        returnRepository.save(ret);
    }

    /**
     * Emits an ADJUSTMENT invoice for a return against a SIGNED original (TH2 — HĐ đã ký, đã gửi thuế): a
     * negative-line delta that reduces the customer's original invoice without ever touching or re-issuing
     * it (legally frozen once signed — {@code subtotal/total/paidBy*} are left exactly as they were). It
     * does NOT deduct stock (the returned goods were already restocked into a fresh batch in
     * {@link #applyReturnEffect}). Linked both ways via {@code originalInvoiceID} + {@code returnID}.
     *
     * <p>Neither invoice's debt moves here. Bù trừ công nợ was replaced by a hard gate — a customer still
     * owing on an invoice cannot return against it at all ({@link #hasOutstandingDebt}) — so the original's
     * {@code debtAmount} is already 0 by the time we get here, and the adjustment carries no debt of its
     * own. Paying the customer back is the Expense module's job.</p>
     */
    private void createAdjustmentInvoice(Return ret, Invoice original, List<Returndetail> details) {
        BigDecimal refund = nz(ret.getTotalRefund());
        BigDecimal vatRefund = nz(ret.getTotalVATRefund());

        Invoice adj = new Invoice();
        // Cùng ký hiệu (mẫu số / serie) với hóa đơn gốc; số hóa đơn mới, duy nhất.
        adj.setInvoicePattern(original.getInvoicePattern());
        adj.setInvoiceNumber(generateInvoiceNumber());
        adj.setDate(LocalDateTime.now(VN_ZONE));
        adj.setEmployeeID(ret.getReturnedBy());
        adj.setCustomerID(original.getCustomerID());
        adj.setInvoiceType(INVOICE_TYPE_ADJUSTMENT);
        adj.setOriginalInvoiceID(original);
        adj.setRootInvoiceID(rootOf(original));
        adj.setReturnID(ret);
        adj.setPrescriptionRequired(false);
        // Phát hành ở trạng thái "Hoàn thành" (chờ ký) — Kế toán/Owner review rồi ký đẩy thuế như hóa đơn thường.
        adj.setStatus(INVOICE_STATUS_COMPLETED);
        adj.setDiscount(BigDecimal.ZERO);
        adj.setSubtotal(refund.negate());
        adj.setTotal(refund.negate());
        adj.setTotalVATOutput(vatRefund.negate());
        adj.setPaidByCash(BigDecimal.ZERO);
        adj.setPaidByBanking(BigDecimal.ZERO);
        adj.setDebtAmount(BigDecimal.ZERO);
        adj.setNote(buildAdjustmentNote(ret, original, true));

        Invoice savedAdj = invoiceRepository.save(adj);
        saveNegativeLines(savedAdj, details);
    }

    /** The negative delta lines of an adjustment invoice — one per returned line, quantities/amounts negated. */
    private void saveNegativeLines(Invoice savedAdj, List<Returndetail> details) {
        for (Returndetail detail : details) {
            Productunit unit = detail.getProductUnitID();
            int qty = detail.getReturnQty() != null ? detail.getReturnQty() : 0;
            int baseQty = detail.getBaseQtyRestored() != null ? detail.getBaseQtyRestored() : 0;

            Invoicedetail line = new Invoicedetail();
            line.setInvoiceID(savedAdj);
            line.setProductID(detail.getProductID());
            line.setProductUnitID(unit);
            line.setBatchID(detail.getBatchID());
            line.setQuantity(-qty);
            line.setUnitName(unit != null && unit.getUnitName() != null ? truncate(unit.getUnitName(), 20) : "");
            line.setBaseQtyDeducted(-baseQty);
            line.setUnitSellPrice(nz(detail.getUnitSellPrice()));
            line.setSubtotal(nz(detail.getLineRefund()).negate());
            line.setReturnedQty(0);
            line.setVatRate(detail.getVatRate());
            line.setPreTaxAmount(nz(detail.getPreTaxAmount()).negate());
            line.setVatAmount(nz(detail.getVatAmount()).negate());
            invoicedetailRepository.save(line);
        }
    }

    /**
     * Emits a REPLACEMENT invoice for a return against an UNSIGNED original (TH1 — HĐ chưa ký, chưa gửi
     * thuế). The original is invalidated outright ("hóa đơn gốc đã vô hiệu hoàn toàn"), so
     * — unlike the adjustment path — this clones the original's FULL remaining state (every still-
     * unreturned line, not just a negative delta) into a brand-new invoice that becomes the current, live
     * version of the sale. The original's debt is wiped to 0 and the (refund-reduced) remainder moves onto
     * the replacement's own {@code debtAmount}; later corrections must target the replacement, never the
     * original (enforced by {@link #isInvalidatedByReplacement}, which every return-eligibility check
     * consults).
     */
    private void createReplacementInvoice(Return ret, Invoice original, List<Returndetail> details) {
        // Khách trả HẾT phần còn lại → không còn gì để phát hành lại. Phát hành một hóa đơn "Thay thế"
        // rỗng (0đ, không dòng nào) chỉ tạo rác trong danh sách hóa đơn và lọt vào danh sách chọn để trả
        // tiếp (không dòng nào ⇒ không bị coi là đã trả hết). Bỏ hẳn — hóa đơn gốc sẽ được
        // updateInvoiceReturnStatus đánh dấu "Đã trả hàng toàn bộ" là đủ.
        List<Invoicedetail> remainingLines = invoiceLinesOf(original.getId()).stream()
                .filter(line -> line.getQuantity() - (line.getReturnedQty() != null ? line.getReturnedQty() : 0) > 0)
                .toList();
        if (remainingLines.isEmpty()) {
            return;
        }

        BigDecimal refund = nz(ret.getTotalRefund());
        BigDecimal vatRefund = nz(ret.getTotalVATRefund());

        BigDecimal newSubtotal = nz(original.getSubtotal()).subtract(refund).max(BigDecimal.ZERO);
        BigDecimal newTotal = nz(original.getTotal()).subtract(refund).max(BigDecimal.ZERO);
        BigDecimal newVatOutput = nz(original.getTotalVATOutput()).subtract(vatRefund).max(BigDecimal.ZERO);
        // Bản thay thế kế thừa TOÀN BỘ trạng thái còn lại của hóa đơn gốc — gồm cả công nợ: nợ chuyển sang
        // hóa đơn mới, hóa đơn gốc bị vô hiệu nên xóa nợ về 0. Đây là nghiệp vụ tạo hóa đơn mới từ hóa đơn
        // cũ (không phải dòng tiền của phiếu trả), nên chuyển NGUYÊN nợ, không cấn trừ tiền hoàn.
        BigDecimal newDebt = nz(original.getDebtAmount());

        original.setDebtAmount(BigDecimal.ZERO);
        invoiceRepository.save(original);

        Invoice repl = new Invoice();
        repl.setInvoicePattern(original.getInvoicePattern());
        repl.setInvoiceNumber(generateInvoiceNumber());
        repl.setDate(LocalDateTime.now(VN_ZONE));
        // Toàn bộ data khác của hóa đơn gốc (nhân viên bán, khách hàng, đơn thuốc) được giữ nguyên —
        // đây là bản thay thế của CHÍNH giao dịch đó, không phải giao dịch của người lập phiếu trả.
        repl.setEmployeeID(original.getEmployeeID());
        repl.setCustomerID(original.getCustomerID());
        repl.setInvoiceType(INVOICE_TYPE_REPLACEMENT);
        repl.setOriginalInvoiceID(original);
        repl.setRootInvoiceID(rootOf(original));
        repl.setReturnID(ret);
        repl.setPrescriptionRequired(original.getPrescriptionRequired());
        repl.setPrescriptionCode(original.getPrescriptionCode());
        repl.setDiscount(nz(original.getDiscount()));
        repl.setSubtotal(newSubtotal);
        repl.setTotal(newTotal);
        repl.setTotalVATOutput(newVatOutput);
        repl.setPaidByCash(nz(original.getPaidByCash()));
        repl.setPaidByBanking(nz(original.getPaidByBanking()));
        repl.setDebtAmount(newDebt);
        repl.setStatus(newDebt.compareTo(BigDecimal.ZERO) > 0 ? INVOICE_STATUS_DEBT : INVOICE_STATUS_COMPLETED);
        repl.setNote(buildAdjustmentNote(ret, original, false));

        Invoice savedRepl = invoiceRepository.save(repl);

        Map<Integer, Returndetail> returnedByLine = details.stream()
                .collect(Collectors.toMap(d -> d.getInvoiceDetailID().getId(), d -> d, (a, b) -> a));

        for (Invoicedetail line : remainingLines) {
            int returned = line.getReturnedQty() != null ? line.getReturnedQty() : 0;
            int remainingQty = line.getQuantity() - returned;
            Returndetail matched = returnedByLine.get(line.getId());

            Invoicedetail clone = new Invoicedetail();
            clone.setInvoiceID(savedRepl);
            clone.setProductID(line.getProductID());
            clone.setProductUnitID(line.getProductUnitID());
            clone.setBatchID(line.getBatchID());
            clone.setQuantity(remainingQty);
            clone.setUnitName(line.getUnitName());
            clone.setBaseQtyDeducted(line.getBaseQtyDeducted()
                    - (matched != null && matched.getBaseQtyRestored() != null ? matched.getBaseQtyRestored() : 0));
            clone.setUnitSellPrice(line.getUnitSellPrice());
            clone.setSubtotal(nz(line.getSubtotal())
                    .subtract(matched != null ? nz(matched.getLineRefund()) : BigDecimal.ZERO)
                    .max(BigDecimal.ZERO));
            clone.setReturnedQty(0);
            clone.setVatRate(line.getVatRate());
            clone.setPreTaxAmount(nz(line.getPreTaxAmount())
                    .subtract(matched != null ? nz(matched.getPreTaxAmount()) : BigDecimal.ZERO)
                    .max(BigDecimal.ZERO));
            clone.setVatAmount(nz(line.getVatAmount())
                    .subtract(matched != null ? nz(matched.getVatAmount()) : BigDecimal.ZERO)
                    .max(BigDecimal.ZERO));
            invoicedetailRepository.save(clone);
        }
    }

    /** Walks to the very first invoice in a replace/adjust chain — itself if it has no root of its own. */
    private Invoice rootOf(Invoice invoice) {
        return invoice.getRootInvoiceID() != null ? invoice.getRootInvoiceID() : invoice;
    }

    /**
     * Writes the return state into the sale invoice's {@code status} — the dedicated {@code returnStatus}
     * column was removed by the DB owner (2026-07-14), who asked to reuse {@code invoice.status}
     * ("Đã trả hàng 1 phần" / "Đã trả hàng toàn bộ"). Left untouched when nothing has been returned.
     */
    private void updateInvoiceReturnStatus(Invoice invoice) {
        if (invoice == null) {
            return;
        }
        String code = invoiceReturnCode(invoice);
        if (INVOICE_RETURN_FULL.equals(code)) {
            invoice.setStatus(INVOICE_STATUS_RETURNED_FULL);
            invoiceRepository.save(invoice);
        } else if (INVOICE_RETURN_PARTIAL.equals(code)) {
            invoice.setStatus(INVOICE_STATUS_RETURNED_PARTIAL);
            invoiceRepository.save(invoice);
        }
    }

    private Batch cloneReturnBatch(Batch original, int quantity, Return ret) {
        Batch batch = new Batch();
        Integer origBatchId = original != null ? original.getId() : null;
        // Mã lô hàng trả RT-{id phiếu}-L{id lô gốc}, vd RT-000004-L4:
        //   • RT      = "Return" (lô hàng khách trả lại);
        //   • 000004  = id phiếu trả (khớp mã hiển thị TH-000004);
        //   • L4      = lô gốc mà hàng được bán ra (batchID=4) → truy ngược nguồn gốc ngay trên mã lô.
        batch.setBatchCode(truncate("RT-" + String.format("%06d", ret.getId())
                + "-L" + (origBatchId != null ? origBatchId : 0), 50));
        batch.setBatchName(truncate("Hàng trả " + (original != null && original.getBatchName() != null
                ? original.getBatchName() : ""), 50));
        batch.setProductID(original != null ? original.getProductID() : null);
        batch.setPurchaseDetailID(null);
        batch.setStorageQuantity(quantity);
        batch.setImportUnitID(original != null ? original.getImportUnitID() : null);
        batch.setImportQtyInUnit(original != null ? original.getImportQtyInUnit() : null);
        batch.setImportPrice(original != null && original.getImportPrice() != null
                ? original.getImportPrice() : BigDecimal.ZERO);
        batch.setImportPricePerBase(original != null && original.getImportPricePerBase() != null
                ? original.getImportPricePerBase() : BigDecimal.ZERO);
        batch.setImportDate(nowVn());
        batch.setProductionDate(original != null ? original.getProductionDate() : null);
        batch.setExpirationDate(original != null ? original.getExpirationDate() : null);
        batch.setLotNumber(original != null ? original.getLotNumber() : null);
        batch.setStatus(true);
        batch.setNote("Hàng trả từ phiếu " + formatCode(ret.getId()));
        return batchRepository.save(batch);
    }

    /**
     * NONE / PARTIAL / FULL derived from how much of the invoice's lines have been returned. Replaces
     * the removed {@code invoice.returnStatus} column (the sale {@code status} is left untouched).
     */
    private String invoiceReturnCode(Invoice invoice) {
        List<Invoicedetail> lines = invoiceLinesOf(invoice.getId());
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

        String statusName = getStatusName(ret);
        Invoice invoice = ret.getInvoiceID();
        Customer customer = invoice != null ? invoice.getCustomerID() : null;

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
                details.stream()
                        .map(Returndetail::getPreTaxAmount)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                ret.getTotalVATRefund(),
                items);
    }

    // ------------------------------------------------------------------ mapping helpers

    private ReturnListItemResponse toListItem(Return ret, List<Returndetail> details) {
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
                ret.getReturnType(),
                returnTypeDisplay(ret.getReturnType()),
                statusName,
                statusCssClass(statusName));
    }

    private ReturnDetailItemResponse toDetailItem(Returndetail detail) {
        Product product = detail.getProductID();
        Productunit unit = detail.getProductUnitID();
        Batch batch = detail.getBatchID();
        boolean restockable = Boolean.TRUE.equals(detail.getRestockable());

        return new ReturnDetailItemResponse(
                product != null ? product.getProductID() : null,
                product != null ? product.getName() : "Không rõ",
                batch != null ? batch.getLotNumber() : "",
                batch != null ? formatLocalDate(batch.getExpirationDate()) : "",
                unit != null ? unit.getUnitName() : "",
                detail.getReturnQty(),
                detail.getUnitSellPrice(),
                detail.getLineRefund(),
                detail.getVatRate(),
                detail.getPreTaxAmount(),
                detail.getVatAmount(),
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
                batch != null ? batch.getLotNumber() : "",
                batch != null ? formatLocalDate(batch.getExpirationDate()) : "",
                line.getUnitName(),
                line.getQuantity(),
                already,
                line.getQuantity() - already,
                line.getUnitSellPrice(),
                line.getVatRate(),
                isRestockableUnit(line));
    }

    /**
     * Restockable only when the sold unit is the manufacturer's default packaging unit
     * ({@code productunit.isDefault}). Loose units (isDefault=false) cannot go back to
     * stock; combo / medical-device "máy" / prescription invoices are already blocked from return
     * upstream. This is hard-coded (no manual checkbox).
     */
    private boolean isRestockableUnit(Invoicedetail line) {
        Productunit unit = line.getProductUnitID();
        return unit != null && Boolean.TRUE.equals(unit.getIsDefault());
    }

    /**
     * Builds a priced return line. The refund money ({@code lineRefund}) is the gross (VAT-inclusive)
     * value of the returned quantity, prorated from the original sale line.
     *
     * <p><b>VAT mirrors the original invoice line</b>: the return/adjustment invoice is the
     * negative counterpart of the original, so it must reverse the exact VAT that was snapshotted onto the sale
     * line (F-12), regardless of the current revenue group. If the sale line carried {@code vatRate > 0} the
     * refund is split into net/VAT (vatAmount feeds {@code Return.totalVATRefund}); if the line had no VAT the
     * whole refund is a revenue reduction (vatRate/vatAmount = 0). Gating again on the household's current
     * revenue group would desync the credit from the invoice it reverses.</p>
     */
    private PreparedLine preparedLineOf(Invoicedetail line, int qty, boolean restockable) {
        BigDecimal saleRate = line.getVatRate() != null ? line.getVatRate() : BigDecimal.ZERO;
        BigDecimal gross = grossRefundOf(line, qty);

        BigDecimal rate;
        BigDecimal preTax;
        BigDecimal vat;
        if (saleRate.compareTo(BigDecimal.ZERO) > 0) {
            rate = saleRate;
            BigDecimal divisor = BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
            preTax = gross.divide(divisor, 2, RoundingMode.HALF_UP);
            vat = gross.subtract(preTax);
        } else {
            rate = BigDecimal.ZERO;
            preTax = gross;
            vat = BigDecimal.ZERO;
        }
        return new PreparedLine(line, qty, restockable, rate, preTax, vat, gross);
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

    /** All detail lines of one invoice (read-only over the Invoice module — no custom repo methods). */
    private List<Invoicedetail> invoiceLinesOf(Integer invoiceId) {
        return invoicedetailRepository.findAll().stream()
                .filter(line -> line.getInvoiceID() != null && invoiceId.equals(line.getInvoiceID().getId()))
                .toList();
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
                || isStatus(invoice.getStatus(), INVOICE_STATUS_RETURNED_PARTIAL);
    }

    /** Signed ("Đã ký") = pushed to tax → return must emit an adjustment invoice (TH2), not edit the original. */
    private boolean isSigned(Invoice invoice) {
        return invoice != null && isStatus(invoice.getStatus(), INVOICE_STATUS_SIGNED);
    }

    /**
     * A return target must be a sale or replacement invoice — never an adjustment invoice (it carries only
     * negative delta lines, nothing sellable to return again). (invoiceType is NOT NULL.)
     */
    private boolean isNormalInvoice(Invoice invoice) {
        if (invoice == null) {
            return false;
        }
        String type = invoice.getInvoiceType();
        return type == null
                || INVOICE_TYPE_NORMAL.equalsIgnoreCase(type)
                || INVOICE_TYPE_NORMAL_LEGACY.equalsIgnoreCase(type)
                || INVOICE_TYPE_REPLACEMENT.equalsIgnoreCase(type);
    }

    /**
     * True once a "Thay thế" (replacement) child invoice exists pointing back at this one — per BA
     * 2026-07-25, a replaced invoice is fully invalidated and must never be picked again as a return
     * target; later corrections land on the latest replacement instead. An "Điều chỉnh" (adjustment)
     * child does NOT invalidate its original — the two remain valid in parallel.
     */
    private boolean isInvalidatedByReplacement(Invoice invoice) {
        if (invoice == null || invoice.getId() == null) {
            return false;
        }
        return invoiceRepository.findAll().stream().anyMatch(candidate ->
                INVOICE_TYPE_REPLACEMENT.equalsIgnoreCase(candidate.getInvoiceType())
                        && candidate.getOriginalInvoiceID() != null
                        && invoice.getId().equals(candidate.getOriginalInvoiceID().getId()));
    }

    private boolean isReturnable(Invoice invoice, int windowDays) {
        if (!isNormalInvoice(invoice)) {
            return false;
        }
        if (!isReturnEligibleStatus(invoice)) {
            return false;
        }
        if (Boolean.TRUE.equals(invoice.getPrescriptionRequired())) {
            return false;
        }
        if (isFullyReturned(invoice)) {
            return false;
        }
        if (isInvalidatedByReplacement(invoice)) {
            return false;
        }
        if (hasOutstandingDebt(invoice)) {
            return false;
        }
        return withinReturnWindow(effectiveSaleDate(invoice), windowDays);
    }

    /**
     * BA 2026-07-26: an invoice the customer has not paid off cannot be returned at all. Settling the
     * debt first keeps the return screen out of the money entirely — there is nothing left to cấn trừ,
     * so the refund is a plain payable handled by the Expense module.
     *
     * <p><strong>Known conflict with the BA docs, deferred on purpose (2026-07-27).</strong> The
     * documents issued that afternoon (PISMS_Xu_ly_Cong_no sheet "Công nợ Khách hàng" ca 3/4/5;
     * Huong_dan_tinh_thue sheet 04, "netting" section) require the OPPOSITE: a return must be allowed
     * while the invoice is still unpaid, offsetting {@code MIN(totalRefund, debtAmount)} against the
     * debt and paying out only the remainder — *"phần mềm KHÔNG cần bắt người dùng thanh toán xong rồi
     * mới xử lý trả hàng"*. {@code Financialsetting.autoOffsetDebtOnRefund} (default true) exists for
     * exactly that and is deliberately NOT read here yet. Owner's call: keep the simple gate for now,
     * do netting in a later phase. Dropping this gate and wiring the setting must happen together —
     * see phan-tich-tai-lieu/quyet-dinh-va-mau-thuan.md (TREO #1) for the full spec.</p>
     */
    private boolean hasOutstandingDebt(Invoice invoice) {
        return nz(invoice.getDebtAmount()).signum() > 0;
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
        if (isFullyReturned(invoice)) {
            throw new IllegalArgumentException("Hóa đơn này đã được trả toàn bộ");
        }
        if (isInvalidatedByReplacement(invoice)) {
            throw new IllegalArgumentException(
                    "Hóa đơn này đã được thay thế bởi hóa đơn khác — vui lòng chọn hóa đơn thay thế mới nhất");
        }
        if (hasOutstandingDebt(invoice)) {
            throw new IllegalArgumentException(
                    "Hóa đơn này khách còn nợ — khách phải thanh toán hết mới được trả hàng");
        }
        int windowDays = getReturnWindowDays();
        if (!withinReturnWindow(effectiveSaleDate(invoice), windowDays)) {
            throw new IllegalArgumentException("Quá thời hạn trả hàng (chỉ trong "
                    + windowDays + " ngày kể từ ngày lập hóa đơn gốc)");
        }
    }

    /**
     * The configured return window, from {@code Financialsetting.returnPolicyMaxDays} — the policy the
     * pharmacy sets on the financial-settings screen, not a constant. Blank (NULL, or no setting row at
     * all) means NO limit ({@link #RETURN_WINDOW_UNLIMITED}), per the BA's definition of the column; a
     * negative stored value is read the same way. {@code 0} is a real value and means "same day only".
     *
     * <p>Public so the create screen can state the real policy instead of a hard-coded number.</p>
     */
    @Transactional(readOnly = true)
    public int getReturnWindowDays() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(Financialsetting::getReturnPolicyMaxDays)
                .filter(days -> days >= 0)
                .orElse(RETURN_WINDOW_UNLIMITED);
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
     * clock the window runs on) is whenever the root sale happened.
     */
    private LocalDateTime effectiveSaleDate(Invoice invoice) {
        Invoice root = invoice.getRootInvoiceID();
        return root != null ? root.getDate() : invoice.getDate();
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

    private boolean matchesInvoiceKeyword(Invoice invoice, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        Customer customer = invoice.getCustomerID();
        return containsNormalized(invoice.getInvoiceNumber(), normalizedKeyword)
                || containsNormalized(customer != null ? customer.getName() : null, normalizedKeyword)
                || containsNormalized(customer != null ? customer.getPhoneNumber() : null, normalizedKeyword);
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

    private String generateCode() {
        int nextId = returnRepository.findAll().stream()
                .map(Return::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        return "TH-" + String.format("%06d", nextId);
    }

    private String formatCode(Integer id) {
        return id == null ? "TH-000000" : "TH-" + String.format("%06d", id);
    }

    /** Unique sale-invoice number for the adjustment invoice — {@code HD} + 6-digit next id (mirrors InvoiceService). */
    private String generateInvoiceNumber() {
        int nextId = invoiceRepository.findAll().stream()
                .map(Invoice::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        return "HD" + String.format("%06d", nextId);
    }

    private BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private String safeStr(String value) {
        return value != null ? value : "";
    }

    /**
     * Nội dung hóa đơn điều chỉnh / thay thế theo NĐ 70/2025 (người mua trả lại hàng). Ghi rõ mẫu số + ký hiệu
     * + số + ngày của hóa đơn gốc, và trả toàn bộ hay một phần. Cụm động từ đầu câu khác nhau theo tình huống:
     * <ul>
     *   <li>{@code signed=true} (HĐ đã ký) → "Điều chỉnh giảm cho...";</li>
     *   <li>{@code signed=false} (HĐ chưa ký) → "Thay thế cho...".</li>
     * </ul>
     * VD: "Thay thế cho hóa đơn Mẫu số 2, ký hiệu K26MYY, số HD000001, ngày 16 tháng 07 năm 2026,
     * do người mua trả lại hàng một phần (phiếu trả TH-000002)".
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

    /** A validated, priced return line (with its VAT split) ready to be persisted. */
    private record PreparedLine(Invoicedetail invoiceLine, int qty, boolean restockable,
                                BigDecimal vatRate, BigDecimal preTaxAmount, BigDecimal vatAmount,
                                BigDecimal lineRefund) {
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
