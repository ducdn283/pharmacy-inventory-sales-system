package com.example.project.service;

import com.example.project.constant.ReturnPurchaseStatus;
import com.example.project.dto.request.ReturnPurchaseCreateRequest;
import com.example.project.dto.request.ReturnPurchaseLineRequest;
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
 * Supplier-return feature: returning goods back to a supplier against an original purchase invoice.
 * It shares the {@code return} / {@code returndetail} tables with the customer return, distinguished
 * by {@code purchaseID != null} (and {@code invoiceID == null}).
 *
 * <p>Owner-only (the Pharmacist has no rights on the supplier side). The Owner either saves a draft
 * or approves; there is no "Chờ duyệt" hand-off. Statuses: {@link ReturnPurchaseStatus} —
 * Nháp → Đã duyệt / Từ chối.</p>
 *
 * <p><strong>Approval deducts stock</strong> (goods physically leave for the supplier): each line's
 * quantity is removed from the batches that were imported on the original purchase line
 * ({@code batch.purchaseDetailID}), FIFO by expiry, blocking negative stock. The purchase invoice's
 * {@code returnStatus} / {@code returnQty} are recomputed in the same transaction, and the value returned
 * is netted against whatever the pharmacy still owes on that purchase ({@code PurchaseInvoice.paid} goes
 * up by {@code offsetDebtAmount} — see {@code applyDebtOffset}). Only the remainder is real money the
 * supplier still has to hand back, which the Income module collects.</p>
 *
 * <p>Per-line "already returned" is derived on the fly from {@code returndetail} (there is no
 * {@code returnedQty} column on {@code purchasedetail}); see
 * {@link ReturndetailRepository#sumReturnedQtyByPurchaseDetail}.</p>
 */
@Service
public class ReturnPurchaseService {

    /** Only received purchases can be returned; a Nháp (draft) purchase has no stock yet. */
    private static final String PURCHASE_STATUS_DRAFT = "Nháp";

    /**
     * Tỷ lệ hoàn đầy đủ. Dùng khi {@code Financialsetting.returnProductOnInvoiceValueRate} chưa đặt —
     * mặc định coi như NCC hoàn 100% giá trị nhập.
     */
    private static final BigDecimal FULL_REFUND_RATE = new BigDecimal("100.00");

    private static final String PURCHASE_RETURN_NONE = "NONE";
    private static final String PURCHASE_RETURN_PARTIAL = "PARTIAL";
    private static final String PURCHASE_RETURN_FULL = "FULL";

    // returnType no longer describes HOW the money comes back (the return screen does
    // not touch cash at all) — it only says WHO the goods went back to. Customer slips use CUSTOMER.
    private static final String TYPE_SUPPLIER = "SUPPLIER";

    // Thời gian: lưu GIỜ VN gán lên UTC + đọc lại bằng UTC (cùng quy ước InvoiceService/purchase).
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ReturnRepository returnRepository;
    private final ReturndetailRepository returndetailRepository;
    private final AccountRepository accountRepository;
    private final BatchRepository batchRepository;
    private final ProductunitRepository productunitRepository;
    // Purchasing module is owned by another member — consumed read-only via its repositories.
    private final PurchaseinvoiceRepository purchaseinvoiceRepository;
    private final PurchasedetailRepository purchasedetailRepository;
    // Read-only: current tax revenue group (Nhóm 2/3) — Nhóm 3 records the reversed input VAT.
    private final FinancialsettingRepository financialsettingRepository;

    public ReturnPurchaseService(ReturnRepository returnRepository,
                                 ReturndetailRepository returndetailRepository,
                                 AccountRepository accountRepository,
                                 BatchRepository batchRepository,
                                 ProductunitRepository productunitRepository,
                                 PurchaseinvoiceRepository purchaseinvoiceRepository,
                                 PurchasedetailRepository purchasedetailRepository,
                                 FinancialsettingRepository financialsettingRepository) {
        this.returnRepository = returnRepository;
        this.returndetailRepository = returndetailRepository;
        this.accountRepository = accountRepository;
        this.batchRepository = batchRepository;
        this.productunitRepository = productunitRepository;
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.purchasedetailRepository = purchasedetailRepository;
        this.financialsettingRepository = financialsettingRepository;
    }

    /** Current tax revenue group of the household (1/2/3/4), read from the financial setting singleton. */
    private int revenueGroup() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(Financialsetting::getRevenueGroup)
                .orElse(2);
    }

    /** Nhóm 3/4 = deduction method → the reversed input VAT is tracked on the slip; Nhóm 2 has nothing to reverse. */
    @Transactional(readOnly = true)
    public boolean isDeductionGroup() {
        return revenueGroup() >= 3;
    }

    /**
     * Tỷ lệ hoàn MẶC ĐỊNH, từ {@code Financialsetting.returnProductOnInvoiceValueRate}. Ở chiều NCC đây là
     * tỷ lệ NCC CHẤP NHẬN hoàn (đặc tả bổ sung 27/07 mục 1.3 bước 2) — phần NCC không hoàn
     * ({@code originalLineValue − lineRefund}) là khoản LỖ, được tính động vào chi phí hợp lý TNCN của kỳ
     * đó nếu có chứng từ, KHÔNG sinh Expense riêng.
     */
    @Transactional(readOnly = true)
    public BigDecimal getDefaultRefundRate() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(Financialsetting::getReturnProductOnInvoiceValueRate)
                .filter(rate -> rate.signum() > 0)
                .map(rate -> rate.min(FULL_REFUND_RATE))
                .orElse(FULL_REFUND_RATE)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Có tự động cấn trừ tiền NCC hoàn vào công nợ đang nợ NCC hay không ({@code autoOffsetDebtOnRefund}). */
    @Transactional(readOnly = true)
    public boolean isAutoOffsetDebt() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(Financialsetting::getAutoOffsetDebtOnRefund)
                .orElse(Boolean.TRUE);
    }

    /** Tỷ lệ hoàn thực áp cho phiếu đang lập; bỏ trống thì lấy mặc định của hệ thống. */
    private BigDecimal resolveRefundRate(BigDecimal requested) {
        if (requested == null) {
            return getDefaultRefundRate();
        }
        if (requested.signum() <= 0 || requested.compareTo(FULL_REFUND_RATE) > 0) {
            throw new IllegalArgumentException("Tỷ lệ NCC hoàn phải lớn hơn 0 và không vượt quá 100%");
        }
        return requested.setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ list / search

    @Transactional(readOnly = true)
    public Page<ReturnPurchaseListItemResponse> search(String keyword,
                                                       String fromDate,
                                                       String toDate,
                                                       String status,
                                                       Pageable pageable) {
        final String normalizedKeyword = normalize(keyword);
        final LocalDate from = parseDate(fromDate);
        final LocalDate to = parseDate(toDate);

        List<Return> returns = supplierReturns();
        Map<Integer, List<Returndetail>> detailMap = returndetailRepository.findAllWithRelations().stream()
                .filter(detail -> detail.getReturnID() != null)
                .collect(Collectors.groupingBy(detail -> detail.getReturnID().getId()));

        List<ReturnPurchaseListItemResponse> filtered = returns.stream()
                .filter(ret -> matchesKeyword(ret, normalizedKeyword))
                .filter(ret -> matchesDate(ret, from, to))
                .filter(ret -> status == null || status.isBlank() || isStatus(getStatusName(ret), status))
                .sorted(Comparator.comparing(Return::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ret -> toListItem(ret, detailMap.getOrDefault(ret.getId(), List.of())))
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<ReturnPurchaseListItemResponse> content = start >= filtered.size() ? List.of() : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public ReturnPurchaseStatsResponse getStats() {
        List<Return> returns = supplierReturns();
        YearMonth currentMonth = YearMonth.now();

        long monthlyCount = returns.stream()
                .filter(ret -> ret.getReturnDate() != null)
                .filter(ret -> YearMonth.from(toLocalDate(ret.getReturnDate())).equals(currentMonth))
                .count();

        return new ReturnPurchaseStatsResponse(
                monthlyCount,
                countByStatus(returns, ReturnPurchaseStatus.DRAFT),
                countByStatus(returns, ReturnPurchaseStatus.APPROVED),
                countByStatus(returns, ReturnPurchaseStatus.REJECTED));
    }

    public List<String> listStatuses() {
        return ReturnPurchaseStatus.ALL;
    }

    // ------------------------------------------------------------------ create screen sources

    /** All supplier-return slips (purchaseID set), read once. */
    private List<Return> supplierReturns() {
        return returnRepository.findAll().stream()
                .filter(ret -> ret.getPurchaseID() != null)
                .toList();
    }

    /**
     * Purchase invoices the store may still return goods against: received (not a draft purchase),
     * not already fully returned, and with at least one line that still has on-hand stock.
     */
    @Transactional(readOnly = true)
    public List<ReturnPurchaseInvoiceResponse> listReturnablePurchases(String keyword) {
        String normalizedKeyword = normalize(keyword);

        Map<Integer, List<Purchasedetail>> linesByPurchase = purchasedetailRepository.findAllWithRelations().stream()
                .filter(line -> line.getPurchaseID() != null)
                .collect(Collectors.groupingBy(line -> line.getPurchaseID().getId()));
        Map<Integer, Integer> onHandByDetail = onHandByPurchaseDetail();

        List<ReturnPurchaseInvoiceResponse> result = new ArrayList<>();
        for (Purchaseinvoice purchase : purchaseinvoiceRepository.findAllWithRelations()) {
            // Phiếu nhập nhà thuốc CÒN NỢ NCC vẫn trả hàng được (bỏ gate 28/07): giá trị hàng trả được cấn
            // trừ thẳng vào khoản nợ đó (netting — xem applyDebtOffset).
            if (!isReceived(purchase) || isFullyReturned(purchase)) {
                continue;
            }
            if (!matchesPurchaseKeyword(purchase, normalizedKeyword)) {
                continue;
            }
            long returnableLines = linesByPurchase.getOrDefault(purchase.getId(), List.of()).stream()
                    .filter(line -> onHandByDetail.getOrDefault(line.getId(), 0) > 0)
                    .count();
            if (returnableLines == 0) {
                continue;
            }
            result.add(new ReturnPurchaseInvoiceResponse(
                    purchase.getId(),
                    purchase.getPurchaseInvoiceCode(),
                    formatInstant(purchase.getDate()),
                    purchase.getSupplierID() != null ? purchase.getSupplierID().getName() : "Không rõ",
                    purchase.getEmployeeID() != null ? purchase.getEmployeeID().getName() : "Không rõ",
                    purchase.getTotalAmount(),
                    // Nhà thuốc còn nợ NCC bao nhiêu trên chính phiếu nhập này — số sẽ được cấn trừ.
                    outstandingDebt(purchase),
                    (int) returnableLines,
                    returnStatusDisplay(purchase.getReturnStatus())));
        }
        return result;
    }

    /** The still-returnable lines of one purchase, for the create screen (JSON). */
    @Transactional(readOnly = true)
    public List<ReturnPurchaseLineResponse> loadPurchaseLines(Integer purchaseId) {
        purchaseinvoiceRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        Map<Integer, Long> returnedByDetail = returnedQtyByPurchaseDetail();
        Map<Integer, List<Batch>> batchesByDetail = batchesByPurchaseDetail();

        List<ReturnPurchaseLineResponse> lines = new ArrayList<>();
        for (Purchasedetail line : purchasedetailRepository.findByPurchaseIdWithProduct(purchaseId)) {
            List<Batch> batches = batchesByDetail.getOrDefault(line.getId(), List.of());
            int onHand = batches.stream().mapToInt(b -> orZero(b.getStorageQuantity())).sum();
            if (onHand <= 0) {
                continue;
            }
            Batch primary = batches.get(0);
            Product product = line.getProductID();
            // Trả NCC theo ĐƠN VỊ NHẬP (hộp/lọ/thùng… tùy sản phẩm — KHÔNG hardcode "hộp"), chỉ trả nguyên
            // đơn vị nhập. Tồn kho lưu bằng đơn vị cơ sở (viên) nên quy về đơn vị nhập = tồn / tỉ lệ;
            // phần lẻ (chưa đủ 1 đơn vị nhập) không trả NCC được.
            int ratio = importRatio(primary);
            int onHandUnit = onHand / ratio;
            if (onHandUnit <= 0) {
                continue;
            }
            int returnedUnit = returnedByDetail.getOrDefault(line.getId(), 0L).intValue() / ratio;
            // Chốt nhóm: importPricePerBase là giá đã gồm thuế (gross) → giá nhập/đơn vị nhập = gross × ratio = đúng số đã trả NCC.
            BigDecimal grossPerUnit = importPricePerBase(primary).multiply(BigDecimal.valueOf(ratio));
            lines.add(new ReturnPurchaseLineResponse(
                    line.getId(),
                    product != null ? product.getProductID() : null,
                    product != null ? product.getName() : "Không rõ",
                    primary.getLotNumber() != null ? primary.getLotNumber() : lotOf(line),
                    formatLocalDate(primary.getExpirationDate() != null ? primary.getExpirationDate() : line.getExpirationDate()),
                    unitName(primary, product),
                    orZero(line.getQuantity()),   // Đã nhập (theo đơn vị nhập)
                    returnedUnit,                 // Đã trả (theo đơn vị nhập)
                    onHandUnit,                   // Tồn / SL trả tối đa (theo đơn vị nhập)
                    grossPerUnit,                 // Đơn giá nhập (gross / đơn vị nhập)
                    grossPerUnit,                 // Tiền hoàn/đơn vị = 100% gross (NCC hoàn đúng số đã trả)
                    line.getVatRate()));          // Thuế suất dòng nhập gốc — để tạm tính thuế đầu vào đảo lại
        }
        return lines;
    }

    /**
     * Tỉ lệ quy đổi ĐƠN VỊ NHẬP → đơn vị cơ sở (base per import unit), ví dụ 1 hộp = 40 viên → 40.
     * Ưu tiên {@code productunit.ratio} của đơn vị nhập; dự phòng suy từ importPrice/importPricePerBase.
     */
    private int importRatio(Batch batch) {
        if (batch == null) {
            return 1;
        }
        if (batch.getImportUnitID() != null && batch.getImportUnitID().getRatio() != null
                && batch.getImportUnitID().getRatio().compareTo(BigDecimal.ZERO) > 0) {
            return Math.max(1, batch.getImportUnitID().getRatio().setScale(0, RoundingMode.HALF_UP).intValue());
        }
        if (batch.getImportPrice() != null && batch.getImportPricePerBase() != null
                && batch.getImportPricePerBase().compareTo(BigDecimal.ZERO) > 0) {
            return Math.max(1, batch.getImportPrice()
                    .divide(batch.getImportPricePerBase(), 0, RoundingMode.HALF_UP).intValue());
        }
        return 1;
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates one supplier-return slip from a chosen purchase invoice. Owner-only.
     *
     * @param asDraft when true the slip stays a draft; otherwise it is approved immediately, which
     *                deducts stock and updates the purchase's return status.
     * @return the id of the created slip, for the redirect.
     */
    @Transactional
    public Integer createReturn(ReturnPurchaseCreateRequest request, Integer currentAccountId, boolean asDraft) {
        if (request.getPurchaseId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn phiếu nhập cần trả");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập lý do trả hàng");
        }

        Purchaseinvoice purchase = purchaseinvoiceRepository.findById(request.getPurchaseId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));
        assertReturnable(purchase);

        Account creator = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        // Tỷ lệ NCC chấp nhận hoàn cho phiếu này (mục 1.3) — mặc định theo thiết lập tài chính, chỉnh được.
        BigDecimal refundRate = resolveRefundRate(request.getRefundRate());

        Map<Integer, Purchasedetail> lineById = purchasedetailRepository.findByPurchaseIdWithProduct(purchase.getId())
                .stream().collect(Collectors.toMap(Purchasedetail::getId, line -> line, (a, b) -> a));
        Map<Integer, List<Batch>> batchesByDetail = batchesByPurchaseDetail();

        // Split each requested line across its batches (FIFO by expiry) into priced chunks.
        List<Chunk> chunks = new ArrayList<>();
        for (ReturnPurchaseLineRequest item : request.getItems()) {
            if (item == null || item.getPurchaseDetailId() == null
                    || item.getReturnQty() == null || item.getReturnQty() <= 0) {
                continue;
            }
            Purchasedetail line = lineById.get(item.getPurchaseDetailId());
            if (line == null) {
                throw new IllegalArgumentException("Dòng nhập không thuộc phiếu nhập đã chọn");
            }
            List<Batch> batches = batchesByDetail.getOrDefault(line.getId(), List.of());
            int onHand = batches.stream().mapToInt(b -> orZero(b.getStorageQuantity())).sum();
            // Input là số lượng theo ĐƠN VỊ NHẬP; tồn kho là đơn vị cơ sở (viên) → quy đổi qua tỉ lệ.
            int ratio = importRatio(batches.isEmpty() ? null : batches.get(0));
            int onHandUnit = onHand / ratio;
            int qtyUnit = item.getReturnQty();
            if (qtyUnit > onHandUnit) {
                throw new IllegalArgumentException("Số lượng trả của \"" + productName(line)
                        + "\" vượt quá tồn hiện tại (" + onHandUnit + ")");
            }
            int remaining = qtyUnit * ratio;   // quy về đơn vị cơ sở để trừ kho theo lô (FIFO)
            for (Batch batch : batches) {
                if (remaining <= 0) {
                    break;
                }
                int take = Math.min(remaining, orZero(batch.getStorageQuantity()));
                if (take <= 0) {
                    continue;
                }
                chunks.add(new Chunk(line, batch, take, importPricePerBase(batch), line.getVatRate(), refundRate));
                remaining -= take;
            }
        }

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một dòng hàng cần trả");
        }

        // Tiền NCC hoàn = gross (chưa thuế + thuế) × tỷ lệ NCC chấp nhận hoàn, cho cả Nhóm 2 và 3.
        BigDecimal totalRefund = chunks.stream()
                .map(Chunk::lineRefund)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Nhóm 3 (khấu trừ): ghi giảm thuế GTGT đầu vào đã khấu trừ; Nhóm 2 chưa từng khấu trừ → 0.
        BigDecimal totalVATRefund = isDeductionGroup()
                ? chunks.stream().map(Chunk::vatAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                : BigDecimal.ZERO;

        String status = asDraft ? ReturnPurchaseStatus.DRAFT : ReturnPurchaseStatus.APPROVED;

        Return ret = new Return();
        ret.setReturnCode(generateCode());
        ret.setInvoiceID(null);
        ret.setPurchaseID(purchase);
        ret.setReturnedBy(creator);
        ret.setReturnDate(nowVn());
        ret.setReturnType(TYPE_SUPPLIER);
        // Phiếu trả CHỈ TÍNH tiền, không thu tiền: cách nhận lại (tiền mặt / chuyển khoản) là dữ liệu của
        // phiếu thu bên Kế toán. 3 cột refundCash/refundBanking/refundCredit đã bị bỏ khỏi
        // bảng `return` — phiếu trả chỉ còn lưu tổng NCC phải hoàn (totalRefund/offsetDebtAmount).
        ret.setTotalRefund(totalRefund);
        ret.setTotalVATRefund(totalVATRefund);
        ret.setAppliedRefundRate(refundRate);
        // Số dự kiến cấn trừ vào công nợ đang nợ NCC; chốt lại theo dư nợ tại thời điểm DUYỆT.
        ret.setOffsetDebtAmount(computeDebtOffset(purchase, totalRefund));
        ret.setReason(request.getReason().trim());
        ret.setNote(trimToNull(request.getNote()));
        ret.setStatus(status);
        if (ReturnPurchaseStatus.APPROVED.equals(status)) {
            ret.setApprovedAt(nowVn());
        }

        Return savedReturn = returnRepository.save(ret);

        for (Chunk chunk : chunks) {
            Returndetail detail = new Returndetail();
            detail.setReturnID(savedReturn);
            detail.setInvoiceDetailID(null);
            detail.setPurchaseDetailID(chunk.line());
            detail.setProductID(chunk.line().getProductID());
            detail.setProductUnitID(resolveUnit(chunk.batch(), chunk.line().getProductID()));
            detail.setBatchID(chunk.batch());
            detail.setReturnQty(chunk.qty());
            detail.setBaseQtyRestored(chunk.qty());
            detail.setUnitSellPrice(chunk.grossUnitPrice());
            detail.setLineRefund(chunk.lineRefund());
            // originalLineValue = giá trị nhập GỐC 100% của phần trả; lineRefund = số NCC thực hoàn.
            // Chênh lệch giữa 2 cột = khoản LỖ khi NCC không hoàn đủ — tính động vào chi phí hợp lý TNCN
            // của kỳ (đặc tả bổ sung 27/07 mục 1.3 + 4.5), KHÔNG tạo Expense riêng.
            detail.setOriginalLineValue(chunk.grossRefund());
            // importPricePerBase là GROSS (chốt nhóm) → tách net/VAT TỪ TRONG gross: preTax = net, vatAmount = thuế đầu vào.
            detail.setVatRate(chunk.vatRate() != null ? chunk.vatRate() : BigDecimal.ZERO);
            detail.setPreTaxAmount(chunk.preTaxAmount());
            detail.setVatAmount(chunk.vatAmount());
            detail.setRestockable(false);
            returndetailRepository.save(detail);
        }

        if (ReturnPurchaseStatus.APPROVED.equals(status)) {
            applyReturnEffect(savedReturn);
        }

        return savedReturn.getId();
    }

    // ------------------------------------------------------------------ approve / reject

    /** Owner approves a draft → Đã duyệt, deducting stock and updating the purchase's return status. */
    @Transactional
    public void approve(Integer returnId) {
        Return ret = requireSupplierReturn(returnId);
        if (!isStatus(getStatusName(ret), ReturnPurchaseStatus.DRAFT)) {
            throw new IllegalArgumentException("Chỉ có thể duyệt phiếu đang ở trạng thái nháp");
        }
        ret.setStatus(ReturnPurchaseStatus.APPROVED);
        ret.setApprovedAt(nowVn());
        returnRepository.save(ret);
        applyReturnEffect(ret);
    }

    /** Owner declines a draft → Từ chối. No stock change. */
    @Transactional
    public void reject(Integer returnId) {
        Return ret = requireSupplierReturn(returnId);
        if (!isStatus(getStatusName(ret), ReturnPurchaseStatus.DRAFT)) {
            throw new IllegalArgumentException("Chỉ có thể từ chối phiếu đang ở trạng thái nháp");
        }
        ret.setStatus(ReturnPurchaseStatus.REJECTED);
        ret.setApprovedAt(nowVn());
        returnRepository.save(ret);
    }

    /**
     * Deducts stock for an approved supplier return: each line's quantity is removed from its batch
     * (blocking negative), the value returned is netted against what the pharmacy still owes the supplier
     * on that same purchase (see {@link #applyDebtOffset}), then the purchase invoice's
     * {@code returnStatus} / {@code returnQty} are recomputed.
     *
     * <p>TODO(finance): mục 3.3 của đặc tả bổ sung còn yêu cầu sinh cặp chứng từ đối ứng cho phần bù trừ
     * (Income {@code SUPPLIER} + Expense trỏ {@code purchaseID}, cả hai {@code paidByCredit =
     * offsetDebtAmount}). Chưa làm ở đây vì 2 bảng đó thuộc module Thu/Chi của thành viên khác — công nợ
     * đã trừ đúng, chỉ thiếu 2 chứng từ. Phần NCC hoàn bằng TIỀN THẬT ({@code totalRefund −
     * offsetDebtAmount}) vẫn do màn phiếu thu ghi nhận.</p>
     */
    private void applyReturnEffect(Return ret) {
        for (Returndetail detail : returndetailRepository.findByReturnIdWithRelations(ret.getId())) {
            Batch batch = detail.getBatchID();
            int available = orZero(batch.getStorageQuantity());
            int qty = orZero(detail.getReturnQty());
            if (qty > available) {
                throw new IllegalArgumentException("Không đủ tồn kho để trả cho sản phẩm \""
                        + (detail.getProductID() != null ? detail.getProductID().getName() : "") + "\"");
            }
            batch.setStorageQuantity(available - qty);
            batchRepository.save(batch);
        }
        applyDebtOffset(ret, ret.getPurchaseID());
        recomputeReturnPurchaseStatus(ret.getPurchaseID());
    }

    // ------------------------------------------------------------------ bù trừ công nợ (netting)

    /**
     * Số tiền NCC hoàn được cấn trừ vào công nợ nhà thuốc đang nợ chính phiếu nhập đó:
     * {@code MIN(totalRefund, totalAmount − paid)} — đặc tả bổ sung 27/07 mục 1.3 bước 5 + mục 3.3.
     * Trả 0 khi {@code Financialsetting.autoOffsetDebtOnRefund} tắt.
     */
    private BigDecimal computeDebtOffset(Purchaseinvoice purchase, BigDecimal totalRefund) {
        if (!isAutoOffsetDebt()) {
            return BigDecimal.ZERO;
        }
        BigDecimal refund = totalRefund != null ? totalRefund : BigDecimal.ZERO;
        return outstandingDebt(purchase).min(refund).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Thực hiện bù trừ khi duyệt phiếu: chốt {@code offsetDebtAmount} theo dư nợ TẠI THỜI ĐIỂM DUYỆT rồi
     * ghi tăng {@code PurchaseInvoice.paid} đúng số đó — nợ NCC giảm ngay trong cùng transaction (mục 3.3
     * bước 1, mục 3.4 "cập nhật trực tiếp").
     *
     * <p><strong>⚠️ ĐỔI NGHĨA CỘT {@code offsetDebtAmount} (28/07) — cần báo chủ module Thu/Chi.</strong>
     * Trước đây cột này mang nghĩa "NCC CÒN phải hoàn": lúc duyệt = {@code totalRefund}, rồi
     * {@code IncomeService.applySupplierOffsetDebtPayment} trừ dần mỗi lần NCC hoàn tiền. Theo đặc tả mới
     * nó là SỐ ĐÃ BÙ TRỪ (cố định, không phải số dư động). Vì vậy phần NCC còn phải hoàn bằng tiền thật
     * nay là {@code totalRefund − offsetDebtAmount} — {@code IncomeService.collectibleOffsetDebt} phải
     * đổi theo (và trừ dần theo tổng Income đã lập, không trừ vào cột này nữa), nếu không màn thu tiền
     * NCC sẽ hiểu sai số còn thu được.</p>
     */
    private void applyDebtOffset(Return ret, Purchaseinvoice purchase) {
        BigDecimal offset = computeDebtOffset(purchase, ret.getTotalRefund());
        ret.setOffsetDebtAmount(offset);
        returnRepository.save(ret);
        if (offset.signum() <= 0 || purchase == null) {
            return;
        }
        BigDecimal paid = purchase.getPaid() != null ? purchase.getPaid() : BigDecimal.ZERO;
        purchase.setPaid(paid.add(offset));
        purchaseinvoiceRepository.save(purchase);
    }

    private void recomputeReturnPurchaseStatus(Purchaseinvoice purchase) {
        if (purchase == null) {
            return;
        }
        Map<Integer, Long> returnedByDetail = returnedQtyByPurchaseDetail();   // base units (viên)
        Map<Integer, Integer> onHandByDetail = onHandByPurchaseDetail();       // base units (viên)
        Map<Integer, Integer> ratioByDetail = importRatioByPurchaseDetail();   // base / đơn vị nhập

        List<Purchasedetail> lines = purchasedetailRepository.findByPurchaseIdWithProduct(purchase.getId());
        int totalReturnedBase = 0;
        int totalOnHand = 0;
        for (Purchasedetail line : lines) {
            int returnedBase = returnedByDetail.getOrDefault(line.getId(), 0L).intValue();
            totalReturnedBase += returnedBase;
            totalOnHand += onHandByDetail.getOrDefault(line.getId(), 0);

            // returnQty nằm trên TỪNG Purchasedetail
            // Lưu theo ĐƠN VỊ NHẬP cho khớp purchasedetail.quantity (returndetail lưu base → chia tỉ lệ lô).
            int ratio = ratioByDetail.getOrDefault(line.getId(), 1);
            line.setReturnQty(ratio > 0 ? returnedBase / ratio : returnedBase);
            purchasedetailRepository.save(line);
        }

        String status = totalReturnedBase == 0 ? PURCHASE_RETURN_NONE
                : (totalOnHand == 0 ? PURCHASE_RETURN_FULL : PURCHASE_RETURN_PARTIAL);
        purchase.setReturnStatus(status);
        purchaseinvoiceRepository.save(purchase);
    }

    /** Tỉ lệ quy đổi (base / đơn vị nhập) cho mỗi dòng nhập — lấy từ BẤT KỲ lô nào của dòng (kể cả đã hết
     *  tồn), vì {@link #batchesByPurchaseDetail} chỉ giữ lô còn tồn nên dòng đã trả hết sẽ không có lô. */
    private Map<Integer, Integer> importRatioByPurchaseDetail() {
        Map<Integer, Integer> map = new HashMap<>();
        for (Batch batch : batchRepository.findAll()) {
            if (batch.getPurchaseDetailID() == null) {
                continue;
            }
            map.putIfAbsent(batch.getPurchaseDetailID().getId(), importRatio(batch));
        }
        return map;
    }

    // ------------------------------------------------------------------ detail

    @Transactional(readOnly = true)
    public ReturnPurchaseDetailPageResponse getDetail(Integer returnId) {
        Return ret = requireSupplierReturn(returnId);
        List<Returndetail> details = returndetailRepository.findByReturnIdWithRelations(returnId);
        List<ReturnPurchaseDetailItemResponse> items = details.stream().map(this::toDetailItem).toList();

        // Quy tổng số lượng về đơn vị nhập — returndetail lưu theo đơn vị cơ sở (viên).
        int totalQuantity = details.stream()
                .filter(d -> d.getReturnQty() != null)
                .mapToInt(d -> d.getReturnQty() / importRatio(d.getBatchID()))
                .sum();

        BigDecimal totalOriginalValue = details.stream()
                .map(Returndetail::getOriginalLineValue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Purchaseinvoice purchase = ret.getPurchaseID();
        String statusName = getStatusName(ret);

        return new ReturnPurchaseDetailPageResponse(
                ret.getId(),
                formatCode(ret.getId()),
                ret.getReturnDate(),
                formatInstant(ret.getReturnDate()),
                purchase != null ? purchase.getId() : null,
                purchase != null ? purchase.getPurchaseInvoiceCode() : "—",
                purchase != null && purchase.getSupplierID() != null ? purchase.getSupplierID().getName() : "Không rõ",
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
                nzMoney(ret.getTotalRefund()).subtract(nzMoney(ret.getOffsetDebtAmount())).max(BigDecimal.ZERO),
                ret.getAppliedRefundRate(),
                totalOriginalValue,
                totalOriginalValue.subtract(nzMoney(ret.getTotalRefund())).max(BigDecimal.ZERO),
                details.stream()
                        .map(Returndetail::getPreTaxAmount)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                ret.getTotalVATRefund(),
                isDeductionGroup(),
                items);
    }

    // ------------------------------------------------------------------ mapping helpers

    private ReturnPurchaseListItemResponse toListItem(Return ret, List<Returndetail> details) {
        Purchaseinvoice purchase = ret.getPurchaseID();
        String statusName = getStatusName(ret);
        return new ReturnPurchaseListItemResponse(
                ret.getId(),
                formatCode(ret.getId()),
                ret.getReturnDate(),
                formatInstant(ret.getReturnDate()),
                purchase != null ? purchase.getPurchaseInvoiceCode() : "—",
                purchase != null && purchase.getSupplierID() != null ? purchase.getSupplierID().getName() : "Không rõ",
                ret.getReturnedBy() != null ? ret.getReturnedBy().getName() : "Không rõ",
                details.size(),
                ret.getTotalRefund(),
                ret.getOffsetDebtAmount(),
                nzMoney(ret.getTotalRefund()).subtract(nzMoney(ret.getOffsetDebtAmount())).max(BigDecimal.ZERO),
                ret.getReturnType(),
                returnTypeDisplay(ret.getReturnType()),
                statusName,
                statusCssClass(statusName));
    }

    private ReturnPurchaseDetailItemResponse toDetailItem(Returndetail detail) {
        Product product = detail.getProductID();
        Productunit unit = detail.getProductUnitID();
        Batch batch = detail.getBatchID();
        // returndetail lưu số lượng/đơn giá theo đơn vị cơ sở (viên) để trừ kho chính xác; hiển thị quy về
        // đơn vị nhập cho khớp màn tạo: SL ÷ tỉ lệ, đơn giá × tỉ lệ (tiền hoàn giữ nguyên).
        int ratio = importRatio(batch);
        Integer qtyUnit = detail.getReturnQty() != null ? detail.getReturnQty() / ratio : null;
        BigDecimal pricePerUnit = detail.getUnitSellPrice() != null
                ? detail.getUnitSellPrice().multiply(BigDecimal.valueOf(ratio)) : null;
        return new ReturnPurchaseDetailItemResponse(
                product != null ? product.getProductID() : null,
                product != null ? product.getName() : "Không rõ",
                batch != null ? batch.getLotNumber() : "",
                batch != null ? formatLocalDate(batch.getExpirationDate()) : "",
                unit != null ? unit.getUnitName() : "",
                qtyUnit,
                pricePerUnit,
                detail.getOriginalLineValue(),
                detail.getLineRefund(),
                detail.getVatRate(),
                detail.getPreTaxAmount(),
                detail.getVatAmount());
    }

    // ------------------------------------------------------------------ purchase read-only access

    /** Batches grouped by their originating purchase-detail line, FIFO by expiry, in-stock & active only. */
    private Map<Integer, List<Batch>> batchesByPurchaseDetail() {
        return batchRepository.findAll().stream()
                .filter(batch -> batch.getPurchaseDetailID() != null)
                .filter(batch -> !Boolean.FALSE.equals(batch.getStatus()))
                .filter(batch -> orZero(batch.getStorageQuantity()) > 0)
                .sorted(Comparator.comparing(Batch::getExpirationDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Batch::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.groupingBy(batch -> batch.getPurchaseDetailID().getId(),
                        LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Integer, Integer> onHandByPurchaseDetail() {
        Map<Integer, Integer> map = new HashMap<>();
        for (Map.Entry<Integer, List<Batch>> entry : batchesByPurchaseDetail().entrySet()) {
            int sum = entry.getValue().stream().mapToInt(b -> orZero(b.getStorageQuantity())).sum();
            map.put(entry.getKey(), sum);
        }
        return map;
    }

    private Map<Integer, Long> returnedQtyByPurchaseDetail() {
        Map<Integer, Long> map = new HashMap<>();
        for (Object[] row : returndetailRepository.sumReturnedQtyByPurchaseDetail(ReturnPurchaseStatus.APPROVED)) {
            map.put((Integer) row[0], ((Number) row[1]).longValue());
        }
        return map;
    }

    private boolean isReceived(Purchaseinvoice purchase) {
        return !isStatus(purchase.getStatus(), PURCHASE_STATUS_DRAFT);
    }

    private boolean isFullyReturned(Purchaseinvoice purchase) {
        return PURCHASE_RETURN_FULL.equalsIgnoreCase(purchase.getReturnStatus());
    }

    private void assertReturnable(Purchaseinvoice purchase) {
        if (!isReceived(purchase)) {
            throw new IllegalArgumentException("Chỉ trả được phiếu nhập đã nhận hàng");
        }
        if (isFullyReturned(purchase)) {
            throw new IllegalArgumentException("Phiếu nhập này đã được trả toàn bộ");
        }
        // KHÔNG chặn phiếu nhập còn nợ NCC: giá trị hàng trả được cấn trừ vào chính khoản nợ đó
        // (PISMS_Xu_ly_Cong_no sheet "Công nợ Nhà cung cấp" ca 3/4 + đặc tả bổ sung 27/07 mục 3.3).
    }

    /** Số nhà thuốc CÒN NỢ nhà cung cấp trên phiếu nhập = totalAmount − paid (sàn 0). */
    private BigDecimal outstandingDebt(Purchaseinvoice purchase) {
        if (purchase == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = purchase.getTotalAmount() != null ? purchase.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal paid = purchase.getPaid() != null ? purchase.getPaid() : BigDecimal.ZERO;
        return total.subtract(paid).max(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------ unit / price helpers

    private Productunit resolveUnit(Batch batch, Product product) {
        if (batch != null && batch.getImportUnitID() != null) {
            return batch.getImportUnitID();
        }
        if (product == null || product.getProductID() == null) {
            throw new IllegalArgumentException("Không xác định được đơn vị trả cho sản phẩm");
        }
        List<Productunit> units = productunitRepository.findByProductId(product.getProductID());
        return units.stream()
                .filter(u -> Boolean.TRUE.equals(u.getIsBaseUnit()))
                .findFirst()
                .or(() -> units.stream().findFirst())
                .orElseThrow(() -> new IllegalArgumentException("Sản phẩm chưa có đơn vị tính"));
    }

    private String unitName(Batch batch, Product product) {
        if (batch != null && batch.getImportUnitID() != null && batch.getImportUnitID().getUnitName() != null) {
            return batch.getImportUnitID().getUnitName();
        }
        if (product != null && product.getProductID() != null) {
            return productunitRepository.findByProductId(product.getProductID()).stream()
                    .filter(u -> Boolean.TRUE.equals(u.getIsBaseUnit()))
                    .map(Productunit::getUnitName)
                    .findFirst()
                    .orElse("");
        }
        return "";
    }

    private BigDecimal importPricePerBase(Batch batch) {
        if (batch != null && batch.getImportPricePerBase() != null) {
            return batch.getImportPricePerBase();
        }
        return BigDecimal.ZERO;
    }

    private String lotOf(Purchasedetail line) {
        return line != null && line.getLotNumber() != null ? line.getLotNumber() : "";
    }

    // ------------------------------------------------------------------ filtering / formatting

    private boolean matchesKeyword(Return ret, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        Purchaseinvoice purchase = ret.getPurchaseID();
        Supplier supplier = purchase != null ? purchase.getSupplierID() : null;
        return containsNormalized(formatCode(ret.getId()), normalizedKeyword)
                || containsNormalized(purchase != null ? purchase.getPurchaseInvoiceCode() : null, normalizedKeyword)
                || containsNormalized(supplier != null ? supplier.getName() : null, normalizedKeyword)
                || containsNormalized(ret.getReason(), normalizedKeyword)
                || containsNormalized(getStatusName(ret), normalizedKeyword)
                || containsNormalized(ret.getReturnedBy() != null ? ret.getReturnedBy().getName() : null, normalizedKeyword);
    }

    private boolean matchesPurchaseKeyword(Purchaseinvoice purchase, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        Supplier supplier = purchase.getSupplierID();
        return containsNormalized(purchase.getPurchaseInvoiceCode(), normalizedKeyword)
                || containsNormalized(supplier != null ? supplier.getName() : null, normalizedKeyword);
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
        if (isStatus(statusName, ReturnPurchaseStatus.APPROVED)) {
            return "status-approved";
        }
        if (isStatus(statusName, ReturnPurchaseStatus.REJECTED)) {
            return "status-rejected";
        }
        if (isStatus(statusName, ReturnPurchaseStatus.DRAFT)) {
            return "status-draft";
        }
        return "status-default";
    }

    private String returnTypeDisplay(String type) {
        if (type == null) {
            return "—";
        }
        return TYPE_SUPPLIER.equalsIgnoreCase(type) ? "Nhà cung cấp" : type;
    }

    private String returnStatusDisplay(String returnStatus) {
        if (returnStatus == null || returnStatus.isBlank()) {
            return "Chưa trả";
        }
        return switch (returnStatus.toUpperCase(Locale.ROOT)) {
            case PURCHASE_RETURN_PARTIAL -> "Trả một phần";
            case PURCHASE_RETURN_FULL -> "Đã trả toàn bộ";
            default -> "Chưa trả";
        };
    }

    private String productName(Purchasedetail line) {
        Product product = line.getProductID();
        return product != null && product.getName() != null ? product.getName() : "Sản phẩm";
    }

    private String generateCode() {
        int nextId = returnRepository.findAll().stream()
                .map(Return::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        return "TNCC-" + String.format("%06d", nextId);
    }

    private String formatCode(Integer id) {
        return id == null ? "TNCC-000000" : "TNCC-" + String.format("%06d", id);
    }

    private Return requireSupplierReturn(Integer returnId) {
        Return ret = returnRepository.findById(returnId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng"));
        if (ret.getPurchaseID() == null) {
            throw new IllegalArgumentException("Phiếu này không phải phiếu trả hàng nhà cung cấp");
        }
        return ret;
    }

    private int orZero(Integer value) {
        return value != null ? value : 0;
    }

    private BigDecimal nzMoney(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
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

    private String formatLocalDate(LocalDate date) {
        return date == null ? "" : date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private LocalDate toLocalDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
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

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replace("Đ", "D").replace("đ", "d");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * A validated, priced return chunk (one batch worth of a returned purchase line). The supplier refunds
     * {@code refundRate}% of the import value; the rest is the pharmacy's loss (chi phí hợp lý, tính động).
     *
     * <p>"Giá nhập" bên phiếu nhập là
     * GIÁ CUỐI ĐÃ GỒM THUẾ (gross) → {@code batch.importPricePerBase} lưu gross/đơn vị cơ sở → {@code
     * unitImportPrice} là GROSS. Vì vậy tiền hoàn NCC = gross = ĐÚNG số nhà thuốc đã trả (không cộng thêm
     * VAT lên trên); net/VAT được TÁCH RA từ trong gross để ghi sổ thuế (giảm GTGT đầu vào Nhóm 3).</p>
     */
    private record Chunk(Purchasedetail line, Batch batch, int qty, BigDecimal unitImportPrice,
                         BigDecimal vatRate, BigDecimal refundRate) {
        /** Giá trị nhập GỐC 100% của chunk = importPricePerBase × qty (originalLineValue). */
        BigDecimal grossRefund() {
            return unitImportPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
        }

        /** Số NCC THỰC hoàn = giá trị gốc × tỷ lệ NCC chấp nhận hoàn. */
        BigDecimal lineRefund() {
            if (refundRate == null || refundRate.compareTo(FULL_REFUND_RATE) >= 0) {
                return grossRefund();
            }
            return grossRefund().multiply(refundRate).divide(FULL_REFUND_RATE, 2, RoundingMode.HALF_UP);
        }

        /** Net (pre-tax) portion tách từ SỐ THỰC HOÀN = lineRefund ÷ (1 + vatRate%). */
        BigDecimal preTaxAmount() {
            BigDecimal rate = vatRate != null ? vatRate : BigDecimal.ZERO;
            if (rate.compareTo(BigDecimal.ZERO) <= 0) {
                return lineRefund();
            }
            BigDecimal divisor = BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
            return lineRefund().divide(divisor, 2, RoundingMode.HALF_UP);
        }

        /** Input VAT tách từ số thực hoàn = lineRefund − net (đầu vào đã khấu trừ, dùng cho Nhóm 3). */
        BigDecimal vatAmount() {
            return lineRefund().subtract(preTaxAmount());
        }

        /** Gross unit import price (per base) — đã gồm thuế, dùng làm đơn giá dòng chi tiết. */
        BigDecimal grossUnitPrice() {
            return unitImportPrice;
        }
    }
}
