package com.example.project.service;

import com.example.project.constant.ReturnPurchaseStatus;
import com.example.project.constant.TaxRevenueGroup;
import com.example.project.dto.request.ReturnPurchaseCreateRequest;
import com.example.project.dto.request.ReturnPurchaseLineRequest;
import com.example.project.dto.response.*;
import com.example.project.entity.*;
import com.example.project.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Trả hàng cho nhà cung cấp: trả lại hàng đã nhập theo một phiếu nhập gốc. Dùng chung 2 bảng
 * {@code return} / {@code returndetail} với trả hàng khách, phân biệt bằng {@code purchaseID !=
 * null} (và {@code invoiceID == null}).
 *
 * <p>Owner-only (Dược sĩ không có quyền ở phía nhà cung cấp). Owner chỉ lưu nháp hoặc duyệt luôn;
 * không có bước bàn giao "Chờ duyệt". Trạng thái: {@link ReturnPurchaseStatus} —
 * Nháp → Đã duyệt / Từ chối.</p>
 *
 * <p><strong>Duyệt phiếu = trừ kho</strong> (hàng thật sự rời kho để trả NCC): mỗi dòng trừ từ đúng
 * các lô đã nhập theo dòng phiếu nhập gốc ({@code batch.purchaseDetailID}), theo FIFO ưu tiên hạn
 * dùng gần nhất, chặn tồn âm. Giá trị hàng trả được cấn trừ vào khoản nhà thuốc còn nợ phiếu nhập đó
 * (xem {@code applyDebtOffset}); chỉ phần còn dư mới là tiền thật NCC hoàn lại, do module Thu ghi
 * nhận.</p>
 *
 * <p>"Đã trả bao nhiêu" của từng dòng được TÍNH LẠI mỗi lần từ {@code returndetail} — bảng
 * {@code purchasedetail} không có cột {@code returnedQty} riêng.</p>
 */
@Service
public class ReturnPurchaseService {

    /** Chỉ phiếu nhập đã nhận hàng mới trả được; phiếu Nháp chưa có tồn kho nào để trả. */
    private static final String PURCHASE_STATUS_DRAFT = "Nháp";

    /**
     * Tỷ lệ hoàn đầy đủ. Dùng khi {@code Financialsetting.returnProductOnInvoiceValueRate} chưa đặt —
     * mặc định coi như NCC hoàn 100% giá trị nhập.
     */
    private static final BigDecimal FULL_REFUND_RATE = new BigDecimal("100.00");

    private static final String PURCHASE_RETURN_NONE = "NONE";
    private static final String PURCHASE_RETURN_PARTIAL = "PARTIAL";
    private static final String PURCHASE_RETURN_FULL = "FULL";

    // returnType nay KHÔNG còn mô tả tiền quay lại kiểu gì (màn trả hàng không đụng tới tiền mặt
    // nữa) — chỉ còn nói HÀNG TRẢ VỀ ĐÂU. Phiếu trả khách dùng CUSTOMER.
    private static final String TYPE_SUPPLIER = "SUPPLIER";

    // Thời gian: lưu GIỜ VN gán lên UTC + đọc lại bằng UTC (cùng quy ước InvoiceService/purchase).
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * Khoảng thời gian coi hai phiếu trùng khít nội dung là MỘT lần bấm Tạo bị lặp — xem
     * {@link #findRecentDuplicate}. Giữ bằng với bên trả khách để hai màn hành xử như nhau.
     */
    private static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(2);

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
    private final DebtService debtService;
    private final PurchaseinvoiceService purchaseinvoiceService;
    private final IncomeService incomeService;
    private InventoryAlertEventService inventoryAlertEventService;

    public ReturnPurchaseService(ReturnRepository returnRepository,
                                 ReturndetailRepository returndetailRepository,
                                 AccountRepository accountRepository,
                                 BatchRepository batchRepository,
                                 ProductunitRepository productunitRepository,
                                 PurchaseinvoiceRepository purchaseinvoiceRepository,
                                 PurchasedetailRepository purchasedetailRepository,
                                 FinancialsettingRepository financialsettingRepository,
                                 DebtService debtService,
                                 PurchaseinvoiceService purchaseinvoiceService,
                                 IncomeService incomeService) {
        this.returnRepository = returnRepository;
        this.returndetailRepository = returndetailRepository;
        this.accountRepository = accountRepository;
        this.batchRepository = batchRepository;
        this.productunitRepository = productunitRepository;
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.purchasedetailRepository = purchasedetailRepository;
        this.financialsettingRepository = financialsettingRepository;
        this.debtService = debtService;
        this.purchaseinvoiceService = purchaseinvoiceService;
        this.incomeService = incomeService;
    }

    @Autowired
    public void setInventoryAlertEventService(
            InventoryAlertEventService inventoryAlertEventService
    ) {
        this.inventoryAlertEventService =
                inventoryAlertEventService;
    }

    // Không có hàm nào đọc nhóm doanh thu ở màn này: hộ kinh doanh KHÔNG khấu trừ GTGT đầu vào ở bất kỳ
    // nhóm nào, nên trả hàng NCC không có khoản thuế nào để đảo.

    // Không còn "tỷ lệ hoàn mặc định" ở chiều NCC: từ 13/08/2026 người lập gõ thẳng SỐ TIỀN nhà cung
    // cấp chấp nhận hoàn, và màn tạo điền sẵn đúng giá trị hàng đang chọn (hoàn đủ) để sửa xuống.
    // Thiết lập `Financialsetting.returnProductOnInvoiceValueRate` nay chỉ còn chiều KHÁCH dùng.

    /** Có tự động cấn trừ tiền NCC hoàn vào công nợ đang nợ NCC hay không ({@code autoOffsetDebtOnRefund}). */
    @Transactional(readOnly = true)
    public boolean isAutoOffsetDebt() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(Financialsetting::getAutoOffsetDebtOnRefund)
                .orElse(Boolean.TRUE);
    }

    /**
     * Số tiền NCC chấp nhận hoàn cho phiếu đang lập — người lập gõ thẳng, KHÔNG suy ra từ tỷ lệ %
     * (BA chốt 13/08/2026, xem {@code ReturnPurchaseCreateRequest#getRefundAmount}).
     *
     * <p>Bắt buộc nhập: cố ý không mặc định về "hoàn đủ" khi bỏ trống. Đây là con số tiền thật do
     * NCC báo lại, tự điền hộ một giá trị là ghi vào phiếu một khoản không ai xác nhận.</p>
     *
     * <p>Trần là giá trị hàng trả theo giá nhập. Cho vượt thì {@code originalLineValue − lineRefund}
     * ra số ÂM, tức khoản "lỗ" âm — về nghiệp vụ là NCC hoàn nhiều hơn giá trị hàng, phần dôi ra là
     * thu nhập khác chứ không phải tiền hàng, không thuộc phiếu trả này.</p>
     */
    private BigDecimal resolveRefundAmount(BigDecimal requested, BigDecimal originalTotal) {
        if (requested == null) {
            throw new IllegalArgumentException("Vui lòng nhập số tiền nhà cung cấp chấp nhận hoàn");
        }
        if (requested.signum() <= 0) {
            throw new IllegalArgumentException("Số tiền nhà cung cấp chấp nhận hoàn phải lớn hơn 0");
        }
        if (requested.compareTo(originalTotal) > 0) {
            throw new IllegalArgumentException("Số tiền nhà cung cấp chấp nhận hoàn ("
                    + formatMoney(requested) + "đ) không được vượt quá giá trị hàng trả ("
                    + formatMoney(originalTotal) + "đ)");
        }
        return requested.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Chia số tiền hoàn của cả phiếu về từng dòng, theo tỷ trọng giá trị gốc của dòng đó.
     *
     * <p>Phần dư do làm tròn dồn vào dòng CUỐI để giữ bất biến {@code Σ lineRefund = totalRefund}
     * đúng đến từng đồng — lệch một đồng ở đây là màn chi tiết hiện tổng dòng khác tổng phiếu, và
     * số thu được của NCC ({@code totalRefund − offsetDebtAmount}) không khớp với các dòng.</p>
     */
    private List<BigDecimal> allocateRefund(List<Chunk> chunks, BigDecimal totalRefund,
                                            BigDecimal originalTotal) {
        List<BigDecimal> allocated = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < chunks.size(); i++) {
            BigDecimal share;
            if (i == chunks.size() - 1) {
                share = totalRefund.subtract(running);
            } else if (originalTotal.signum() == 0) {
                share = BigDecimal.ZERO;
            } else {
                share = chunks.get(i).grossRefund()
                        .multiply(totalRefund)
                        .divide(originalTotal, 2, RoundingMode.HALF_UP);
                running = running.add(share);
            }
            allocated.add(share.setScale(2, RoundingMode.HALF_UP));
        }
        return allocated;
    }

    /**
     * Tỷ lệ hoàn suy ngược từ tiền hoàn, chỉ để LƯU LẠI cho khớp cột {@code appliedRefundRate} có sẵn.
     * Không nơi nào tính tiền từ con số này nữa — tiền là số người lập gõ vào.
     */
    private BigDecimal deriveRefundRate(BigDecimal totalRefund, BigDecimal originalTotal) {
        if (originalTotal == null || originalTotal.signum() == 0) {
            return null;
        }
        return totalRefund.multiply(FULL_REFUND_RATE)
                .divide(originalTotal, 2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ danh sách / tìm kiếm

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

    // ------------------------------------------------------------------ nguồn dữ liệu màn tạo

    /** Mọi phiếu trả NCC (có gắn purchaseID), đọc một lần dùng chung. */
    private List<Return> supplierReturns() {
        return returnRepository.findAll().stream()
                .filter(ret -> ret.getPurchaseID() != null)
                .toList();
    }

    /**
     * Các phiếu nhập nhà thuốc còn trả hàng được: đã nhận hàng (không phải phiếu Nháp), chưa trả
     * hết, và còn ít nhất một dòng vẫn còn tồn kho.
     */
    @Transactional(readOnly = true)
    public List<ReturnPurchaseInvoiceResponse> listReturnablePurchases(String keyword) {
        String normalizedKeyword = normalize(keyword);

        Map<Integer, List<Purchasedetail>> linesByPurchase = purchasedetailRepository.findAllWithRelations().stream()
                .filter(line -> line.getPurchaseID() != null)
                .collect(Collectors.groupingBy(line -> line.getPurchaseID().getId()));
        Map<Integer, Integer> onHandByDetail = onHandByPurchaseDetail();
        // Dựng MỘT lần cho cả danh sách: trạng thái trả được tính lại từ dữ liệu (xem isFullyReturned),
        // gọi theo từng phiếu là quét lại cả bảng cho mỗi phiếu.
        Map<Integer, Long> returnedByDetail = returnedQtyByPurchaseDetail();
        Map<Integer, Integer> ratioByDetail = importRatioByPurchaseDetail();

        List<ReturnPurchaseInvoiceResponse> result = new ArrayList<>();
        for (Purchaseinvoice purchase : purchaseinvoiceRepository.findAllWithRelations()) {
            // Phiếu nhập nhà thuốc CÒN NỢ NCC vẫn trả hàng được (bỏ gate 28/07): giá trị hàng trả được cấn
            // trừ thẳng vào khoản nợ đó (netting — xem applyDebtOffset).
            if (!isReceived(purchase)) {
                continue;
            }
            List<Purchasedetail> lines = linesByPurchase.getOrDefault(purchase.getId(), List.of());
            int returnedBase = returnedBaseQty(lines, returnedByDetail);
            // Đã trả đủ SỐ ĐÃ NHẬP thì mới hết trả được — không phải "hết tồn kho".
            if (isFullyReturned(returnedBase, importedBaseQty(lines, ratioByDetail))) {
                continue;
            }
            if (!matchesPurchaseKeyword(purchase, normalizedKeyword)) {
                continue;
            }
            long returnableLines = lines.stream()
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
                    // Suy từ dữ liệu, không đọc cột đã lưu: phiếu bị luật cũ đánh nhầm "Đã trả toàn bộ"
                    // mà vẫn nằm trong danh sách này thì nhãn phải nói đúng là mới trả một phần.
                    returnStatusDisplay(returnedBase == 0 ? PURCHASE_RETURN_NONE : PURCHASE_RETURN_PARTIAL)));
        }
        return result;
    }

    /** Các dòng còn trả được của một phiếu nhập, cho màn tạo phiếu (endpoint JSON). */
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
                    grossPerUnit));               // Tiền hoàn/đơn vị = 100% gross (NCC hoàn đúng số đã trả)
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

    // ------------------------------------------------------------------ tạo phiếu

    /**
     * Tạo một phiếu trả hàng NCC từ phiếu nhập được chọn. Owner-only.
     *
     * <p>Trước khi ghi, phiếu được đối chiếu với các phiếu vừa lập để không tạo bản sao khi người
     * dùng bấm Tạo lần thứ hai — xem {@link #findRecentDuplicate}.</p>
     *
     * @param asDraft true thì phiếu giữ ở trạng thái nháp; ngược lại được duyệt ngay, trừ kho và
     *                cập nhật trạng thái trả hàng của phiếu nhập.
     * @return id phiếu để chuyển hướng, kèm cờ cho biết đó là phiếu vừa tạo hay phiếu đã có.
     */
    @Transactional
    public SlipCreateOutcome createReturn(ReturnPurchaseCreateRequest request, Integer currentAccountId,
                                          boolean asDraft) {
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

        Map<Integer, Purchasedetail> lineById = purchasedetailRepository.findByPurchaseIdWithProduct(purchase.getId())
                .stream().collect(Collectors.toMap(Purchasedetail::getId, line -> line, (a, b) -> a));
        Map<Integer, List<Batch>> batchesByDetail = batchesByPurchaseDetail();

        // Tách mỗi dòng yêu cầu trả thành các phần theo lô (FIFO ưu tiên hạn dùng gần nhất), mỗi
        // phần đã tính sẵn số tiền.
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
                chunks.add(new Chunk(line, batch, take, importPricePerBase(batch)));
                remaining -= take;
            }
        }

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một dòng hàng cần trả");
        }

        // Giá trị hàng trả theo giá nhập (100%) — trần của số NCC có thể hoàn.
        BigDecimal originalTotal = chunks.stream()
                .map(Chunk::grossRefund)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Tiền NCC hoàn nay do người lập GÕ THẲNG (số NCC báo lại), không suy ra từ tỷ lệ % nữa —
        // xem ReturnPurchaseCreateRequest#getRefundAmount.
        BigDecimal totalRefund = resolveRefundAmount(request.getRefundAmount(), originalTotal);
        List<BigDecimal> lineRefunds = allocateRefund(chunks, totalRefund, originalTotal);
        String status = asDraft ? ReturnPurchaseStatus.DRAFT : ReturnPurchaseStatus.APPROVED;

        String reason = request.getReason().trim();
        Optional<Return> duplicate = findRecentDuplicate(purchase.getId(), currentAccountId, status,
                totalRefund, reason, qtyByPurchaseLine(chunks));
        if (duplicate.isPresent()) {
            Return existing = duplicate.get();
            return SlipCreateOutcome.duplicate(existing.getId(), "Phiếu trả hàng nhà cung cấp "
                    + existing.getReturnCode() + " với đúng nội dung này đã được lập lúc "
                    + formatInstant(existing.getReturnDate())
                    + ". Hệ thống KHÔNG tạo thêm phiếu mới — đây là phiếu đã lưu.");
        }

        Return ret = new Return();
        ret.setReturnCode(temporaryCode());
        ret.setInvoiceID(null);
        ret.setPurchaseID(purchase);
        ret.setReturnedBy(creator);
        ret.setReturnDate(nowVn());
        ret.setReturnType(TYPE_SUPPLIER);
        // Phiếu trả CHỈ TÍNH tiền, không thu tiền: cách nhận lại (tiền mặt / chuyển khoản) là dữ liệu của
        // phiếu thu bên Kế toán. 3 cột refundCash/refundBanking/refundCredit đã bị bỏ khỏi
        // bảng `return` — phiếu trả chỉ còn lưu tổng NCC phải hoàn (totalRefund/offsetDebtAmount).
        ret.setTotalRefund(totalRefund);
        // Tỷ lệ nay là số SUY RA từ tiền hoàn, chỉ để đối chiếu với dữ liệu cũ (thời còn nhập %) —
        // không còn là dữ liệu người dùng nhập, và không màn nào tính tiền từ nó nữa.
        ret.setAppliedRefundRate(deriveRefundRate(totalRefund, originalTotal));
        // Số dự kiến cấn trừ vào công nợ đang nợ NCC; chốt lại theo dư nợ tại thời điểm DUYỆT.
        ret.setOffsetDebtAmount(computeDebtOffset(purchase, totalRefund));
        ret.setReason(reason);
        ret.setNote(trimToNull(request.getNote()));
        ret.setStatus(status);
        if (ReturnPurchaseStatus.APPROVED.equals(status)) {
            ret.setApprovedAt(nowVn());
        }

        Return savedReturn = returnRepository.save(ret);
        // Mã thật = TNCC- + id do DB cấp, ghi ngay sau INSERT (cùng transaction).
        savedReturn.setReturnCode(formatCode(savedReturn.getId()));

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
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
            detail.setLineRefund(lineRefunds.get(i));
            // originalLineValue = giá trị nhập GỐC 100% của phần trả; lineRefund = số NCC thực hoàn.
            // Chênh lệch giữa 2 cột = khoản LỖ khi NCC không hoàn đủ — tính động vào chi phí hợp lý TNCN
            // của kỳ, KHÔNG tạo Expense riêng.
            detail.setOriginalLineValue(chunk.grossRefund());
            // KHÔNG tách net/VAT trên dòng trả (3 cột đó đã bị bỏ khỏi `returndetail`).
            // `importPricePerBase` là giá GỘP nên lineRefund đã là số NCC thực hoàn, không cộng thêm thuế.
            detail.setRestockable(false);
            returndetailRepository.save(detail);
        }

        if (ReturnPurchaseStatus.APPROVED.equals(status)) {
            applyReturnEffect(savedReturn);
        }

        return SlipCreateOutcome.created(savedReturn.getId());
    }

    /**
     * Tìm phiếu trả NCC vừa lập có nội dung TRÙNG KHÍT với phiếu sắp tạo — dấu hiệu của một cú bấm
     * Tạo lặp lại chứ không phải một phiếu mới. Đối xứng với {@code ReturnService#findRecentDuplicate};
     * lý do so khớp toàn bộ nội dung (thay vì chỉ "cùng phiếu nhập") xem javadoc bên đó.
     *
     * <p>Chữ ký dòng hàng gộp theo DÒNG NHẬP, cố ý bỏ lô ra ngoài: cùng một yêu cầu trả có thể được
     * FIFO chia sang bộ lô khác nhau giữa hai lần bấm (lần đầu đã trừ kho nếu tạo-và-duyệt), lấy lô
     * làm khoá thì lần gửi lại không còn khớp và vẫn lọt.</p>
     *
     * <p>Cửa sổ đo trên {@code returnDate} nên mốc phải lấy bằng {@link #nowVn()} — cột đó lưu giờ VN
     * gắn nhãn UTC, so với {@code Instant.now()} là lệch 7 tiếng.</p>
     */
    private Optional<Return> findRecentDuplicate(Integer purchaseId,
                                                 Integer creatorId,
                                                 String status,
                                                 BigDecimal totalRefund,
                                                 String reason,
                                                 Map<Integer, Integer> qtyByPurchaseLine) {
        Instant since = nowVn().minus(DUPLICATE_WINDOW);
        for (Return candidate : returnRepository.findByPurchaseID_IdOrderByReturnDateDesc(purchaseId)) {
            if (candidate.getReturnDate() == null || candidate.getReturnDate().isBefore(since)) {
                // Danh sách đã sắp giảm dần theo ngày nên gặp phiếu ngoài cửa sổ là dừng được.
                break;
            }
            if (candidate.getReturnedBy() == null
                    || !Objects.equals(candidate.getReturnedBy().getId(), creatorId)
                    || !status.equals(candidate.getStatus())
                    || !sameAmount(candidate.getTotalRefund(), totalRefund)
                    || !reason.equals(candidate.getReason())) {
                continue;
            }
            if (qtyByPurchaseLine.equals(savedQtyByPurchaseLine(candidate.getId()))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Chữ ký dòng hàng của phiếu SẮP tạo: {@code purchaseDetailId -> tổng số lượng (đơn vị cơ sở)}. */
    private Map<Integer, Integer> qtyByPurchaseLine(List<Chunk> chunks) {
        Map<Integer, Integer> qtyByLine = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            qtyByLine.merge(chunk.line().getId(), chunk.qty(), Integer::sum);
        }
        return qtyByLine;
    }

    /** Cùng chữ ký như trên, dựng lại từ một phiếu ĐÃ lưu. */
    private Map<Integer, Integer> savedQtyByPurchaseLine(Integer returnId) {
        Map<Integer, Integer> qtyByLine = new LinkedHashMap<>();
        for (Returndetail detail : returndetailRepository.findByReturnIdWithRelations(returnId)) {
            if (detail.getPurchaseDetailID() != null) {
                qtyByLine.merge(detail.getPurchaseDetailID().getId(), orZero(detail.getReturnQty()), Integer::sum);
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

    // ------------------------------------------------------------------ duyệt / từ chối

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
     * Trừ kho cho một phiếu trả NCC vừa được duyệt: số lượng từng dòng bị trừ khỏi đúng lô của nó
     * (chặn âm), giá trị hàng trả được cấn trừ vào khoản nhà thuốc còn nợ NCC trên chính phiếu nhập
     * đó (xem {@link #applyDebtOffset}), rồi tính lại {@code returnStatus} / {@code returnQty} của
     * phiếu nhập.
     *
     * <p>TODO(finance): chưa sinh cặp chứng từ đối ứng cho phần bù trừ (Income {@code SUPPLIER} + Expense
     * trỏ {@code purchaseID}) vì 2 bảng đó thuộc module Thu/Chi — công nợ đã trừ đúng, chỉ thiếu chứng từ.
     * Phần NCC hoàn bằng TIỀN THẬT vẫn do màn phiếu thu ghi nhận.</p>
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
            saveBatchGuardingConcurrentEdit(batch, detail);
            if (inventoryAlertEventService != null
                    && batch.getProductID() != null) {

                inventoryAlertEventService
                        .checkBatchAfterCommit(
                                batch.getProductID()
                                        .getProductID(),
                                batch.getId()
                        );
            }
        }
        applyDebtOffset(ret, ret.getPurchaseID());
        recomputeReturnPurchaseStatus(ret.getPurchaseID());
    }

    /**
     * Ghi tồn kho một lô, dịch lỗi khoá lạc quan thành câu người dùng đọc được — xem
     * {@code StockadjustmentService.saveBatchGuardingConcurrentEdit} để biết cơ chế.
     */
    private void saveBatchGuardingConcurrentEdit(Batch batch, Returndetail detail) {
        try {
            batchRepository.saveAndFlush(batch);
        } catch (ObjectOptimisticLockingFailureException exception) {
            String product = detail.getProductID() != null ? detail.getProductID().getName() : "";
            throw new IllegalArgumentException("Lô hàng của sản phẩm \"" + product
                    + "\" vừa được người khác cập nhật."
                    + " Vui lòng tải lại trang để xem tồn kho mới nhất rồi thực hiện lại.", exception);
        }
    }

    // ------------------------------------------------------------------ bù trừ công nợ (netting)

    /**
     * Số tiền NCC hoàn được cấn trừ vào công nợ nhà thuốc đang nợ chính phiếu nhập đó:
     * {@code MIN(totalRefund, totalAmount − paid)}. Trả 0 khi {@code autoOffsetDebtOnRefund} tắt.
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
     * ghi tăng {@code PurchaseInvoice.paid} và đồng bộ {@code status} qua
     * {@link DebtService#recordPurchaseDebtOffset}, ngay trong cùng transaction.
     *
     * <p><strong>⚠️ {@code offsetDebtAmount} là SỐ ĐÃ BÙ TRỪ (cố định)</strong>, không phải số dư động.
     * Phần NCC còn phải hoàn bằng tiền thật là {@code totalRefund − offsetDebtAmount} ⇒
     * {@code IncomeService.collectibleOffsetDebt} phải đọc theo công thức đó, nếu không màn thu tiền NCC
     * hiểu sai số còn thu được.</p>
     */
    private void applyDebtOffset(Return ret, Purchaseinvoice purchase) {
        BigDecimal offset = computeDebtOffset(purchase, ret.getTotalRefund());
        ret.setOffsetDebtAmount(offset);
        returnRepository.save(ret);
        if (offset.signum() <= 0 || purchase == null || purchase.getId() == null) {
            return;
        }
        debtService.recordPurchaseDebtOffset(purchase.getId(), offset);
    }

    /**
     * Chốt lại {@code returnStatus} / {@code returnQty} của phiếu nhập sau mỗi lần duyệt phiếu trả NCC.
     *
     * <p><b>"Trả toàn bộ" đo theo SỐ LƯỢNG ĐÃ NHẬP, không phải theo tồn kho còn lại.</b> Nhập 10, bán 2,
     * trả NCC 8 ⇒ vẫn là <i>trả một phần</i> (8/10); về sau khách trả lại 2 hộp rồi mang trả nốt cho NCC
     * mới thành <i>trả toàn bộ</i>. Đo theo tồn kho thì bán hết phần còn lại là phiếu nhập bị coi như đã
     * trả xong, tới lúc khách trả hàng (lô {@code RT-} trỏ về đúng dòng nhập gốc, có tồn thật) thì phiếu
     * nhập đã bị loại khỏi danh sách chọn ⇒ không mang trả NCC được nữa. Phần "còn có thể trả" mới là chỗ
     * đo theo tồn, do {@link #listReturnablePurchases} và {@link #loadPurchaseLines} lọc.</p>
     */
    private void recomputeReturnPurchaseStatus(Purchaseinvoice purchase) {
        if (purchase == null) {
            return;
        }
        Map<Integer, Long> returnedByDetail = returnedQtyByPurchaseDetail();   // base units (viên)
        Map<Integer, Integer> ratioByDetail = importRatioByPurchaseDetail();   // base / đơn vị nhập

        List<Purchasedetail> lines = purchasedetailRepository.findByPurchaseIdWithProduct(purchase.getId());
        int totalReturnedBase = 0;
        int totalImportedBase = 0;
        for (Purchasedetail line : lines) {
            int returnedBase = returnedByDetail.getOrDefault(line.getId(), 0L).intValue();
            totalReturnedBase += returnedBase;

            // returnQty nằm trên TỪNG Purchasedetail
            // Lưu theo ĐƠN VỊ NHẬP cho khớp purchasedetail.quantity (returndetail lưu base → chia tỉ lệ lô).
            int ratio = ratioByDetail.getOrDefault(line.getId(), 1);
            if (ratio <= 0) {
                ratio = 1;
            }
            totalImportedBase += orZero(line.getQuantity()) * ratio;
            line.setReturnQty(returnedBase / ratio);
            purchasedetailRepository.save(line);
        }

        String status;
        if (totalReturnedBase == 0) {
            status = PURCHASE_RETURN_NONE;
        } else if (isFullyReturned(totalReturnedBase, totalImportedBase)) {
            status = PURCHASE_RETURN_FULL;
        } else {
            status = PURCHASE_RETURN_PARTIAL;
        }
        purchase.setReturnStatus(status);
        purchaseinvoiceService.persistPurchaseInvoice(purchase);
    }

    /** Tổng số lượng ĐÃ NHẬP của một phiếu nhập, quy về đơn vị cơ sở (viên). */
    private int importedBaseQty(List<Purchasedetail> lines, Map<Integer, Integer> ratioByDetail) {
        int total = 0;
        for (Purchasedetail line : lines) {
            int ratio = ratioByDetail.getOrDefault(line.getId(), 1);
            total += orZero(line.getQuantity()) * (ratio > 0 ? ratio : 1);
        }
        return total;
    }

    /** Tổng số lượng ĐÃ TRẢ về NCC của một phiếu nhập (đơn vị cơ sở), chỉ tính phiếu trả đã duyệt. */
    private int returnedBaseQty(List<Purchasedetail> lines, Map<Integer, Long> returnedByDetail) {
        int total = 0;
        for (Purchasedetail line : lines) {
            total += returnedByDetail.getOrDefault(line.getId(), 0L).intValue();
        }
        return total;
    }

    /** Đã trả đủ số đã nhập hay chưa. Dùng {@code >=} phòng dữ liệu cũ trả dôi ra vì luật cũ. */
    private boolean isFullyReturned(int returnedBase, int importedBase) {
        return importedBase > 0 && returnedBase >= importedBase;
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

    // ------------------------------------------------------------------ chi tiết

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
                supplierCashRefundDue(ret),
                ret.getAppliedRefundRate(),
                totalOriginalValue,
                totalOriginalValue.subtract(nzMoney(ret.getTotalRefund())).max(BigDecimal.ZERO),
                items);
    }

    // ------------------------------------------------------------------ hàm dựng DTO

    /** Tiền NCC còn phải hoàn — trừ các phiếu thu SUPPLIER đã hoàn thành (không đụng offsetDebtAmount). */
    private BigDecimal supplierCashRefundDue(Return ret) {
        if (ret != null && ret.getId() != null && isStatus(getStatusName(ret), ReturnPurchaseStatus.APPROVED)) {
            return incomeService.remainingCollectibleForSupplierReturn(ret.getId());
        }
        return nzMoney(ret != null ? ret.getTotalRefund() : null)
                .subtract(nzMoney(ret != null ? ret.getOffsetDebtAmount() : null))
                .max(BigDecimal.ZERO);
    }

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
                supplierCashRefundDue(ret),
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
                detail.getLineRefund());
    }

    // ------------------------------------------------------------------ đọc phiếu nhập (chỉ đọc)

    /** Các lô gom theo dòng phiếu nhập gốc, sắp FIFO theo hạn dùng, chỉ lấy lô còn tồn và đang hoạt động. */
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

    /**
     * Phiếu nhập đã trả HẾT số đã nhập cho NCC hay chưa — <b>tính lại từ dữ liệu, KHÔNG đọc cột
     * {@code returnStatus} đã lưu</b>: dữ liệu cũ được ghi theo luật cũ ("hết tồn kho là trả toàn bộ") nên
     * còn nhiều phiếu bị đánh FULL oan, đọc thẳng cột là chúng vĩnh viễn không trả tiếp được. Cột vẫn được
     * ghi lại đúng ở {@link #recomputeReturnPurchaseStatus} mỗi lần duyệt phiếu trả, và vẫn dùng để hiển thị.
     */
    private boolean isFullyReturned(Purchaseinvoice purchase) {
        if (purchase == null || purchase.getId() == null) {
            return false;
        }
        List<Purchasedetail> lines = purchasedetailRepository.findByPurchaseIdWithProduct(purchase.getId());
        return isFullyReturned(
                returnedBaseQty(lines, returnedQtyByPurchaseDetail()),
                importedBaseQty(lines, importRatioByPurchaseDetail()));
    }

    private void assertReturnable(Purchaseinvoice purchase) {
        if (!isReceived(purchase)) {
            throw new IllegalArgumentException("Chỉ trả được phiếu nhập đã nhận hàng");
        }
        if (isFullyReturned(purchase)) {
            throw new IllegalArgumentException("Phiếu nhập này đã được trả toàn bộ");
        }
        // KHÔNG chặn phiếu nhập còn nợ NCC: giá trị hàng trả được cấn trừ vào chính khoản nợ đó.
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

    // ------------------------------------------------------------------ hàm phụ trợ đơn vị / giá

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

    /** Số tiền dạng "3.088.000" để nhét vào câu thông báo (không kèm ký hiệu tiền). */
    private String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        return new DecimalFormat("#,##0", new DecimalFormatSymbols(Locale.forLanguageTag("vi-VN")))
                .format(amount);
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

    // ------------------------------------------------------------------ lọc / định dạng

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

    /**
     * Mã tạm dùng đúng một lần, chỉ để qua được ràng buộc {@code NOT NULL UNIQUE} của cột mã tại thời
     * điểm INSERT — lúc đó chưa biết id nên chưa dựng được mã thật. Ngay sau khi lưu, mã được ghi lại
     * theo id do DB cấp. Không bao giờ commit ra ngoài: cả hai bước nằm trong cùng một transaction.
     *
     * <p>ĐỪNG quay lại cách {@code max(id) + 1} <em>trước khi</em> lưu: đọc rồi mới ghi thì hai người tạo
     * phiếu cùng lúc nhận cùng một số, mà cột {@code returnCode} có UNIQUE nên người thứ hai ăn lỗi 500
     * thay vì được cấp mã kế tiếp. AUTO_INCREMENT của DB thì không bao giờ cấp trùng.</p>
     */
    private String temporaryCode() {
        return "TMP-" + UUID.randomUUID();
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
     * Một phần trả hàng đã tính đủ số tiền (giá trị hàng trả từ đúng MỘT lô của một dòng phiếu nhập).
     * NCC hoàn {@code refundRate}% giá trị nhập; phần còn lại là khoản lỗ của nhà thuốc (chi phí hợp
     * lý, tính động).
     *
     * <p>"Giá nhập" bên phiếu nhập là
     * GIÁ CUỐI ĐÃ GỒM THUẾ (gross) → {@code batch.importPricePerBase} lưu gross/đơn vị cơ sở → {@code
     * unitImportPrice} là GROSS. Vì vậy tiền hoàn NCC = gross = ĐÚNG số nhà thuốc đã trả (không cộng thêm
     * VAT lên trên). KHÔNG tách net/VAT: hộ kinh doanh không khấu trừ GTGT đầu vào nên không có gì để ghi
     * sổ đảo ngược.</p>
     */
    private record Chunk(Purchasedetail line, Batch batch, int qty, BigDecimal unitImportPrice) {
        /** Giá trị nhập GỐC 100% của chunk = importPricePerBase × qty (originalLineValue). */
        BigDecimal grossRefund() {
            return unitImportPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
        }

        // Tiền hoàn của dòng KHÔNG còn tính ở đây: nó được chia ra từ số tiền cả phiếu mà người lập
        // gõ vào — xem allocateRefund().

        /** Gross unit import price (per base) — đã gồm thuế, dùng làm đơn giá dòng chi tiết. */
        BigDecimal grossUnitPrice() {
            return unitImportPrice;
        }
    }
}
