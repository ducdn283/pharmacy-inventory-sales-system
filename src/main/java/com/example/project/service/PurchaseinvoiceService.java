package com.example.project.service;

import com.example.project.constant.PurchaseInvoiceStatus;
import com.example.project.constant.RoleConstants;
import com.example.project.dto.request.PurchaseInvoiceCreateRequest;
import com.example.project.dto.request.PurchaseInvoiceDetailCreateRequest;
import com.example.project.dto.response.*;
import com.example.project.entity.*;
import com.example.project.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
 * The Owner has full permission regardless of whether the pharmacy has an active Accountant (BA,
 * 2026-08-11 — supersedes the earlier 2026-08-04 rule that blocked the Owner's direct-create path
 * once an Accountant was active). Two independent paths into a purchase invoice now coexist:
 *
 * <ul>
 *   <li><strong>Owner</strong> — always creates directly via {@link #createPurchaseInvoice}, one
 *       step, regardless of whether an Accountant is active: {@link Batch} rows are created in the
 *       same transaction and {@code approvedAt} is stamped to the creation moment (the Owner is,
 *       in effect, both creator and approver). {@link #canCreatePurchaseInvoice} is always
 *       {@code true} for {@code OWNER}. The Owner may also save a Nháp first via
 *       {@link #createPurchaseInvoiceDraft} and come back later — editing it via
 *       {@link #updatePurchaseInvoiceDraft} (stay Nháp) or {@link #finalizePurchaseInvoiceDraft}
 *       (create the batches and approve in the same action, since the Owner never needs a separate
 *       approval step from themselves).</li>
 *   <li><strong>Active Accountant</strong> — the Accountant may additionally create via
 *       {@link #createPurchaseInvoiceDraft}/{@link #createPurchaseInvoiceForApproval}, optionally
 *       edit a Nháp ({@link #updatePurchaseInvoiceDraft}/{@link #submitPurchaseInvoiceDraft}) or
 *       delete it ({@link #deletePurchaseInvoiceDraft}), and the Owner approves
 *       ({@link #approvePurchaseInvoice}) or rejects ({@link #rejectPurchaseInvoice}) it. Stock is
 *       received — {@link Batch} rows created, supplier cost price refreshed — and
 *       {@code approvedAt} stamped ONLY at {@link #approvePurchaseInvoice}, never before; a
 *       rejected invoice goes back to Nháp with nothing to reverse, since nothing was ever
 *       received.</li>
 * </ul>
 */
@Service
public class PurchaseinvoiceService {

    private final PurchaseinvoiceRepository purchaseinvoiceRepository;
    private final PurchasedetailRepository purchasedetailRepository;
    private final SupplierRepository supplierRepository;
    private final AccountRepository accountRepository;
    private final ProductRepository productRepository;
    private final BatchRepository batchRepository;
    private final ProductunitRepository productunitRepository;
    private final SupplierproductRepository supplierproductRepository;
    private final ProcurementplanRepository procurementplanRepository;
    private final ProcurementplandetailRepository procurementplandetailRepository;
    private final AccountpermissionRepository accountpermissionRepository;
    private InventoryAlertEventService inventoryAlertEventService;

    public PurchaseinvoiceService(PurchaseinvoiceRepository purchaseinvoiceRepository,
                                  PurchasedetailRepository purchasedetailRepository,
                                  SupplierRepository supplierRepository,
                                  AccountRepository accountRepository,
                                  ProductRepository productRepository,
                                  BatchRepository batchRepository,
                                  ProductunitRepository productunitRepository,
                                  SupplierproductRepository supplierproductRepository,
                                  ProcurementplanRepository procurementplanRepository,
                                  ProcurementplandetailRepository procurementplandetailRepository,
                                  AccountpermissionRepository accountpermissionRepository) {
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.purchasedetailRepository = purchasedetailRepository;
        this.supplierRepository = supplierRepository;
        this.accountRepository = accountRepository;
        this.productRepository = productRepository;
        this.batchRepository = batchRepository;
        this.productunitRepository = productunitRepository;
        this.supplierproductRepository = supplierproductRepository;
        this.procurementplandetailRepository = procurementplandetailRepository;
        this.procurementplanRepository = procurementplanRepository;
        this.accountpermissionRepository = accountpermissionRepository;
    }

    @Autowired
    public void setInventoryAlertEventService(
            InventoryAlertEventService inventoryAlertEventService
    ) {
        this.inventoryAlertEventService =
                inventoryAlertEventService;
    }

    // Product types (Type.sortType / Type.name) that need special handling on purchase invoice
    // creation. Compared accent/case-insensitively against normalize(...) — same idiom
    // ReturnService.isReturnableProductType() already uses for the identical sortType/name pair.
    private static final String SORT_MEDICAL_DEVICE = "thiet bi y te";
    private static final String DEVICE_MACHINE_MARK = "may";
    private static final String DEVICE_NO_EXPIRY_MARK = "khong han";

    // ------------------------------------------------------------------ who may create

    /**
     * Whether the given role may create a purchase invoice right now. The Accountant may always
     * create (when reachable at all — the {@code /accountant/**} URL prefix already implies an
     * active accountant account); the Owner has full permission and may always create directly too
     * (2026-08-11 — the Owner is no longer locked out just because an Accountant is active).
     */
    @Transactional(readOnly = true)
    public boolean canCreatePurchaseInvoice(String role) {
        return RoleConstants.ACCOUNTANT.equals(role) || RoleConstants.OWNER.equals(role);
    }

    /**
     * Giữ lại method cũ để PurchaseinvoiceController REST không bị lỗi compile.
     */
    @Transactional(readOnly = true)
    public List<PurchaseinvoiceResponse> getAll() {
        return purchaseinvoiceRepository.findAllWithRelations()
                .stream()
                .map(PurchaseinvoiceResponse::from)
                .toList();
    }

    /**
     * Purchase Invoice List search: three independent, optional, ANDed fields (mã phiếu / nhà cung
     * cấp / sản phẩm — the expandable search box) plus date range and payment-status filters, then
     * in-memory pagination. The old single combined {@code keyword} field and the exact-id supplier
     * dropdown filter were both replaced by this — see the Product List filter for the same idea.
     */
    @Transactional(readOnly = true)
    public Page<PurchaseInvoiceListItemResponse> searchPurchaseInvoices(String codeQuery,
                                                                        String supplierQuery,
                                                                        String productQuery,
                                                                        String fromDate,
                                                                        String toDate,
                                                                        String paymentStatus,
                                                                        Pageable pageable) {
        String normalizedCode = normalize(codeQuery);
        String normalizedSupplier = normalize(supplierQuery);
        String normalizedProduct = normalize(productQuery);
        LocalDate from = parseDate(fromDate);
        LocalDate to = parseDate(toDate);

        List<Purchaseinvoice> invoices = purchaseinvoiceRepository.findAllWithRelations();
        List<Purchasedetail> allDetails = purchasedetailRepository.findAllWithRelations();

        Map<Integer, List<Purchasedetail>> detailMap = allDetails.stream()
                .filter(detail -> detail.getPurchaseID() != null)
                .collect(Collectors.groupingBy(detail -> detail.getPurchaseID().getId()));

        List<PurchaseInvoiceListItemResponse> filtered = invoices.stream()
                .filter(invoice -> matchesCode(invoice, normalizedCode))
                .filter(invoice -> matchesSupplier(invoice, normalizedSupplier))
                .filter(invoice -> matchesProduct(detailMap.getOrDefault(invoice.getId(), List.of()), normalizedProduct))
                .filter(invoice -> matchesDate(invoice, from, to))
                .map(invoice -> toListItem(invoice, detailMap.getOrDefault(invoice.getId(), List.of())))
                .filter(item -> paymentStatus == null || paymentStatus.isBlank()
                        || paymentStatus.equals(item.getPaymentStatus()))
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());

        List<PurchaseInvoiceListItemResponse> content = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public PurchaseInvoiceStatsResponse getStats() {
        // Phiếu đã hủy coi như chưa từng tồn tại đối với thống kê tiền/công nợ — xem cancelPurchaseInvoice().
        List<Purchaseinvoice> invoices = purchaseinvoiceRepository.findAllWithRelations().stream()
                .filter(invoice -> !PurchaseInvoiceStatus.CANCELLED.equals(invoice.getStatus()))
                .toList();

        LocalDate today = LocalDate.now();

        long todayCount = invoices.stream()
                .filter(invoice -> invoice.getDate() != null)
                .filter(invoice -> toLocalDate(invoice.getDate()).equals(today))
                .count();

        BigDecimal totalAmount = invoices.stream()
                .map(this::safeTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal paidAmount = invoices.stream()
                .map(this::safePaid)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal debtAmount = totalAmount.subtract(paidAmount);
        if (debtAmount.compareTo(BigDecimal.ZERO) < 0) {
            debtAmount = BigDecimal.ZERO;
        }

        return new PurchaseInvoiceStatsResponse(
                todayCount,
                totalAmount,
                paidAmount,
                debtAmount
        );
    }

    @Transactional(readOnly = true)
    public PurchaseInvoiceDetailPageResponse getDetail(Integer purchaseId) {
        Purchaseinvoice invoice = purchaseinvoiceRepository.findByIdWithRelations(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        List<Purchasedetail> details = purchasedetailRepository.findByPurchaseIdWithProduct(purchaseId);
        Map<Integer, String> unitNameByDetailId = importUnitNameByPurchaseDetailId(details);
        Map<Integer, BigDecimal> sellPriceByProduct = getSellPriceByProduct();

        BigDecimal subtotal = calculateSubtotal(details);
        BigDecimal additionCost = safe(invoice.getAdditionCost());
        BigDecimal discount = safe(invoice.getDiscount());
        BigDecimal totalVATInput = calculateTotalVATInput(details);
        BigDecimal totalAmount = safeTotalAmount(invoice);
        BigDecimal paid = safePaid(invoice);
        BigDecimal debtAmount = totalAmount.subtract(paid);

        if (debtAmount.compareTo(BigDecimal.ZERO) < 0) {
            debtAmount = BigDecimal.ZERO;
        }

        List<PurchaseInvoiceDetailItemResponse> items = details.stream()
                .map(detail -> toDetailItem(detail, unitNameByDetailId.get(detail.getId()), sellPriceByProduct))
                .toList();

        int totalQuantity = details.stream()
                .map(Purchasedetail::getQuantity)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        String paymentStatus = resolveDisplayStatus(invoice, totalAmount, paid);

        Supplier supplier = invoice.getSupplierID();
        Procurementplan procurementPlan = invoice.getProcurementID();

        return new PurchaseInvoiceDetailPageResponse(
                invoice.getId(),
                formatPurchaseCode(invoice.getId()),
                invoice.getDate(),
                formatInstant(invoice.getDate()),
                supplier != null ? supplier.getName() : "Không có",
                supplier != null ? supplier.getPhone() : "",
                supplier != null ? supplier.getEmail() : "",
                invoice.getEmployeeID() != null ? invoice.getEmployeeID().getName() : "Không rõ",
                procurementPlan != null ? procurementPlan.getProcurementCode() : "Không có",
                subtotal,
                additionCost,
                discount,
                totalVATInput,
                totalAmount,
                paid,
                debtAmount,
                paymentStatus,
                statusCssClass(paymentStatus),
                invoice.getVatInvoiceNumber(),
                formatLocalDate(invoice.getVatInvoiceDate()),
                formatLocalDate(invoice.getDueDate()),
                isValidForDeduction(totalAmount, paid, invoice.getDueDate()),
                invoice.getNote(),
                details.size(),
                totalQuantity,
                items
        );
    }

    @Transactional(readOnly = true)
    public PurchaseInvoicePrintPageResponse getPrintPage(Integer purchaseId) {
        Purchaseinvoice invoice = purchaseinvoiceRepository.findByIdWithRelations(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        List<Purchasedetail> details = purchasedetailRepository.findByPurchaseIdWithProduct(purchaseId);
        Map<Integer, String> unitNameByDetailId = importUnitNameByPurchaseDetailId(details);

        BigDecimal subtotal = calculateSubtotal(details);
        BigDecimal additionCost = safe(invoice.getAdditionCost());
        BigDecimal discount = safe(invoice.getDiscount());
        BigDecimal totalVATInput = calculateTotalVATInput(details);
        BigDecimal totalAmount = safeTotalAmount(invoice);

        int totalQuantity = details.stream()
                .map(Purchasedetail::getQuantity)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        Supplier supplier = invoice.getSupplierID();

        List<PurchaseInvoicePrintLineResponse> lines = details.stream()
                .map(detail -> toPrintLine(detail, unitNameByDetailId.get(detail.getId())))
                .toList();

        return new PurchaseInvoicePrintPageResponse(
                invoice.getId(),
                formatPurchaseCode(invoice.getId()),
                formatInstant(invoice.getDate()),
                invoice.getEmployeeID() != null ? invoice.getEmployeeID().getName() : "Không rõ",
                supplier != null ? supplier.getName() : "Không có",
                supplier != null ? supplier.getAddress() : "",
                totalQuantity,
                subtotal,
                additionCost,
                discount,
                totalVATInput,
                totalAmount,
                invoice.getVatInvoiceNumber(),
                formatLocalDate(invoice.getVatInvoiceDate()),
                invoice.getNote(),
                lines
        );
    }

    private PurchaseInvoicePrintLineResponse toPrintLine(Purchasedetail detail, String unitName) {
        Product product = detail.getProductID();
        BigDecimal lineTotal = safe(detail.getImportPrice())
                .multiply(BigDecimal.valueOf(detail.getQuantity() == null ? 0 : detail.getQuantity()));

        return new PurchaseInvoicePrintLineResponse(
                product != null ? product.getCode() : "",
                product != null ? product.getName() : "Không rõ",
                detail.getImportPrice(),
                detail.getQuantity(),
                unitName != null ? unitName : "—",
                lineTotal,
                detail.getVatRate(),
                detail.getVatAmount()
        );
    }

    /**
     * Đơn vị thực tế đã dùng để tạo lô hàng của mỗi dòng phiếu nhập — đọc từ {@code Batch
     * .importUnitID} (mỗi Purchasedetail luôn có đúng 1 Batch được tạo cùng transaction, xem
     * {@link #createBatchForDetail}), không suy đoán lại từ Product như gợi ý trên trang tạo phiếu.
     */
    private Map<Integer, String> importUnitNameByPurchaseDetailId(List<Purchasedetail> details) {
        List<Integer> detailIds = details.stream()
                .map(Purchasedetail::getId)
                .filter(Objects::nonNull)
                .toList();

        if (detailIds.isEmpty()) {
            return Map.of();
        }

        Map<Integer, String> result = new HashMap<>();

        for (Batch batch : batchRepository.findByPurchaseDetailIds(detailIds)) {
            if (batch.getPurchaseDetailID() != null && batch.getImportUnitID() != null) {
                result.put(batch.getPurchaseDetailID().getId(), batch.getImportUnitID().getUnitName());
            }
        }

        return result;
    }

    /**
     * Everything about a submitted request that can be resolved/validated before the invoice's
     * first save, shared by every creation and draft-edit path below.
     */
    private record PreparedInvoiceHeader(Supplier supplier, Account employee, Procurementplan procurementPlan,
                                          List<PreparedPurchaseLine> lines, BigDecimal additionCost,
                                          BigDecimal discount, BigDecimal totalAmount) {
    }

    private PreparedInvoiceHeader prepareInvoiceHeader(PurchaseInvoiceCreateRequest request, Integer currentAccountId) {
        validateCreateRequest(request);

        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp"));

        Account employee = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        // Optional cross-reference only — null means "not linked to any procurement plan", not an error.
        Procurementplan procurementPlan = request.getRequisitionId() == null
                ? null
                : procurementplanRepository.findById(request.getRequisitionId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dự trù mua hàng"));

        // Resolve every line's product + VAT rate up front (from Type, never the client) so totals can
        // be computed before the invoice's first save — see prepareLine(). priceIncludesVat is a
        // whole-invoice toggle (not per line), see PurchaseInvoiceCreateRequest's own javadoc.
        boolean priceIncludesVat = request.isPriceIncludesVat();
        List<PreparedPurchaseLine> lines = request.getDetails().stream()
                .map(item -> prepareLine(item, priceIncludesVat))
                .toList();

        BigDecimal subtotal = lines.stream()
                .map(PreparedPurchaseLine::grossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal additionCost = safe(request.getAdditionCost());
        BigDecimal discount = safe(request.getDiscount());
        // Every PreparedPurchaseLine.grossAmount is already VAT-inclusive by this point regardless
        // of priceIncludesVat (prepareLine() grosses up a pre-tax entry before this) — nothing is
        // added on top of subtotal.
        BigDecimal totalAmount = subtotal.add(additionCost).subtract(discount);

        if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Tổng tiền phiếu nhập không hợp lệ");
        }

        return new PreparedInvoiceHeader(supplier, employee, procurementPlan, lines, additionCost, discount,
                totalAmount);
    }

    /** Builds a not-yet-persisted {@link Purchaseinvoice} — always unpaid, see the field's own note below. */
    private Purchaseinvoice buildInvoiceEntity(PurchaseInvoiceCreateRequest request, PreparedInvoiceHeader header,
                                               String status, LocalDateTime approvedAt) {
        // A new import invoice always starts unpaid. Receiving goods and paying for them are separate
        // events: the money only moves when an Expense slip is raised against this invoice, and
        // applyPayment() is the single door it comes through — one path to paid/status instead of two
        // that could disagree, and why the create form asks for no amount already paid.
        BigDecimal paid = BigDecimal.ZERO;

        Purchaseinvoice invoice = new Purchaseinvoice();
        invoice.setPurchaseInvoiceCode(generatePurchaseInvoiceCode());
        invoice.setDate(Instant.now());
        invoice.setSupplierID(header.supplier());
        invoice.setEmployeeID(header.employee());
        invoice.setProcurementID(header.procurementPlan());
        invoice.setAdditionCost(header.additionCost());
        invoice.setDiscount(header.discount());
        invoice.setTotalAmount(header.totalAmount());
        invoice.setPaid(paid);
        invoice.setStatus(status);
        invoice.setReturnStatus("NONE");
        invoice.setNote(request.getNote());
        invoice.setVatInvoiceNumber(trimToNull(request.getVatInvoiceNumber()));
        invoice.setVatInvoiceDate(request.getVatInvoiceDate());
        invoice.setDueDate(request.getDueDate());
        invoice.setApprovedAt(approvedAt);
        return invoice;
    }

    /** Persists one {@link Purchasedetail} row per prepared line — no {@link Batch}, see {@link #receiveStockForInvoice}. */
    private List<Purchasedetail> persistDetailLines(Purchaseinvoice savedInvoice, List<PreparedPurchaseLine> lines) {
        List<Purchasedetail> saved = new ArrayList<>();

        for (PreparedPurchaseLine line : lines) {
            PurchaseInvoiceDetailCreateRequest item = line.item();

            Purchasedetail detail = new Purchasedetail();
            detail.setPurchaseID(savedInvoice);
            detail.setProductID(line.product());
            detail.setQuantity(item.getQuantity());
            // Luôn lưu đơn giá ĐÃ GỒM THUẾ (gross) bất kể người dùng gõ giá trước hay sau thuế lúc
            // nhập — xem prepareLine()/unitPriceGross(). KHÔNG đọc thẳng item.getImportPrice() ở
            // đây, vì khi priceIncludesVat=false, giá trị đó là giá TRƯỚC thuế, không phải giá lưu.
            detail.setImportPrice(line.unitPriceGross());
            detail.setProductionDate(item.getProductionDate());
            // Loại hàng không theo dõi hạn sử dụng (xem requiresExpirationDate) luôn lưu null, bất kể
            // client gửi gì.
            detail.setExpirationDate(requiresExpirationDate(line.product()) ? item.getExpirationDate() : null);
            detail.setLotNumber(trimToNull(item.getLotNumber()));
            detail.setVatRate(line.vatRate());
            detail.setPreTaxAmount(line.preTaxAmount());
            detail.setVatAmount(line.vatAmount());
            detail.setReturnQty(0);

            saved.add(purchasedetailRepository.save(detail));
        }

        return saved;
    }

    /**
     * The actual "hàng về kho" side effect — creates one {@link Batch} per line and refreshes the
     * supplier's reference cost price. Called exactly once per invoice, at the moment it becomes
     * official: immediately for a direct Owner creation (the Owner always has this path, regardless
     * of whether an Accountant is active — the Owner IS the approval on this path), or from
     * {@link #approvePurchaseInvoice} once the Owner approves an Accountant's submission. Never
     * called for a Nháp/Chờ duyệt row — see the class javadoc.
     */
    private void receiveStockForInvoice(Purchaseinvoice invoice, List<Purchasedetail> details) {
        Supplier supplier = invoice.getSupplierID();
        for (Purchasedetail detail : details) {
            Product product = detail.getProductID();
            createBatchForDetail(invoice, detail, product);
            upsertSupplierProductCostPrice(supplier, product, detail.getImportPrice());
        }
    }

    /**
     * The Owner's direct, one-step creation path — always available ({@link #canCreatePurchaseInvoice}
     * is always {@code true} for {@code OWNER}), regardless of whether an Accountant is active. The
     * Owner both creates and, in effect, approves in the same action: stock is received immediately
     * and {@code approvedAt} is stamped to the creation moment. An active Accountant may
     * additionally create via {@link #createPurchaseInvoiceDraft}/{@link #createPurchaseInvoiceForApproval}
     * — the two paths coexist, they are not mutually exclusive any more.
     */
    @Transactional
    public Integer createPurchaseInvoice(PurchaseInvoiceCreateRequest request, Integer currentAccountId) {
        PreparedInvoiceHeader header = prepareInvoiceHeader(request, currentAccountId);
        LocalDateTime now = LocalDateTime.now();

        Purchaseinvoice invoice = buildInvoiceEntity(request, header,
                resolveInvoiceStatus(header.totalAmount(), BigDecimal.ZERO), now);
        Purchaseinvoice savedInvoice = savePurchaseInvoiceGuardingConcurrentEdit(invoice);

        List<Purchasedetail> savedDetails = persistDetailLines(savedInvoice, header.lines());
        receiveStockForInvoice(savedInvoice, savedDetails);

        return savedInvoice.getId();
    }

    /**
     * "Lưu Nháp" — persists the header and lines so the creator (Accountant or Owner, see class
     * javadoc) can come back later, but nothing else: no stock, no cost-price refresh, no
     * {@code approvedAt}. Editable/deletable only while it stays {@link PurchaseInvoiceStatus#DRAFT}.
     */
    @Transactional
    public Integer createPurchaseInvoiceDraft(PurchaseInvoiceCreateRequest request, Integer currentAccountId) {
        PreparedInvoiceHeader header = prepareInvoiceHeader(request, currentAccountId);

        Purchaseinvoice invoice = buildInvoiceEntity(request, header, PurchaseInvoiceStatus.DRAFT, null);
        Purchaseinvoice savedInvoice = savePurchaseInvoiceGuardingConcurrentEdit(invoice);

        persistDetailLines(savedInvoice, header.lines());
        return savedInvoice.getId();
    }

    /**
     * Accountant "Nộp duyệt" straight from the create form — same as {@link #createPurchaseInvoiceDraft}
     * but lands directly on {@link PurchaseInvoiceStatus#PENDING_APPROVAL}, awaiting the Owner.
     */
    @Transactional
    public Integer createPurchaseInvoiceForApproval(PurchaseInvoiceCreateRequest request, Integer currentAccountId) {
        PreparedInvoiceHeader header = prepareInvoiceHeader(request, currentAccountId);

        Purchaseinvoice invoice = buildInvoiceEntity(request, header, PurchaseInvoiceStatus.PENDING_APPROVAL, null);
        Purchaseinvoice savedInvoice = savePurchaseInvoiceGuardingConcurrentEdit(invoice);

        persistDetailLines(savedInvoice, header.lines());
        return savedInvoice.getId();
    }

    /**
     * Shared body for editing an existing Nháp: re-validates/re-resolves the posted form exactly
     * like a fresh create, replaces every {@link Purchasedetail} line (a Draft never has a
     * {@link Batch} yet, so there is nothing to reconcile), and leaves everything else about the
     * row — code, original creator ({@code employeeID}), creation {@code date} — untouched.
     * {@code currentAccountId} is only used to confirm the editing account still exists; it does
     * NOT reassign {@code employeeID}, which stays the account that first saved the Draft.
     */
    private Purchaseinvoice applyDraftEdit(Integer purchaseId, PurchaseInvoiceCreateRequest request,
                                           Integer currentAccountId, String targetStatus) {
        Purchaseinvoice invoice = purchaseinvoiceRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        if (!PurchaseInvoiceStatus.DRAFT.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể sửa phiếu nhập đang ở trạng thái Nháp");
        }

        PreparedInvoiceHeader header = prepareInvoiceHeader(request, currentAccountId);

        invoice.setSupplierID(header.supplier());
        invoice.setProcurementID(header.procurementPlan());
        invoice.setAdditionCost(header.additionCost());
        invoice.setDiscount(header.discount());
        invoice.setTotalAmount(header.totalAmount());
        invoice.setNote(request.getNote());
        invoice.setVatInvoiceNumber(trimToNull(request.getVatInvoiceNumber()));
        invoice.setVatInvoiceDate(request.getVatInvoiceDate());
        invoice.setDueDate(request.getDueDate());
        invoice.setStatus(targetStatus);

        Purchaseinvoice savedInvoice = savePurchaseInvoiceGuardingConcurrentEdit(invoice);

        purchasedetailRepository.deleteAll(purchasedetailRepository.findByPurchaseIdWithProduct(purchaseId));
        persistDetailLines(savedInvoice, header.lines());

        return savedInvoice;
    }

    /** Accountant edits a Nháp and saves it as a Nháp again. */
    @Transactional
    public Integer updatePurchaseInvoiceDraft(Integer purchaseId, PurchaseInvoiceCreateRequest request,
                                              Integer currentAccountId) {
        return applyDraftEdit(purchaseId, request, currentAccountId, PurchaseInvoiceStatus.DRAFT).getId();
    }

    /** Accountant edits a Nháp and submits it for approval in the same action. */
    @Transactional
    public Integer submitPurchaseInvoiceDraft(Integer purchaseId, PurchaseInvoiceCreateRequest request,
                                              Integer currentAccountId) {
        return applyDraftEdit(purchaseId, request, currentAccountId, PurchaseInvoiceStatus.PENDING_APPROVAL).getId();
    }

    /**
     * Owner edits a Nháp (their own or an Accountant's — the Owner has full permission regardless of
     * origin) and approves it in the same action: unlike {@link #submitPurchaseInvoiceDraft}, there
     * is no intermediate {@link PurchaseInvoiceStatus#PENDING_APPROVAL} step, since the Owner is
     * always both creator and approver on this path (see class javadoc / {@link #createPurchaseInvoice}).
     * Stock is received here — same {@link #receiveStockForInvoice} call {@link #createPurchaseInvoice}
     * and {@link #approvePurchaseInvoice} use — the moment this row stops being a Nháp.
     */
    @Transactional
    public Integer finalizePurchaseInvoiceDraft(Integer purchaseId, PurchaseInvoiceCreateRequest request,
                                                Integer currentAccountId) {
        Purchaseinvoice invoice = purchaseinvoiceRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        if (!PurchaseInvoiceStatus.DRAFT.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể sửa phiếu nhập đang ở trạng thái Nháp");
        }

        PreparedInvoiceHeader header = prepareInvoiceHeader(request, currentAccountId);

        invoice.setSupplierID(header.supplier());
        invoice.setProcurementID(header.procurementPlan());
        invoice.setAdditionCost(header.additionCost());
        invoice.setDiscount(header.discount());
        invoice.setTotalAmount(header.totalAmount());
        invoice.setNote(request.getNote());
        invoice.setVatInvoiceNumber(trimToNull(request.getVatInvoiceNumber()));
        invoice.setVatInvoiceDate(request.getVatInvoiceDate());
        invoice.setDueDate(request.getDueDate());
        invoice.setStatus(resolveInvoiceStatus(header.totalAmount(), BigDecimal.ZERO));
        invoice.setApprovedAt(LocalDateTime.now());

        Purchaseinvoice savedInvoice = savePurchaseInvoiceGuardingConcurrentEdit(invoice);

        purchasedetailRepository.deleteAll(purchasedetailRepository.findByPurchaseIdWithProduct(purchaseId));
        List<Purchasedetail> savedDetails = persistDetailLines(savedInvoice, header.lines());
        receiveStockForInvoice(savedInvoice, savedDetails);

        return savedInvoice.getId();
    }

    /**
     * Deletes a Nháp outright — the only status this is allowed from, since nothing else (no
     * {@link Batch}, no debt, no payment) has ever been attached to it yet. Reachable by the
     * Accountant or the Owner (see class javadoc), not restricted to the row's own creator.
     */
    @Transactional
    public void deletePurchaseInvoiceDraft(Integer purchaseId) {
        Purchaseinvoice invoice = purchaseinvoiceRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        if (!PurchaseInvoiceStatus.DRAFT.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể xóa phiếu nhập đang ở trạng thái Nháp");
        }

        purchasedetailRepository.deleteAll(purchasedetailRepository.findByPurchaseIdWithProduct(purchaseId));
        purchaseinvoiceRepository.delete(invoice);
    }

    /**
     * Owner duyệt một phiếu Chờ duyệt: đây là thời điểm DUY NHẤT hàng thật sự về kho đối với luồng
     * Kế toán tạo phiếu — {@link #receiveStockForInvoice} chỉ chạy ở đây (và ở
     * {@link #createPurchaseInvoice}'s tự-duyệt khi không có Kế toán). {@code paid} luôn là 0 tại
     * thời điểm này (chưa có phiếu chi nào nối vào một phiếu chưa từng "Nợ"), nên status luôn suy ra
     * {@link PurchaseInvoiceStatus#DEBT} trừ phi tổng tiền là 0.
     */
    @Transactional
    public void approvePurchaseInvoice(Integer purchaseId) {
        Purchaseinvoice invoice = purchaseinvoiceRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        if (!PurchaseInvoiceStatus.PENDING_APPROVAL.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("Phiếu nhập không ở trạng thái chờ duyệt");
        }

        BigDecimal totalAmount = safe(invoice.getTotalAmount());
        BigDecimal paid = safe(invoice.getPaid());

        invoice.setStatus(resolveInvoiceStatus(totalAmount, paid));
        invoice.setApprovedAt(LocalDateTime.now());

        Purchaseinvoice savedInvoice = savePurchaseInvoiceGuardingConcurrentEdit(invoice);

        List<Purchasedetail> details = purchasedetailRepository.findByPurchaseIdWithProduct(purchaseId);
        receiveStockForInvoice(savedInvoice, details);
    }

    /**
     * Owner từ chối một phiếu Chờ duyệt — quay về Nháp để Kế toán sửa lại và nộp lại, KHÔNG hủy.
     * Vì chưa từng cộng kho ({@link #receiveStockForInvoice} chưa chạy), không có gì để đảo ngược.
     */
    @Transactional
    public void rejectPurchaseInvoice(Integer purchaseId, String reason) {
        Purchaseinvoice invoice = purchaseinvoiceRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        if (!PurchaseInvoiceStatus.PENDING_APPROVAL.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("Phiếu nhập không ở trạng thái chờ duyệt");
        }

        invoice.setStatus(PurchaseInvoiceStatus.DRAFT);
        invoice.setNote(appendNote(invoice.getNote(),
                "Bị từ chối" + (trimToNull(reason) != null ? ": " + reason.trim() : "")));

        savePurchaseInvoiceGuardingConcurrentEdit(invoice);
    }

    /**
     * Rehydrates an existing Nháp's header + lines into the same request DTO the create form binds
     * to, so "Sửa phiếu nhập" is literally the create form pre-filled — same fields, same
     * validation, same JS. Only ever called for a {@link PurchaseInvoiceStatus#DRAFT} row.
     */
    @Transactional(readOnly = true)
    public PurchaseInvoiceCreateRequest getDraftEditForm(Integer purchaseId) {
        Purchaseinvoice invoice = purchaseinvoiceRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        if (!PurchaseInvoiceStatus.DRAFT.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể sửa phiếu nhập đang ở trạng thái Nháp");
        }

        PurchaseInvoiceCreateRequest form = new PurchaseInvoiceCreateRequest();
        form.setSupplierId(invoice.getSupplierID() != null ? invoice.getSupplierID().getId() : null);
        form.setRequisitionId(invoice.getProcurementID() != null ? invoice.getProcurementID().getId() : null);
        form.setAdditionCost(invoice.getAdditionCost());
        form.setDiscount(invoice.getDiscount());
        form.setNote(invoice.getNote());
        form.setVatInvoiceNumber(invoice.getVatInvoiceNumber());
        form.setVatInvoiceDate(invoice.getVatInvoiceDate());
        form.setDueDate(invoice.getDueDate());

        List<PurchaseInvoiceDetailCreateRequest> details = new ArrayList<>();
        for (Purchasedetail detail : purchasedetailRepository.findByPurchaseIdWithProduct(purchaseId)) {
            PurchaseInvoiceDetailCreateRequest item = new PurchaseInvoiceDetailCreateRequest();
            item.setProductId(detail.getProductID() != null ? detail.getProductID().getProductID() : null);
            item.setQuantity(detail.getQuantity());
            item.setImportPrice(detail.getImportPrice());
            item.setProductionDate(detail.getProductionDate());
            item.setExpirationDate(detail.getExpirationDate());
            item.setLotNumber(detail.getLotNumber());
            item.setVatRate(detail.getVatRate());
            details.add(item);
        }
        form.setDetails(details);

        return form;
    }

    /**
     * Hủy một phiếu nhập đã lập sai — chỉ là sửa dữ liệu nội bộ (KHÔNG phải hóa đơn đã xuất, không
     * chịu ràng buộc luật cấm hủy hóa đơn), nên được phép đảo ngược hoàn toàn tồn kho đã cộng vào
     * khi tạo phiếu. Chỉ cho phép hủy nếu CHƯA có bất kỳ lô hàng nào của phiếu bị đụng tới (bán ra,
     * điều chỉnh kho, trả hàng...) kể từ lúc tạo — nếu không, số liệu tồn kho/công nợ đã lan ra
     * những giao dịch khác và việc đảo ngược không còn an toàn.
     */
    @Transactional
    public void cancelPurchaseInvoice(Integer purchaseId, String reason) {
        Purchaseinvoice invoice = purchaseinvoiceRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        if (PurchaseInvoiceStatus.CANCELLED.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("Phiếu nhập đã bị hủy trước đó");
        }
        if (PurchaseInvoiceStatus.DRAFT.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("Phiếu nhập đang ở trạng thái Nháp — vui lòng xóa thay vì hủy");
        }
        if (PurchaseInvoiceStatus.PENDING_APPROVAL.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("Phiếu nhập đang chờ duyệt — vui lòng từ chối thay vì hủy");
        }

        List<Purchasedetail> details = purchasedetailRepository.findByPurchaseIdWithProduct(purchaseId);
        List<Integer> detailIds = details.stream()
                .map(Purchasedetail::getId)
                .filter(Objects::nonNull)
                .toList();

        List<Batch> batches = detailIds.isEmpty() ? List.of() : batchRepository.findByPurchaseDetailIds(detailIds);

        List<String> touchedProductNames = batches.stream()
                .filter(this::batchWasTouchedSinceImport)
                .map(batch -> batch.getProductID() != null ? batch.getProductID().getName() : batch.getBatchCode())
                .toList();

        if (!touchedProductNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "Không thể hủy phiếu nhập vì lô hàng của các sản phẩm sau đã phát sinh giao dịch "
                            + "(bán ra, điều chỉnh kho, trả hàng...): " + String.join(", ", touchedProductNames));
        }

        for (Batch batch : batches) {
            batch.setStorageQuantity(0);
            batch.setStatus(false);

            batch.setNote(
                    appendNote(
                            batch.getNote(),
                            "Đã hủy do phiếu nhập "
                                    + formatPurchaseCode(
                                    invoice.getId()
                            )
                                    + " bị hủy"
                    )
            );

            batchRepository.save(batch);

            scheduleInventoryAlert(batch);
        }

        invoice.setStatus(PurchaseInvoiceStatus.CANCELLED);
        invoice.setNote(appendNote(invoice.getNote(), "Đã hủy" + (trimToNull(reason) != null ? ": " + reason.trim() : "")));

        savePurchaseInvoiceGuardingConcurrentEdit(invoice);
    }

    /** True if a batch's current stock no longer matches what was originally imported for it. */
    private boolean batchWasTouchedSinceImport(Batch batch) {
        int originalBaseQuantity = calculateBaseQuantity(batch.getImportQtyInUnit(), batch.getImportUnitID());
        int currentQuantity = batch.getStorageQuantity() == null ? 0 : batch.getStorageQuantity();
        return currentQuantity != originalBaseQuantity;
    }

    private String appendNote(String existingNote, String addition) {
        String base = existingNote == null ? "" : existingNote.trim();
        return base.isEmpty() ? addition : base + " | " + addition;
    }

    /**
     * One purchase line with its product and VAT breakdown resolved, ready to price and persist.
     * {@code unitPriceGross} is what actually gets stored ({@code Purchasedetail.importPrice}) —
     * always gross, regardless of how the line's price was typed; see {@link #prepareLine}.
     */
    private record PreparedPurchaseLine(PurchaseInvoiceDetailCreateRequest item, Product product,
                                        BigDecimal unitPriceGross, BigDecimal grossAmount, BigDecimal vatRate,
                                        BigDecimal preTaxAmount, BigDecimal vatAmount) {}

    /**
     * @param priceIncludesVat whole-invoice toggle (see {@code PurchaseInvoiceCreateRequest
     *                         #priceIncludesVat}) — {@code true} means {@code item.getImportPrice()}
     *                         is already gross (the long-standing default behavior); {@code false}
     *                         means it's a pre-tax "giá trước thuế" that must be grossed up here
     *                         before anything downstream (persistence, totals) ever sees it, so the
     *                         rest of the app can keep assuming every stored price is gross.
     */
    private PreparedPurchaseLine prepareLine(PurchaseInvoiceDetailCreateRequest item, boolean priceIncludesVat) {
        Product product = productRepository.findById(item.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm: " + item.getProductId()));

        validateExpirationForType(product, item);

        BigDecimal vatRate = resolvePurchaseVatRate(product);
        BigDecimal enteredUnitPrice = safe(item.getImportPrice());
        BigDecimal unitPriceGross = priceIncludesVat
                ? enteredUnitPrice.setScale(2, RoundingMode.HALF_UP)
                : grossUpUnitPrice(enteredUnitPrice, vatRate);

        int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
        BigDecimal grossAmount = unitPriceGross.multiply(BigDecimal.valueOf(quantity));
        BigDecimal preTaxAmount = calculateLinePreTaxAmount(grossAmount, vatRate);
        BigDecimal vatAmount = grossAmount.subtract(preTaxAmount);

        return new PreparedPurchaseLine(item, product, unitPriceGross, grossAmount, vatRate, preTaxAmount, vatAmount);
    }

    /** Reverse of {@link #calculateLinePreTaxAmount}: grosses up a pre-tax unit price by {@code vatRate}. */
    private BigDecimal grossUpUnitPrice(BigDecimal preTaxUnitPrice, BigDecimal vatRate) {
        BigDecimal rate = safe(vatRate);
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            return preTaxUnitPrice.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal multiplier = BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        return preTaxUnitPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Whether a product's batch needs {@code expirationDate} at all. Every type tracks it except
     * two "thiết bị y tế" sub-types: "(máy)" (a durable instrument, tracked by warranty rather than
     * shelf life) and "(không hạn)" (explicitly labelled as never expiring).
     */
    private boolean requiresExpirationDate(Product product) {
        Type type = product.getTypeID();
        if (type == null || !SORT_MEDICAL_DEVICE.equals(normalize(type.getSortType()))) {
            return true;
        }
        String name = normalize(type.getName());
        return !(name.contains(DEVICE_MACHINE_MARK) || name.contains(DEVICE_NO_EXPIRY_MARK));
    }

    /**
     * Type-aware counterpart of the old blanket check in {@link #validateCreateRequest}: a product
     * whose type doesn't track expiration (see {@link #requiresExpirationDate}) is exempt entirely
     * — {@link #persistDetailLines} discards {@code expirationDate} for such a product regardless
     * of what the client posts. There is no manual override for a product whose type DOES track
     * expiration; the date is always required, full stop.
     */
    private void validateExpirationForType(Product product, PurchaseInvoiceDetailCreateRequest item) {
        if (!requiresExpirationDate(product)) {
            return;
        }

        if (item.getExpirationDate() == null) {
            throw new IllegalArgumentException("Vui lòng nhập hạn sử dụng cho tất cả sản phẩm");
        }

        if (!item.getExpirationDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Hạn sử dụng phải lớn hơn ngày hiện tại");
        }

        if (item.getProductionDate() != null && item.getExpirationDate().isBefore(item.getProductionDate())) {
            throw new IllegalArgumentException("Hạn sử dụng không được trước ngày sản xuất");
        }
    }

    /**
     * "Thuế suất VAT" của phiếu nhập luôn khớp {@code Type.defaultVATRate} của sản phẩm — không dùng
     * {@code Product.vatRateOverride} (khác với bên bán hàng) và không tin giá trị client gửi lên;
     * server luôn tự tính lại theo Type để đảm bảo khớp đúng loại hàng hóa đã đăng ký.
     */
    private BigDecimal resolvePurchaseVatRate(Product product) {
        Type type = product.getTypeID();
        if (type == null || type.getDefaultVATRate() == null) {
            throw new IllegalArgumentException("Sản phẩm \"" + (product.getName() != null ? product.getName() : "")
                    + "\" chưa có loại hàng hoặc thuế suất VAT mặc định");
        }
        return type.getDefaultVATRate();
    }

    /** Reverse-splits a VAT-inclusive gross amount into its pre-tax portion — gross ÷ (1 + vatRate/100). */
    private BigDecimal calculateLinePreTaxAmount(BigDecimal grossAmount, BigDecimal vatRate) {
        BigDecimal rate = safe(vatRate);
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            return grossAmount.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal divisor = BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        return grossAmount.divide(divisor, 2, RoundingMode.HALF_UP);
    }

    /**
     * Per-product VAT-rate lookup for the Purchase Invoice create form's read-only "Thuế suất VAT"
     * field: always {@code Type.defaultVATRate} (ignores {@code Product.vatRateOverride} — that's a
     * sale-side-only concept, see {@code InvoiceService.resolveVatRateSnapshot}). Purely a display
     * value; {@link #resolvePurchaseVatRate} is what the server actually trusts on save.
     */
    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> getVatRateByProduct() {
        Map<Integer, BigDecimal> result = new HashMap<>();

        for (Object[] row : productRepository.findVatRateSuggestions()) {
            Integer productId = (Integer) row[0];
            BigDecimal defaultVATRate = (BigDecimal) row[2];
            result.put(productId, defaultVATRate != null ? defaultVATRate : BigDecimal.ZERO);
        }

        return result;
    }

    /**
     * Resolves each product's single "đơn vị nhập" (import unit) — same priority
     * {@link #resolveImportUnitOrNull} uses at save time: isDefault &gt; isBaseUnit &gt; lowest id.
     * Backs both {@link #getImportUnitNameByProduct()} and {@link #getSellPriceByProduct()} so the
     * "Đơn vị" column and the "Giá bán" reference line always agree on which unit they're about —
     * the sell price of a box shouldn't be shown next to "Đơn vị: Hộp" while secretly meaning a
     * single viên.
     */
    private Map<Integer, Productunit> resolveImportUnitByProduct() {
        Map<Integer, List<Productunit>> unitsByProduct = new HashMap<>();

        for (Productunit unit : productunitRepository.findAll()) {
            if (unit.getProductID() == null || Boolean.FALSE.equals(unit.getIsActive())) {
                continue;
            }
            unitsByProduct.computeIfAbsent(unit.getProductID().getProductID(), id -> new ArrayList<>()).add(unit);
        }

        Map<Integer, Productunit> result = new HashMap<>();

        unitsByProduct.forEach((productId, units) -> units.stream()
                .min(Comparator
                        .comparingInt(this::importUnitPriority)
                        .thenComparing(unit -> unit.getId() == null ? Integer.MAX_VALUE : unit.getId()))
                .ifPresent(unit -> result.put(productId, unit)));

        return result;
    }

    /**
     * Per-product default import-unit name for the Purchase Invoice create form's "Đơn vị" column —
     * purely informational, showing which unit {@link #resolveImportUnit(Product)} will actually use
     * for that product's "Số lượng", so the pharmacist can see what unit their quantity is in before
     * saving.
     */
    @Transactional(readOnly = true)
    public Map<Integer, String> getImportUnitNameByProduct() {
        Map<Integer, String> result = new HashMap<>();
        resolveImportUnitByProduct().forEach((productId, unit) -> result.put(productId, unit.getUnitName()));
        return result;
    }

    /**
     * Per-product sell price of that same "đơn vị nhập" (import unit — the one shown in the "Đơn vị"
     * column, not necessarily the base unit) — shown as a small read-only reference line under the
     * product name on the Purchase Invoice create form (and on the Detail screen's batch table).
     * Purely informational; nothing on this form writes back to {@code Productunit.sellPrice} —
     * that stays Price Settings' job alone.
     */
    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> getSellPriceByProduct() {
        Map<Integer, BigDecimal> result = new HashMap<>();
        resolveImportUnitByProduct().forEach((productId, unit) -> result.put(productId, unit.getSellPrice()));
        return result;
    }

    /**
     * "Giá nhập" của phiếu nhập luôn phản ánh giá thực tế của lần giao dịch (thực hiện ngoài hệ
     * thống), nên mỗi lần lưu phiếu sẽ ghi đè {@code SupplierProduct.costPrice} bằng giá vừa nhập
     * — không chỉ cập nhật khi trước đó chưa có — để costPrice luôn là "giá nhập mới nhất" dùng
     * tham khảo khi lập dự trù mua hàng. Tạo mới liên kết (supplier, product) nếu đây là lần đầu
     * nhập sản phẩm này từ nhà cung cấp này.
     */
    private void upsertSupplierProductCostPrice(Supplier supplier, Product product, BigDecimal importPrice) {
        Supplierproduct supplierProduct = supplierproductRepository
                .findBySupplierID_IdAndProductID_ProductID(supplier.getId(), product.getProductID())
                .orElseGet(() -> {
                    Supplierproduct created = new Supplierproduct();
                    created.setSupplierID(supplier);
                    created.setProductID(product);
                    created.setIsPreferred(false);
                    created.setIsActive(true);
                    return created;
                });

        supplierProduct.setCostPrice(importPrice);
        supplierproductRepository.save(supplierProduct);
    }

    /** Creates the Batch (stock) row for one just-saved Purchasedetail — see class javadoc. */
    private void createBatchForDetail(Purchaseinvoice invoice, Purchasedetail detail, Product product) {
        Productunit importUnit = resolveImportUnit(product);

        BigDecimal importPrice = safe(detail.getImportPrice());
        BigDecimal importPricePerBase = calculateImportPricePerBase(importPrice, importUnit);
        int storageQuantity = calculateBaseQuantity(detail.getQuantity(), importUnit);

        Batch batch = new Batch();
        batch.setBatchCode(generateBatchCode(invoice.getId(), detail.getId()));
        batch.setBatchName(generateBatchName(product, detail));
        batch.setProductID(product);
        batch.setPurchaseDetailID(detail);
        batch.setStorageQuantity(storageQuantity);
        batch.setImportUnitID(importUnit);
        batch.setImportQtyInUnit(detail.getQuantity());
        batch.setImportPrice(importPrice);
        batch.setImportPricePerBase(importPricePerBase);
        batch.setImportDate(invoice.getDate());
        batch.setProductionDate(detail.getProductionDate());
        batch.setExpirationDate(detail.getExpirationDate());
        batch.setLotNumber(detail.getLotNumber());
        batch.setStatus(true);

        batch.setNote(
                "Tạo từ phiếu nhập "
                        + formatPurchaseCode(
                        invoice.getId()
                )
        );

        Batch savedBatch =
                batchRepository.save(batch);

        scheduleInventoryAlert(savedBatch);
    }

    /**
     * Sau khi transaction phiếu nhập commit:
     *
     * - Kiểm tra lại tổng tồn sản phẩm.
     * - Kiểm tra hạn dùng của lô vừa tạo hoặc bị hủy.
     */
    private void scheduleInventoryAlert(
            Batch batch
    ) {
        /*
         * Có thể null trong các Unit Test cũ vì test không
         * thực hiện setter injection.
         */
        if (inventoryAlertEventService == null
                || batch == null
                || batch.getProductID() == null) {
            return;
        }

        inventoryAlertEventService
                .checkBatchAfterCommit(
                        batch.getProductID()
                                .getProductID(),
                        batch.getId()
                );
    }

    private Productunit resolveImportUnit(Product product) {
        return resolveImportUnitOrNull(product)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Sản phẩm " + (product != null ? product.getName() : "") + " chưa có đơn vị nhập trong ProductUnit"
                ));
    }

    private Optional<Productunit> resolveImportUnitOrNull(Product product) {
        if (product == null || product.getProductID() == null) {
            return Optional.empty();
        }

        return productunitRepository.findByProductId(product.getProductID())
                .stream()
                .filter(unit -> !Boolean.FALSE.equals(unit.getIsActive()))
                .sorted(Comparator
                        .comparingInt(this::importUnitPriority)
                        .thenComparing(unit -> unit.getId() == null ? Integer.MAX_VALUE : unit.getId()))
                .findFirst();
    }

    private int importUnitPriority(Productunit unit) {
        if (Boolean.TRUE.equals(unit.getIsDefault())) {
            return 0;
        }

        if (Boolean.TRUE.equals(unit.getIsBaseUnit())) {
            return 1;
        }

        return 2;
    }

    private BigDecimal calculateImportPricePerBase(BigDecimal importPrice, Productunit importUnit) {
        if (importUnit == null
                || importUnit.getRatio() == null
                || importUnit.getRatio().compareTo(BigDecimal.ZERO) <= 0) {
            return importPrice;
        }

        return importPrice.divide(importUnit.getRatio(), 2, RoundingMode.HALF_UP);
    }

    private int calculateBaseQuantity(Integer importQuantity, Productunit importUnit) {
        BigDecimal quantity = BigDecimal.valueOf(importQuantity == null ? 0 : importQuantity);

        BigDecimal ratio = BigDecimal.ONE;

        if (importUnit != null
                && importUnit.getRatio() != null
                && importUnit.getRatio().compareTo(BigDecimal.ZERO) > 0) {
            ratio = importUnit.getRatio();
        }

        return quantity
                .multiply(ratio)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    private String generateBatchCode(Integer purchaseId, Integer purchaseDetailId) {
        return "BATCH-" + String.format("%06d", purchaseId == null ? 0 : purchaseId)
                + "-" + String.format("%03d", purchaseDetailId == null ? 0 : purchaseDetailId);
    }

    private String generateBatchName(Product product, Purchasedetail detail) {
        String productName = product != null && product.getName() != null
                ? product.getName()
                : "Sản phẩm";

        String lot = detail.getLotNumber() != null && !detail.getLotNumber().isBlank()
                ? detail.getLotNumber()
                : "Không số lô";

        String name = productName + " - " + lot;

        return name.length() > 50 ? name.substring(0, 50) : name;
    }

    /**
     * "Giá nhập" gợi ý cho trang tạo phiếu nhập, theo từng cặp (nhà cung cấp, sản phẩm) đã từng
     * nhập — lấy từ {@code SupplierProduct.costPrice} (giá nhập gần nhất được lưu lại, xem
     * {@link #upsertSupplierProductCostPrice}). Bake sẵn thành model attribute, JS đọc trực tiếp
     * thay vì gọi AJAX.
     */
    @Transactional(readOnly = true)
    public Map<Integer, Map<Integer, BigDecimal>> buildCostPriceBySupplierAndProduct() {
        Map<Integer, Map<Integer, BigDecimal>> result = new LinkedHashMap<>();

        for (Supplierproduct supplierProduct : supplierproductRepository.findAll()) {
            if (supplierProduct.getSupplierID() == null
                    || supplierProduct.getProductID() == null
                    || supplierProduct.getCostPrice() == null) {
                continue;
            }

            result.computeIfAbsent(supplierProduct.getSupplierID().getId(), id -> new LinkedHashMap<>())
                    .put(supplierProduct.getProductID().getProductID(), supplierProduct.getCostPrice());
        }

        return result;
    }

    @Transactional(readOnly = true)
    public List<Supplier> listSuppliers() {
        return supplierRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(supplier -> supplier.getName() == null ? "" : supplier.getName()))
                .toList();
    }

    /** (id, name) projection of {@link #listSuppliers()} for the create form's supplier search field. */
    @Transactional(readOnly = true)
    public List<SupplierOptionResponse> listSupplierOptions() {
        return listSuppliers().stream()
                .map(supplier -> new SupplierOptionResponse(supplier.getId(), supplier.getName()))
                .toList();
    }

    /** Options for the create form's optional "Dự trù mua hàng" selector — every plan, newest first. */
    @Transactional(readOnly = true)
    public List<Procurementplan> listProcurementPlans() {
        return procurementplanRepository.findAll(Sort.by(Sort.Direction.DESC, "date"));
    }

    /**
     * "Lấy data" từ dự trù mua hàng cho phiếu nhập — chỉ trả về những dòng dự trù đã gán đúng nhà
     * cung cấp đang chọn (BA: liên kết dự trù là optional, chỉ để đối chiếu/gợi ý số lượng-giá, không
     * bắt buộc và không validate lại số lượng thực nhập so với requestedQuantity).
     */
    @Transactional(readOnly = true)
    public List<ProcurementPlanDetailOptionResponse> getProcurementPlanDetailsForSupplier(Integer procurementId,
                                                                                          Integer supplierId) {
        if (procurementId == null || supplierId == null) {
            return List.of();
        }

        return procurementplandetailRepository.findByProcurementID_IdWithRelations(procurementId).stream()
                .filter(detail -> detail.getSupplierID() != null && supplierId.equals(detail.getSupplierID().getId()))
                .map(this::toProcurementPlanDetailOption)
                .toList();
    }

    /**
     * "estimatedPrice" trên dự trù là TỔNG giá dự kiến cho requestedQuantity, đã bao gồm VAT (theo
     * xác nhận của BA) — chia đều cho requestedQuantity ra "unitPrice" để gợi ý thẳng vào "Đơn giá"
     * của phiếu nhập. Gợi ý này giả định phiếu nhập vẫn để {@code priceIncludesVat = true} (mặc
     * định) — nếu người dùng chuyển sang nhập giá trước thuế, con số gợi ý này (vẫn là giá đã gồm
     * VAT) sẽ bị hiểu sai thành giá trước thuế; xem {@link #prepareLine}.
     */
    private ProcurementPlanDetailOptionResponse toProcurementPlanDetailOption(Procurementplandetail detail) {
        Product product = detail.getProductID();
        Integer quantity = detail.getRequestedQuantity();
        BigDecimal estimatedPrice = detail.getEstimatedPrice();

        BigDecimal unitPrice = (estimatedPrice != null && quantity != null && quantity > 0)
                ? estimatedPrice.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP)
                : null;

        return new ProcurementPlanDetailOptionResponse(
                product != null ? product.getProductID() : null,
                product != null ? product.getName() : "",
                quantity,
                detail.getUnit(),
                estimatedPrice,
                unitPrice
        );
    }

    /** The fixed set of statuses a PurchaseInvoice can be in, for the filter dropdown. */
    public List<String> listPaymentStatuses() {
        return PurchaseInvoiceStatus.ALL;
    }

    /**
     * (id, name) only — see {@link ProductOptionResponse} javadoc for why the raw {@code Product}
     * entity can't be used here (this list is embedded into inline JavaScript on the create page).
     */
    @Transactional(readOnly = true)
    public List<ProductOptionResponse> listProducts() {
        return productRepository.findAllWithRelations()
                .stream()
                .filter(product -> Boolean.TRUE.equals(product.getStatus()))
                .sorted(Comparator.comparing(product -> product.getName() == null ? "" : product.getName()))
                .map(product -> new ProductOptionResponse(product.getProductID(), product.getName()))
                .toList();
    }

    /**
     * Product IDs whose {@code Type} doesn't track an expiration date at all: durable "thiết bị y
     * tế (máy)" instruments (tracked by warranty, not shelf life) and goods explicitly labelled
     * "(không hạn)". Feeds the Purchase Invoice create form's "Không có hạn sử dụng" checkbox — for
     * these products it is forced on automatically instead of left for the pharmacist to remember
     * to tick. {@link #requiresExpirationDate} is the same rule re-checked server-side on save,
     * regardless of what the client posts.
     */
    @Transactional(readOnly = true)
    public Set<Integer> getNoExpirationProductIds() {
        return productRepository.findAllWithRelations().stream()
                .filter(product -> !requiresExpirationDate(product))
                .map(Product::getProductID)
                .collect(Collectors.toSet());
    }

    private void validateCreateRequest(PurchaseInvoiceCreateRequest request) {
        if (request.getVatInvoiceNumber() == null || request.getVatInvoiceNumber().isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập số hóa đơn GTGT");
        }

        if (request.getVatInvoiceDate() == null) {
            throw new IllegalArgumentException("Vui lòng nhập ngày hóa đơn GTGT");
        }

        if (request.getDetails() == null || request.getDetails().isEmpty()) {
            throw new IllegalArgumentException("Phiếu nhập phải có ít nhất một sản phẩm");
        }

        for (PurchaseInvoiceDetailCreateRequest detail : request.getDetails()) {
            if (detail.getProductId() == null) {
                throw new IllegalArgumentException("Vui lòng chọn sản phẩm");
            }

            if (detail.getQuantity() == null || detail.getQuantity() <= 0) {
                throw new IllegalArgumentException("Số lượng nhập phải lớn hơn 0");
            }

            if (detail.getImportPrice() == null || detail.getImportPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Đơn giá phải lớn hơn 0");
            }

            if (detail.getLotNumber() == null || detail.getLotNumber().isBlank()) {
                throw new IllegalArgumentException("Vui lòng nhập số lô cho tất cả sản phẩm");
            }

            // The type-aware expiration-date rule needs the resolved Product (see
            // requiresExpirationDate) — checked per-line in prepareLine() instead of here, right
            // after each line's Product is looked up, to avoid fetching it twice.
        }
    }

    private PurchaseInvoiceListItemResponse toListItem(Purchaseinvoice invoice, List<Purchasedetail> details) {
        BigDecimal totalAmount = safeTotalAmount(invoice);
        BigDecimal paid = safePaid(invoice);
        BigDecimal debtAmount = totalAmount.subtract(paid);

        if (debtAmount.compareTo(BigDecimal.ZERO) < 0) {
            debtAmount = BigDecimal.ZERO;
        }

        String paymentStatus = resolveDisplayStatus(invoice, totalAmount, paid);

        return new PurchaseInvoiceListItemResponse(
                invoice.getId(),
                formatPurchaseCode(invoice.getId()),
                invoice.getDate(),
                formatInstant(invoice.getDate()),
                invoice.getSupplierID() != null ? invoice.getSupplierID().getName() : "Không có",
                invoice.getEmployeeID() != null ? invoice.getEmployeeID().getName() : "Không rõ",
                details.size(),
                totalAmount,
                paid,
                debtAmount,
                paymentStatus,
                statusCssClass(paymentStatus),
                isValidForDeduction(totalAmount, paid, invoice.getDueDate())
        );
    }

    private PurchaseInvoiceDetailItemResponse toDetailItem(Purchasedetail detail, String unitName,
                                                           Map<Integer, BigDecimal> sellPriceByProduct) {
        Product product = detail.getProductID();
        BigDecimal lineTotal = safe(detail.getImportPrice())
                .multiply(BigDecimal.valueOf(detail.getQuantity() == null ? 0 : detail.getQuantity()));

        return new PurchaseInvoiceDetailItemResponse(
                product != null ? product.getProductID() : null,
                product != null ? product.getName() : "Không rõ",
                detail.getLotNumber(),
                detail.getProductionDate(),
                formatLocalDate(detail.getProductionDate()),
                detail.getExpirationDate(),
                formatExpirationDate(detail.getExpirationDate()),
                detail.getQuantity(),
                unitName != null ? unitName : "—",
                detail.getImportPrice(),
                lineTotal,
                detail.getVatRate(),
                detail.getPreTaxAmount(),
                detail.getVatAmount(),
                product != null ? sellPriceByProduct.get(product.getProductID()) : null
        );
    }

    // --- expandable search fields (Mã phiếu / Nhà cung cấp / Sản phẩm) — ANDed when combined ---

    private boolean matchesCode(Purchaseinvoice invoice, String normalizedCode) {
        return normalizedCode.isBlank() || containsNormalized(formatPurchaseCode(invoice.getId()), normalizedCode);
    }

    private boolean matchesSupplier(Purchaseinvoice invoice, String normalizedSupplier) {
        return normalizedSupplier.isBlank()
                || containsNormalized(invoice.getSupplierID() != null ? invoice.getSupplierID().getName() : null,
                        normalizedSupplier);
    }

    private boolean matchesProduct(List<Purchasedetail> details, String normalizedProduct) {
        if (normalizedProduct.isBlank()) {
            return true;
        }
        return details.stream().anyMatch(detail -> {
            Product product = detail.getProductID();
            return product != null
                    && (containsNormalized(String.valueOf(product.getProductID()), normalizedProduct)
                    || containsNormalized(product.getName(), normalizedProduct)
                    || containsNormalized(product.getCode(), normalizedProduct)
                    || containsNormalized(product.getBarcode(), normalizedProduct));
        });
    }

    private boolean matchesDate(Purchaseinvoice invoice, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }

        if (invoice.getDate() == null) {
            return false;
        }

        LocalDate date = toLocalDate(invoice.getDate());

        if (from != null && date.isBefore(from)) {
            return false;
        }

        return to == null || !date.isAfter(to);
    }

    private BigDecimal calculateSubtotal(List<Purchasedetail> details) {
        return details.stream()
                .map(detail -> safe(detail.getImportPrice())
                        .multiply(BigDecimal.valueOf(detail.getQuantity() == null ? 0 : detail.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Total input VAT for display — summed live from {@link Purchasedetail#getVatAmount()} per line. */
    private BigDecimal calculateTotalVATInput(List<Purchasedetail> details) {
        return details.stream()
                .map(detail -> safe(detail.getVatAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal safeTotalAmount(Purchaseinvoice invoice) {
        return safe(invoice.getTotalAmount());
    }

    private BigDecimal safePaid(Purchaseinvoice invoice) {
        return safe(invoice.getPaid());
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String resolvePaymentStatus(BigDecimal totalAmount, BigDecimal paid) {
        return resolveInvoiceStatus(totalAmount, paid);
    }

    /**
     * Trạng thái hiển thị cho danh sách/chi tiết — <strong>lấy đúng giá trị đang lưu trong DB</strong>,
     * không suy lại từ {@code paid}/{@code totalAmount}. Đọc thẳng cột giúp màn hình phản ánh đúng dữ
     * liệu thật nếu ai đó sửa {@code status} trực tiếp trong DB, thay vì luôn tự suy ra "Nợ"/"Hoàn
     * thành" và che giấu sai lệch.
     *
     * <p><strong>Bất biến này phải được chủ động duy trì</strong>: từ khi phiếu chi trả nợ nhà cung
     * cấp được nối vào ({@link #applyPayment}), {@code paid} có thể tăng/giảm sau khi tạo, nên
     * <em>mọi</em> chỗ ghi {@code paid} bắt buộc phải ghi lại {@code status} bằng
     * {@link #resolveInvoiceStatus} trong cùng một transaction — quên một chỗ là màn hình lại hiển
     * thị "Nợ" trên một phiếu đã trả đủ.</p>
     *
     * <p>Chỉ khi cột rỗng (dòng cũ/thiếu dữ liệu) mới suy lại từ tiền để còn có gì đó mà hiển thị.
     * Giá trị lạ được trả về nguyên văn và {@link #statusCssClass(String)} sẽ tô nó thành
     * "không xác định".</p>
     */
    private String resolveDisplayStatus(Purchaseinvoice invoice, BigDecimal totalAmount, BigDecimal paid) {
        String storedStatus = invoice.getStatus() == null ? null : invoice.getStatus().trim();
        if (storedStatus == null || storedStatus.isEmpty()) {
            return resolveInvoiceStatus(totalAmount, paid);
        }
        return storedStatus;
    }

    private static final BigDecimal VAT_DEDUCTION_THRESHOLD = BigDecimal.valueOf(5_000_000);

    /**
     * Whether this invoice's input VAT may be deducted right now — the rule below, applied to a
     * whole invoice, plus the obvious precondition that a cancelled invoice deducts nothing.
     *
     * <p>Exposed for {@code TaxperiodsnapshotService}, which needs exactly this question when it
     * totals a period's input VAT. It lives here rather than there so the Điều 26 rule stays with
     * the entity that owns it, the same reasoning that keeps
     * {@link #applyPayment(Integer, BigDecimal)} on this side. Note the answer is <em>time
     * dependent</em> (an unpaid invoice stops being deductible once its {@code dueDate} passes),
     * which is precisely why a tax period is snapshotted rather than recomputed forever.</p>
     */
    public boolean isDeductible(Purchaseinvoice invoice) {
        if (invoice == null || PurchaseInvoiceStatus.CANCELLED.equals(invoice.getStatus())) {
            return false;
        }
        return isValidForDeduction(invoice.getTotalAmount(), invoice.getPaid(), invoice.getDueDate());
    }

    /**
     * Điều 26 Nghị định 181/2025/NĐ-CP: hóa đơn nhập ≥5 triệu đồng chưa thanh toán vẫn được TẠM
     * khấu trừ GTGT đầu vào cho tới hạn thanh toán ghi trên thỏa thuận với NCC ({@code dueDate}).
     * Quá hạn đó mà vẫn chưa thanh toán đủ (không có chứng từ thanh toán không dùng tiền mặt) thì
     * không còn hợp lệ để khấu trừ nữa — phải kê khai điều chỉnh giảm. Tính lại mỗi lần đọc (không
     * ghi ngược vào DB) để luôn phản ánh đúng thời điểm hiện tại, kể cả khi không có thao tác ghi
     * nào xảy ra giữa lúc tạo phiếu và lúc hạn thanh toán trôi qua.
     */
    private boolean isValidForDeduction(BigDecimal totalAmount, BigDecimal paid, LocalDate dueDate) {
        if (safe(totalAmount).compareTo(VAT_DEDUCTION_THRESHOLD) < 0) {
            return true;
        }

        if (safe(paid).compareTo(safe(totalAmount)) >= 0) {
            return true;
        }

        return dueDate == null || !dueDate.isBefore(LocalDate.now());
    }

    // ------------------------------------------------------------------ payment from an Expense

    /**
     * Phiếu nhập còn nợ, cho ô chọn "chứng từ tham chiếu" của phiếu chi trả nợ NCC. Phiếu đã hủy bị
     * loại vì không còn nghĩa vụ trả tiền. Trả về entity để bên gọi tự tính phần đã cam kết bởi các
     * phiếu chi đang dở (kiến thức đó thuộc về {@code ExpenseService}, không thuộc về phiếu nhập).
     */
    @Transactional(readOnly = true)
    public List<Purchaseinvoice> findPayableInvoices() {
        return purchaseinvoiceRepository.findAllWithRelations().stream()
                .filter(invoice -> !PurchaseInvoiceStatus.CANCELLED.equals(invoice.getStatus()))
                .filter(invoice -> remainingDebt(invoice).compareTo(BigDecimal.ZERO) > 0)
                .toList();
    }

    /** Số tiền còn nợ nhà cung cấp trên một phiếu nhập, không bao giờ âm. */
    public BigDecimal remainingDebt(Purchaseinvoice invoice) {
        return safe(invoice.getTotalAmount()).subtract(safe(invoice.getPaid())).max(BigDecimal.ZERO);
    }

    /**
     * Ghi nhận tiền thực trả cho phiếu nhập từ một phiếu chi. {@code delta} dương là chi thêm, âm là
     * hoàn lại (phiếu chi bị hủy sau khi đã duyệt).
     *
     * <p>Đây là nơi <strong>duy nhất</strong> {@code paid} thay đổi sau khi tạo phiếu, và nó luôn
     * ghi lại {@code status} kèm theo — xem {@link #resolveDisplayStatus} để hiểu vì sao hai thứ đó
     * bắt buộc phải đi cùng nhau.</p>
     *
     * <p>{@code isValidForDeduction} cố tình <em>không</em> được cập nhật ở đây: nó vốn đã được tính
     * lại mỗi lần đọc và cột lưu trong DB không được tin (xem javadoc của
     * {@link #isValidForDeduction}).</p>
     */
    @Transactional
    public void applyPayment(Integer purchaseId, BigDecimal delta) {
        if (delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        Purchaseinvoice invoice = purchaseinvoiceRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        if (PurchaseInvoiceStatus.CANCELLED.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("Không thể ghi nhận thanh toán cho phiếu nhập đã hủy");
        }

        BigDecimal totalAmount = safe(invoice.getTotalAmount());
        BigDecimal newPaid = safe(invoice.getPaid()).add(delta);

        if (newPaid.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Số tiền hoàn lại vượt quá số đã trả cho phiếu nhập");
        }
        if (newPaid.compareTo(totalAmount) > 0) {
            throw new IllegalArgumentException("Số tiền trả vượt quá tổng tiền phiếu nhập");
        }

        invoice.setPaid(newPaid);
        invoice.setStatus(resolveInvoiceStatus(totalAmount, newPaid));
        savePurchaseInvoiceGuardingConcurrentEdit(invoice);
    }

    /** Entry point cho service khác cập nhật phiếu nhập đã tồn tại. */
    public Purchaseinvoice persistPurchaseInvoice(Purchaseinvoice invoice) {
        return savePurchaseInvoiceGuardingConcurrentEdit(invoice);
    }

    private Purchaseinvoice savePurchaseInvoiceGuardingConcurrentEdit(Purchaseinvoice invoice) {
        try {
            return purchaseinvoiceRepository.saveAndFlush(invoice);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new IllegalArgumentException("Phiếu nhập \"" + formatPurchaseCode(invoice.getId())
                    + "\" vừa được người khác cập nhật (chi trả, trả hàng hoặc cấn trừ công nợ)."
                    + " Vui lòng tải lại trang để xem dữ liệu mới nhất rồi thực hiện lại.", exception);
        }
    }

    /**
     * Computes the {@code Purchaseinvoice.status} value from the paid-vs-total thresholds — used
     * both to persist the status on creation and to render it (as {@code paymentStatus}) on the
     * list/detail screens, so the two never drift apart.
     */
    private String resolveInvoiceStatus(BigDecimal totalAmount, BigDecimal paid) {
        if (paid.compareTo(BigDecimal.ZERO) <= 0) {
            return PurchaseInvoiceStatus.DEBT;
        }

        if (paid.compareTo(totalAmount) >= 0) {
            return PurchaseInvoiceStatus.COMPLETED;
        }

        return PurchaseInvoiceStatus.PARTIAL_DEBT;
    }

    /**
     * A status the app doesn't recognise gets its own style rather than falling into
     * {@code status-pending}, so a row whose {@code status} was edited to something arbitrary in
     * the DB is obviously wrong on screen instead of passing for a normal unpaid invoice.
     */
    private String statusCssClass(String paymentStatus) {
        return switch (paymentStatus) {
            case PurchaseInvoiceStatus.COMPLETED -> "status-completed";
            case PurchaseInvoiceStatus.PARTIAL_DEBT -> "status-partial";
            case PurchaseInvoiceStatus.CANCELLED -> "status-cancelled";
            case PurchaseInvoiceStatus.DEBT, PurchaseInvoiceStatus.DRAFT, PurchaseInvoiceStatus.PENDING_APPROVAL ->
                    "status-pending";
            default -> "status-unknown";
        };
    }

    private String formatPurchaseCode(Integer id) {
        if (id == null) {
            return "PINV-000000";
        }

        return "PINV-" + String.format("%06d", id);
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }

        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    private String formatLocalDate(LocalDate date) {
        if (date == null) {
            return "";
        }

        return date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    /** Same as {@link #formatLocalDate}, but a null expiration date means "xác nhận không có hạn", not "chưa nhập". */
    private String formatExpirationDate(LocalDate date) {
        return date == null ? "Không có hạn dùng" : formatLocalDate(date);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return LocalDate.parse(value);
    }

    private LocalDate toLocalDate(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private boolean containsNormalized(String value, String keyword) {
        return value != null && normalize(value).contains(keyword);
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

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private String generatePurchaseInvoiceCode() {
        int nextId = purchaseinvoiceRepository.findAll().stream()
                .map(Purchaseinvoice::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        return "PINV-" + String.format("%06d", nextId);
    }
}