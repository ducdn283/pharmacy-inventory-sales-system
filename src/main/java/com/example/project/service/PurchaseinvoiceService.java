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
 * Quản lý phiếu nhập hàng. Có 2 luồng tạo song song, không phụ thuộc lẫn nhau:
 * <ul>
 *   <li><strong>Chủ nhà thuốc</strong> — luôn tạo trực tiếp qua createPurchaseInvoice(), một bước:
 *       tự tạo tự duyệt luôn, lô hàng (Batch) được sinh ngay trong cùng transaction và approvedAt
 *       được ghi ngay lúc tạo. Cũng có thể lưu Nháp trước (createPurchaseInvoiceDraft()) rồi sửa
 *       lại sau (updatePurchaseInvoiceDraft() để giữ Nháp, hoặc finalizePurchaseInvoiceDraft() để
 *       vừa sửa vừa duyệt luôn).</li>
 *   <li><strong>Kế toán</strong> — tạo Nháp/Chờ duyệt qua createPurchaseInvoiceDraft()/
 *       createPurchaseInvoiceForApproval(), có thể sửa (updatePurchaseInvoiceDraft()/
 *       submitPurchaseInvoiceDraft()) hoặc xóa (deletePurchaseInvoiceDraft()) khi còn Nháp. Chủ nhà
 *       thuốc duyệt (approvePurchaseInvoice()) hoặc từ chối (rejectPurchaseInvoice()). Lô hàng chỉ
 *       được tạo và approvedAt chỉ được ghi khi duyệt — từ chối thì quay về Nháp, không cần đảo
 *       ngược gì vì chưa từng nhập kho.</li>
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

    // Inject bằng setter (không phải constructor) để các test cũ dựng service bằng constructor cũ vẫn chạy được.
    @Autowired
    public void setInventoryAlertEventService(
            InventoryAlertEventService inventoryAlertEventService
    ) {
        this.inventoryAlertEventService =
                inventoryAlertEventService;
    }

    // Loại hàng (Type.sortType / Type.name) cần xử lý đặc biệt khi nhập hàng, so sánh không phân
    // biệt hoa thường/dấu qua normalize(...).
    private static final String SORT_MEDICAL_DEVICE = "thiet bi y te";
    private static final String DEVICE_MACHINE_MARK = "may";
    private static final String DEVICE_NO_EXPIRY_MARK = "khong han";

    // ------------------------------------------------------------------ who may create

    /** Vai trò nào được tạo phiếu nhập ngay bây giờ — Kế toán và Chủ nhà thuốc đều luôn được. */
    @Transactional(readOnly = true)
    public boolean canCreatePurchaseInvoice(String role) {
        return RoleConstants.ACCOUNTANT.equals(role) || RoleConstants.OWNER.equals(role);
    }

    /** Tìm kiếm danh sách phiếu nhập: 1 ô từ khóa (mã phiếu/NCC/sản phẩm) + lọc ngày + trạng thái, phân trang trong bộ nhớ. */
    @Transactional(readOnly = true)
    public Page<PurchaseInvoiceListItemResponse> searchPurchaseInvoices(String keyword,
                                                                        String fromDate,
                                                                        String toDate,
                                                                        String paymentStatus,
                                                                        Pageable pageable) {
        String normalizedKeyword = normalize(keyword);
        LocalDate from = parseDate(fromDate);
        LocalDate to = parseDate(toDate);

        List<Purchaseinvoice> invoices = purchaseinvoiceRepository.findAllWithRelations();
        List<Purchasedetail> allDetails = purchasedetailRepository.findAllWithRelations();

        Map<Integer, List<Purchasedetail>> detailMap = allDetails.stream()
                .filter(detail -> detail.getPurchaseID() != null)
                .collect(Collectors.groupingBy(detail -> detail.getPurchaseID().getId()));

        List<PurchaseInvoiceListItemResponse> filtered = invoices.stream()
                .filter(invoice -> matchesKeyword(invoice,
                        detailMap.getOrDefault(invoice.getId(), List.of()), normalizedKeyword))
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

    /** Overload cũ, giữ để tương thích ngược — 3 field lọc riêng (AND với nhau), UI hiện dùng bản 1 từ khóa ở trên. */
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
                .filter(invoice -> matchesProduct(
                        detailMap.getOrDefault(invoice.getId(), List.of()), normalizedProduct))
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

    // Thống kê tổng quan cho màn danh sách: số phiếu tạo hôm nay, tổng tiền, đã trả và còn nợ (bỏ qua phiếu đã hủy).
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

    // Lấy đầy đủ dữ liệu 1 phiếu nhập để hiển thị trang chi tiết: đầu phiếu, các dòng hàng, trạng thái thanh toán.
    @Transactional(readOnly = true)
    public PurchaseInvoiceDetailPageResponse getDetail(Integer purchaseId) {
        Purchaseinvoice invoice = purchaseinvoiceRepository.findByIdWithRelations(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        List<Purchasedetail> details = purchasedetailRepository.findByPurchaseIdWithProduct(purchaseId);
        PurchaseBatchDisplayMaps batchDisplayMaps = purchaseBatchDisplayMaps(details);
        Map<Integer, String> unitNameByDetailId = batchDisplayMaps.unitNames();
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
                .map(detail -> toDetailItem(detail, unitNameByDetailId.get(detail.getId()),
                        batchDisplayMaps.batchNames().get(detail.getId()), sellPriceByProduct))
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

    /** Chỉ tài khoản đã tạo phiếu (employeeID) mới được hủy. Dùng để hiện/ẩn nút; cancelPurchaseInvoice() kiểm tra lại lần nữa trước khi ghi dữ liệu. */
    @Transactional(readOnly = true)
    public boolean canCancel(Integer purchaseId, Integer currentAccountId) {
        Purchaseinvoice invoice = purchaseinvoiceRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));
        return isCreator(invoice, currentAccountId)
                && !PurchaseInvoiceStatus.CANCELLED.equals(invoice.getStatus())
                && !PurchaseInvoiceStatus.DRAFT.equals(invoice.getStatus())
                && !PurchaseInvoiceStatus.PENDING_APPROVAL.equals(invoice.getStatus());
    }

    // Lấy dữ liệu để render trang in phiếu nhập (đầu phiếu + từng dòng hàng kèm đơn vị nhập).
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

    // Chuyển 1 dòng Purchasedetail thành dòng dữ liệu in (kèm tính thành tiền dòng).
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
     * Đơn vị thực tế đã dùng để tạo lô hàng của mỗi dòng phiếu nhập — đọc từ Batch.importUnitID
     * (mỗi Purchasedetail luôn có đúng 1 Batch tạo cùng transaction, xem createBatchForDetail()),
     * không suy đoán lại từ Product như gợi ý trên trang tạo phiếu.
     */
    private record PurchaseBatchDisplayMaps(Map<Integer, String> unitNames,
                                            Map<Integer, String> batchNames) {}

    // Tra Batch theo từng Purchasedetail để lấy tên đơn vị nhập thực tế + tên lô hiển thị.
    private PurchaseBatchDisplayMaps purchaseBatchDisplayMaps(List<Purchasedetail> details) {
        List<Integer> detailIds = details.stream()
                .map(Purchasedetail::getId)
                .filter(Objects::nonNull)
                .toList();

        if (detailIds.isEmpty()) {
            return new PurchaseBatchDisplayMaps(Map.of(), Map.of());
        }

        Map<Integer, String> unitNames = new HashMap<>();
        Map<Integer, String> batchNames = new HashMap<>();

        for (Batch batch : batchRepository.findByPurchaseDetailIds(detailIds)) {
            if (batch.getPurchaseDetailID() == null) {
                continue;
            }
            Integer detailId = batch.getPurchaseDetailID().getId();
            if (batch.getImportUnitID() != null) {
                unitNames.put(detailId, batch.getImportUnitID().getUnitName());
            }
            if (batch.getBatchName() != null && !batch.getBatchName().isBlank()) {
                batchNames.put(detailId, batch.getBatchName());
            }
        }

        return new PurchaseBatchDisplayMaps(unitNames, batchNames);
    }

    // Map (id dòng phiếu nhập -> tên đơn vị nhập), dùng riêng cho trang in.
    private Map<Integer, String> importUnitNameByPurchaseDetailId(List<Purchasedetail> details) {
        return purchaseBatchDisplayMaps(details).unitNames();
    }

    /** Mọi thứ resolve/validate được từ request trước khi lưu phiếu lần đầu — dùng chung cho mọi luồng tạo/sửa nháp. */
    private record PreparedInvoiceHeader(Supplier supplier, Account employee, Procurementplan procurementPlan,
                                          List<PreparedPurchaseLine> lines, BigDecimal additionCost,
                                          BigDecimal discount, BigDecimal totalAmount) {
    }

    // Validate + resolve toàn bộ dữ liệu request (NCC, nhân viên, dự trù, từng dòng hàng, tổng tiền) trước khi lưu.
    private PreparedInvoiceHeader prepareInvoiceHeader(PurchaseInvoiceCreateRequest request, Integer currentAccountId) {
        validateCreateRequest(request);

        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp"));

        Account employee = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        // Chỉ để liên kết tham chiếu, không bắt buộc — null nghĩa là không gắn với dự trù nào.
        Procurementplan procurementPlan = request.getRequisitionId() == null
                ? null
                : procurementplanRepository.findById(request.getRequisitionId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dự trù mua hàng"));

        // Resolve sản phẩm + thuế suất từng dòng trước (lấy từ Type, không tin client) để tính tổng
        // trước khi lưu — xem prepareLine(). priceIncludesVat áp dụng cho cả phiếu, không theo dòng.
        boolean priceIncludesVat = request.isPriceIncludesVat();
        List<PreparedPurchaseLine> lines = request.getDetails().stream()
                .map(item -> prepareLine(item, priceIncludesVat))
                .toList();

        BigDecimal subtotal = lines.stream()
                .map(PreparedPurchaseLine::grossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal additionCost = safe(request.getAdditionCost());
        BigDecimal discount = safe(request.getDiscount());
        // grossAmount của mọi dòng đã gồm thuế tới đây rồi (prepareLine() gross-up nếu cần), nên
        // không cộng thêm gì vào subtotal nữa.
        BigDecimal totalAmount = subtotal.add(additionCost).subtract(discount);

        if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Tổng tiền phiếu nhập không hợp lệ");
        }

        return new PreparedInvoiceHeader(supplier, employee, procurementPlan, lines, additionCost, discount,
                totalAmount);
    }

    /** Dựng entity Purchaseinvoice chưa lưu — luôn bắt đầu chưa trả đồng nào (paid=0). */
    private Purchaseinvoice buildInvoiceEntity(PurchaseInvoiceCreateRequest request, PreparedInvoiceHeader header,
                                               String status, LocalDateTime approvedAt) {
        // Nhập hàng và trả tiền là 2 sự kiện tách biệt — tiền chỉ chuyển động qua applyPayment() khi
        // có phiếu chi nối vào, nên form tạo phiếu không hỏi "đã trả bao nhiêu".
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

    /** Lưu mỗi dòng đã chuẩn bị thành một Purchasedetail — chưa tạo Batch, xem receiveStockForInvoice(). */
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
     * Side effect "hàng về kho" thật sự — tạo 1 Batch cho mỗi dòng và cập nhật giá tham khảo NCC.
     * Chỉ gọi đúng 1 lần khi phiếu chính thức có hiệu lực: ngay khi Chủ nhà thuốc tạo trực tiếp,
     * hoặc từ approvePurchaseInvoice() khi duyệt phiếu do Kế toán nộp. Không bao giờ gọi cho
     * phiếu còn Nháp/Chờ duyệt.
     */
    private void receiveStockForInvoice(Purchaseinvoice invoice, List<Purchasedetail> details) {
        Supplier supplier = invoice.getSupplierID();
        for (Purchasedetail detail : details) {
            Product product = detail.getProductID();
            createBatchForDetail(invoice, detail, product);
            upsertSupplierProductCostPrice(supplier, product, detail.getImportPrice());
        }
    }

    /** Chủ nhà thuốc tạo trực tiếp, 1 bước: vừa tạo vừa duyệt luôn, hàng vào kho ngay, approvedAt ghi ngay lúc tạo. */
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

    /** "Lưu Nháp" — chỉ lưu header + dòng chi tiết, chưa vào kho, chưa cập nhật giá, chưa ghi approvedAt. Chỉ sửa/xóa được khi còn Nháp. */
    @Transactional
    public Integer createPurchaseInvoiceDraft(PurchaseInvoiceCreateRequest request, Integer currentAccountId) {
        PreparedInvoiceHeader header = prepareInvoiceHeader(request, currentAccountId);

        Purchaseinvoice invoice = buildInvoiceEntity(request, header, PurchaseInvoiceStatus.DRAFT, null);
        Purchaseinvoice savedInvoice = savePurchaseInvoiceGuardingConcurrentEdit(invoice);

        persistDetailLines(savedInvoice, header.lines());
        return savedInvoice.getId();
    }

    /** Kế toán "Nộp duyệt" ngay từ form tạo — giống createPurchaseInvoiceDraft() nhưng vào thẳng trạng thái Chờ duyệt. */
    @Transactional
    public Integer createPurchaseInvoiceForApproval(PurchaseInvoiceCreateRequest request, Integer currentAccountId) {
        PreparedInvoiceHeader header = prepareInvoiceHeader(request, currentAccountId);

        Purchaseinvoice invoice = buildInvoiceEntity(request, header, PurchaseInvoiceStatus.PENDING_APPROVAL, null);
        Purchaseinvoice savedInvoice = savePurchaseInvoiceGuardingConcurrentEdit(invoice);

        persistDetailLines(savedInvoice, header.lines());
        return savedInvoice.getId();
    }

    /**
     * Thân dùng chung để sửa 1 phiếu Nháp: validate/resolve lại y như tạo mới, thay toàn bộ dòng
     * Purchasedetail (Nháp chưa từng có Batch nên không cần đối chiếu gì), giữ nguyên mã phiếu,
     * người tạo gốc (employeeID) và ngày tạo. currentAccountId chỉ để xác nhận tài khoản đang sửa
     * còn tồn tại, KHÔNG gán lại employeeID.
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

    /** Kế toán sửa 1 phiếu Nháp và lưu lại vẫn ở trạng thái Nháp. */
    @Transactional
    public Integer updatePurchaseInvoiceDraft(Integer purchaseId, PurchaseInvoiceCreateRequest request,
                                              Integer currentAccountId) {
        return applyDraftEdit(purchaseId, request, currentAccountId, PurchaseInvoiceStatus.DRAFT).getId();
    }

    /** Kế toán sửa 1 phiếu Nháp và nộp duyệt luôn trong cùng thao tác. */
    @Transactional
    public Integer submitPurchaseInvoiceDraft(Integer purchaseId, PurchaseInvoiceCreateRequest request,
                                              Integer currentAccountId) {
        return applyDraftEdit(purchaseId, request, currentAccountId, PurchaseInvoiceStatus.PENDING_APPROVAL).getId();
    }

    /**
     * Chủ nhà thuốc sửa 1 phiếu Nháp (của mình hoặc của Kế toán) và duyệt luôn trong cùng thao tác —
     * không qua bước Chờ duyệt trung gian, vì Chủ nhà thuốc luôn vừa tạo vừa duyệt. Hàng vào kho
     * ngay tại đây, cùng cơ chế receiveStockForInvoice() mà createPurchaseInvoice()/approvePurchaseInvoice() dùng.
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

    /** Xóa hẳn 1 phiếu Nháp — chỉ được phép khi còn Nháp, vì chưa có Batch/công nợ/thanh toán nào gắn vào. Không giới hạn theo người tạo. */
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
     * Chủ nhà thuốc duyệt 1 phiếu Chờ duyệt: đây là thời điểm DUY NHẤT hàng thật sự về kho đối với
     * luồng Kế toán tạo phiếu — receiveStockForInvoice() chỉ chạy ở đây. paid luôn là 0 tại thời
     * điểm này nên status luôn suy ra DEBT ("Nợ") trừ phi tổng tiền là 0.
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
     * Chủ nhà thuốc từ chối 1 phiếu Chờ duyệt — quay về Nháp để Kế toán sửa lại và nộp lại, KHÔNG
     * hủy. Vì chưa từng cộng kho, không có gì để đảo ngược.
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
     * Đổ dữ liệu 1 phiếu Nháp vào cùng DTO mà form tạo phiếu dùng, để màn "Sửa phiếu nhập" chính là
     * form tạo phiếu được điền sẵn — cùng field, cùng validate, cùng JS. Chỉ gọi cho phiếu còn Nháp.
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
    public void cancelPurchaseInvoice(Integer purchaseId, String reason, Integer currentAccountId) {
        Purchaseinvoice invoice = purchaseinvoiceRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu nhập"));

        if (!isCreator(invoice, currentAccountId)) {
            throw new IllegalArgumentException("Chỉ tài khoản đã tạo phiếu nhập này mới có thể hủy phiếu");
        }

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

    // Kiểm tra tài khoản đang thao tác có phải người tạo (employeeID) của phiếu nhập này không.
    private boolean isCreator(Purchaseinvoice invoice, Integer accountId) {
        return accountId != null
                && invoice.getEmployeeID() != null
                && accountId.equals(invoice.getEmployeeID().getId());
    }

    /** True nếu tồn kho hiện tại của lô đã khác với số lượng nhập ban đầu. */
    private boolean batchWasTouchedSinceImport(Batch batch) {
        int originalBaseQuantity = calculateBaseQuantity(batch.getImportQtyInUnit(), batch.getImportUnitID());
        int currentQuantity = batch.getStorageQuantity() == null ? 0 : batch.getStorageQuantity();
        return currentQuantity != originalBaseQuantity;
    }

    // Nối thêm 1 dòng ghi chú mới vào ghi chú đã có, ngăn cách bởi " | ".
    private String appendNote(String existingNote, String addition) {
        String base = existingNote == null ? "" : existingNote.trim();
        return base.isEmpty() ? addition : base + " | " + addition;
    }

    /** Một dòng nhập hàng đã resolve sản phẩm + thuế, sẵn sàng lưu. unitPriceGross luôn là giá đã gồm thuế — thứ thật sự được lưu vào Purchasedetail.importPrice, xem prepareLine(). */
    private record PreparedPurchaseLine(PurchaseInvoiceDetailCreateRequest item, Product product,
                                        BigDecimal unitPriceGross, BigDecimal grossAmount, BigDecimal vatRate,
                                        BigDecimal preTaxAmount, BigDecimal vatAmount) {}

    /**
     * @param priceIncludesVat cờ áp dụng cho cả phiếu — true: importPrice đã gồm thuế (mặc định);
     *                         false: là giá trước thuế, phải gross-up ngay tại đây trước khi lưu,
     *                         để phần còn lại của app luôn có thể coi giá lưu là đã gồm thuế.
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

    /** Ngược lại calculateLinePreTaxAmount(): gross-up đơn giá trước thuế lên theo vatRate. */
    private BigDecimal grossUpUnitPrice(BigDecimal preTaxUnitPrice, BigDecimal vatRate) {
        BigDecimal rate = safe(vatRate);
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            return preTaxUnitPrice.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal multiplier = BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        return preTaxUnitPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Sản phẩm có cần theo dõi hạn sử dụng không. Mọi loại hàng đều cần, trừ 2 trường hợp thuộc
     * "thiết bị y tế": "(máy)" (thiết bị bền, theo dõi bằng bảo hành chứ không phải hạn dùng) và
     * "(không hạn)" (được khai báo rõ là không hết hạn).
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
     * Validate hạn sử dụng theo loại hàng: sản phẩm không theo dõi hạn (requiresExpirationDate() =
     * false) được miễn hoàn toàn — persistDetailLines() sẽ bỏ giá trị này dù client có gửi gì đi
     * nữa. Sản phẩm có theo dõi hạn thì luôn bắt buộc, không có cách nào bỏ qua thủ công.
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

    /** Tách phần trước thuế ra khỏi số tiền đã gồm thuế: gross ÷ (1 + vatRate/100). */
    private BigDecimal calculateLinePreTaxAmount(BigDecimal grossAmount, BigDecimal vatRate) {
        BigDecimal rate = safe(vatRate);
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            return grossAmount.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal divisor = BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        return grossAmount.divide(divisor, 2, RoundingMode.HALF_UP);
    }

    /**
     * Thuế suất VAT theo từng sản phẩm cho ô "Thuế suất VAT" (readonly) trên form tạo phiếu — luôn
     * lấy Type.defaultVATRate, bỏ qua Product.vatRateOverride (đó là khái niệm riêng của bán hàng).
     * Chỉ để hiển thị; resolvePurchaseVatRate() mới là giá trị server thực sự tin khi lưu.
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
     * Xác định "đơn vị nhập" duy nhất của mỗi sản phẩm — cùng thứ tự ưu tiên resolveImportUnitOrNull()
     * dùng khi lưu: isDefault > isBaseUnit > id nhỏ nhất. Dùng chung cho getImportUnitNameByProduct()
     * và getSellPriceByProduct() để cột "Đơn vị" và dòng "Giá bán" luôn khớp cùng 1 đơn vị.
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

    /** Tên đơn vị nhập mặc định theo sản phẩm, hiển thị ở cột "Đơn vị" trên form tạo phiếu (chỉ để tham khảo, chính là đơn vị resolveImportUnit() sẽ dùng). */
    @Transactional(readOnly = true)
    public Map<Integer, String> getImportUnitNameByProduct() {
        Map<Integer, String> result = new HashMap<>();
        resolveImportUnitByProduct().forEach((productId, unit) -> result.put(productId, unit.getUnitName()));
        return result;
    }

    /**
     * Giá bán tham khảo của cùng đơn vị nhập đó, hiển thị dưới tên sản phẩm trên form tạo phiếu
     * (và bảng lô ở trang chi tiết). Chỉ để tham khảo — màn này không ghi lại Productunit.sellPrice,
     * việc đó thuộc về Price Settings.
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

    /** Tạo dòng Batch (tồn kho) cho 1 Purchasedetail vừa lưu. */
    private void createBatchForDetail(Purchaseinvoice invoice, Purchasedetail detail, Product product) {
        Productunit importUnit = resolveImportUnit(product);

        BigDecimal importPrice = safe(detail.getImportPrice());
        BigDecimal importPricePerBase = calculateImportPricePerBase(importPrice, importUnit);
        int storageQuantity = calculateBaseQuantity(detail.getQuantity(), importUnit);

        Batch batch = new Batch();
        batch.setBatchCode(generateBatchCode(invoice.getId(), detail.getId()));
        batch.setBatchName(generateBatchName(detail));
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

    // Lấy đơn vị nhập của sản phẩm, ném lỗi nếu sản phẩm chưa khai báo đơn vị nào.
    private Productunit resolveImportUnit(Product product) {
        return resolveImportUnitOrNull(product)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Sản phẩm " + (product != null ? product.getName() : "") + " chưa có đơn vị nhập trong ProductUnit"
                ));
    }

    // Tìm đơn vị nhập ưu tiên nhất của sản phẩm (mặc định > đơn vị cơ bản > id nhỏ nhất), rỗng nếu chưa có đơn vị nào.
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

    // Thứ tự ưu tiên chọn đơn vị nhập: mặc định (0) > đơn vị cơ bản (1) > còn lại (2).
    private int importUnitPriority(Productunit unit) {
        if (Boolean.TRUE.equals(unit.getIsDefault())) {
            return 0;
        }

        if (Boolean.TRUE.equals(unit.getIsBaseUnit())) {
            return 1;
        }

        return 2;
    }

    // Quy đổi đơn giá theo đơn vị nhập về đơn giá tính trên đơn vị cơ bản (chia cho tỉ lệ quy đổi).
    private BigDecimal calculateImportPricePerBase(BigDecimal importPrice, Productunit importUnit) {
        if (importUnit == null
                || importUnit.getRatio() == null
                || importUnit.getRatio().compareTo(BigDecimal.ZERO) <= 0) {
            return importPrice;
        }

        return importPrice.divide(importUnit.getRatio(), 2, RoundingMode.HALF_UP);
    }

    // Quy đổi số lượng nhập theo đơn vị nhập về số lượng tồn kho theo đơn vị cơ bản.
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

    // Sinh mã lô hàng duy nhất từ id phiếu nhập + id dòng chi tiết.
    private String generateBatchCode(Integer purchaseId, Integer purchaseDetailId) {
        return "BATCH-" + String.format("%06d", purchaseId == null ? 0 : purchaseId)
                + "-" + String.format("%03d", purchaseDetailId == null ? 0 : purchaseDetailId);
    }

    // Sinh tên lô hàng tự động từ id dòng chi tiết phiếu nhập.
    private String generateBatchName(Purchasedetail detail) {
        return "LOT-" + String.format("%06d", detail.getId() == null ? 0 : detail.getId());
    }

    /**
     * "Giá nhập" gợi ý cho trang tạo phiếu, theo từng cặp (NCC, sản phẩm) đã từng nhập — lấy từ
     * SupplierProduct.costPrice (giá nhập gần nhất, xem upsertSupplierProductCostPrice()). Đưa sẵn
     * vào model để JS đọc trực tiếp, không cần gọi AJAX.
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

    // Danh sách nhà cung cấp, sắp xếp theo tên.
    @Transactional(readOnly = true)
    public List<Supplier> listSuppliers() {
        return supplierRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(supplier -> supplier.getName() == null ? "" : supplier.getName()))
                .toList();
    }

    /** Projection (id, tên) của listSuppliers() cho ô tìm NCC trên form tạo phiếu. */
    @Transactional(readOnly = true)
    public List<SupplierOptionResponse> listSupplierOptions() {
        return listSuppliers().stream()
                .map(supplier -> new SupplierOptionResponse(supplier.getId(), supplier.getName()))
                .toList();
    }

    /** Danh sách dự trù mua hàng cho ô chọn (không bắt buộc) trên form tạo phiếu, mới nhất trước. */
    @Transactional(readOnly = true)
    public List<Procurementplan> listProcurementPlans() {
        return procurementplanRepository.findAll(Sort.by(Sort.Direction.DESC, "date"));
    }

    /**
     * Lấy dữ liệu từ dự trù mua hàng cho phiếu nhập — chỉ trả về dòng dự trù đã gán đúng NCC đang
     * chọn. Liên kết dự trù chỉ để gợi ý số lượng/giá, không bắt buộc, không validate lại số lượng
     * thực nhập so với requestedQuantity.
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
     * estimatedPrice trên dự trù là TỔNG giá dự kiến cho requestedQuantity, đã gồm VAT — chia đều
     * ra unitPrice để gợi ý vào "Đơn giá" phiếu nhập. Gợi ý này giả định phiếu vẫn để
     * priceIncludesVat=true (mặc định); nếu người dùng chuyển sang nhập giá trước thuế thì số gợi ý
     * (vẫn đang là giá gồm VAT) sẽ bị hiểu sai — xem prepareLine().
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

    /** Danh sách trạng thái cố định của phiếu nhập, cho dropdown lọc. */
    public List<String> listPaymentStatuses() {
        return PurchaseInvoiceStatus.ALL;
    }

    /** Chỉ (id, tên) — không dùng entity Product thẳng vì danh sách này được nhúng vào JS trên trang tạo phiếu. */
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
     * ID các sản phẩm không theo dõi hạn sử dụng (thiết bị y tế "(máy)" hoặc "(không hạn)"). Dùng để
     * ẩn hẳn ô "Hạn dùng" trên form tạo phiếu cho các sản phẩm này. requiresExpirationDate() là quy
     * tắc thật sự được kiểm tra lại server-side khi lưu, không phụ thuộc client gửi gì.
     */
    @Transactional(readOnly = true)
    public Set<Integer> getNoExpirationProductIds() {
        return productRepository.findAllWithRelations().stream()
                .filter(product -> !requiresExpirationDate(product))
                .map(Product::getProductID)
                .collect(Collectors.toSet());
    }

    // Validate các field bắt buộc ở mức phiếu và từng dòng hàng (số hóa đơn, ngày, sản phẩm, số lượng, đơn giá).
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

            // Quy tắc hạn sử dụng theo loại hàng cần Product đã resolve — kiểm tra trong
            // prepareLine() ngay sau khi tra Product, tránh phải tra 2 lần.
        }
    }

    // Chuyển 1 Purchaseinvoice thành dòng hiển thị trên danh sách (kèm tính công nợ, trạng thái).
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

    // Chuyển 1 Purchasedetail thành dòng hiển thị trên trang chi tiết (kèm thành tiền, giá bán tham khảo).
    private PurchaseInvoiceDetailItemResponse toDetailItem(Purchasedetail detail, String unitName, String batchName,
                                                           Map<Integer, BigDecimal> sellPriceByProduct) {
        Product product = detail.getProductID();
        BigDecimal lineTotal = safe(detail.getImportPrice())
                .multiply(BigDecimal.valueOf(detail.getQuantity() == null ? 0 : detail.getQuantity()));

        return new PurchaseInvoiceDetailItemResponse(
                product != null ? product.getProductID() : null,
                product != null ? product.getName() : "Không rõ",
                batchName,
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

    // --- combined search (Mã phiếu / Nhà cung cấp / Sản phẩm) ---

    // Khớp từ khóa tìm kiếm với mã phiếu, tên NCC hoặc sản phẩm trong phiếu (ô tìm kiếm gộp 1 field).
    private boolean matchesKeyword(Purchaseinvoice invoice, List<Purchasedetail> details,
                                   String normalizedKeyword) {
        return normalizedKeyword.isBlank()
                || matchesCode(invoice, normalizedKeyword)
                || matchesSupplier(invoice, normalizedKeyword)
                || matchesProduct(details, normalizedKeyword);
    }

    // Khớp mã phiếu nhập với từ khóa đã chuẩn hóa.
    private boolean matchesCode(Purchaseinvoice invoice, String normalizedCode) {
        return normalizedCode.isBlank() || containsNormalized(formatPurchaseCode(invoice.getId()), normalizedCode);
    }

    // Khớp tên nhà cung cấp của phiếu với từ khóa đã chuẩn hóa.
    private boolean matchesSupplier(Purchaseinvoice invoice, String normalizedSupplier) {
        return normalizedSupplier.isBlank()
                || containsNormalized(invoice.getSupplierID() != null ? invoice.getSupplierID().getName() : null,
                        normalizedSupplier);
    }

    // Khớp id/tên/mã/barcode của bất kỳ sản phẩm nào trong các dòng phiếu với từ khóa đã chuẩn hóa.
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

    // Kiểm tra ngày lập phiếu có nằm trong khoảng [from, to] đã lọc không (bỏ qua cận nào là null).
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

    // Tổng tiền hàng (chưa cộng chi phí phát sinh, chưa trừ chiết khấu) = tổng đơn giá × số lượng từng dòng.
    private BigDecimal calculateSubtotal(List<Purchasedetail> details) {
        return details.stream()
                .map(detail -> safe(detail.getImportPrice())
                        .multiply(BigDecimal.valueOf(detail.getQuantity() == null ? 0 : detail.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Tổng thuế GTGT đầu vào để hiển thị — cộng trực tiếp từ vatAmount của từng dòng. */
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

    // Alias của resolveInvoiceStatus() — giữ tên riêng cho chỗ gọi liên quan tới hiển thị thanh toán.
    private String resolvePaymentStatus(BigDecimal totalAmount, BigDecimal paid) {
        return resolveInvoiceStatus(totalAmount, paid);
    }

    /**
     * Trạng thái hiển thị cho danh sách/chi tiết — luôn lấy đúng giá trị đang lưu trong cột status,
     * KHÔNG tự suy lại từ paid/totalAmount, để phản ánh đúng nếu ai đó sửa status trực tiếp trong DB.
     *
     * <p><strong>Bất biến bắt buộc</strong>: mọi chỗ ghi paid (applyPayment()) phải ghi lại status
     * bằng resolveInvoiceStatus() trong cùng transaction — quên là màn hình hiện sai "Nợ" trên phiếu
     * đã trả đủ.</p>
     *
     * <p>Chỉ suy lại từ tiền khi cột status rỗng (dòng cũ/thiếu dữ liệu). Giá trị lạ trả về nguyên
     * văn, statusCssClass() sẽ tô thành "không xác định".</p>
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
     * Thuế GTGT đầu vào của phiếu này có được khấu trừ ngay bây giờ không — áp dụng quy tắc bên
     * dưới cho cả phiếu, phiếu đã hủy luôn không được khấu trừ. Dùng cho TaxperiodsnapshotService
     * khi tổng hợp thuế đầu vào theo kỳ. Kết quả phụ thuộc thời gian (phiếu chưa trả hết hết hạn
     * khấu trừ ngay khi qua dueDate) — đây là lý do kỳ thuế phải chốt snapshot chứ không tính lại mãi.
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

    // Lưu phiếu nhập bằng saveAndFlush(), bắt lỗi khóa lạc quan (@Version) và trả lại thông báo dễ hiểu cho người dùng.
    private Purchaseinvoice savePurchaseInvoiceGuardingConcurrentEdit(Purchaseinvoice invoice) {
        try {
            return purchaseinvoiceRepository.saveAndFlush(invoice);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new IllegalArgumentException("Phiếu nhập \"" + formatPurchaseCode(invoice.getId())
                    + "\" vừa được người khác cập nhật (chi trả, trả hàng hoặc cấn trừ công nợ)."
                    + " Vui lòng tải lại trang để xem dữ liệu mới nhất rồi thực hiện lại.", exception);
        }
    }

    /** Suy ra status từ paid so với totalAmount — dùng cả khi lưu lẫn khi hiển thị để 2 nơi không lệch nhau. */
    private String resolveInvoiceStatus(BigDecimal totalAmount, BigDecimal paid) {
        if (paid.compareTo(BigDecimal.ZERO) <= 0) {
            return PurchaseInvoiceStatus.DEBT;
        }

        if (paid.compareTo(totalAmount) >= 0) {
            return PurchaseInvoiceStatus.COMPLETED;
        }

        return PurchaseInvoiceStatus.PARTIAL_DEBT;
    }

    /** Status lạ (không nằm trong danh sách biết) có class CSS riêng, không lẫn vào "status-pending" bình thường. */
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

    /** Giống formatLocalDate(), nhưng null nghĩa là "xác nhận không có hạn", không phải "chưa nhập". */
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

    // Chuẩn hóa chuỗi để so khớp tìm kiếm không phân biệt hoa/thường và dấu tiếng Việt.
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

    // Sinh mã phiếu nhập mới dựa trên id lớn nhất hiện có + 1.
    private String generatePurchaseInvoiceCode() {
        int nextId = purchaseinvoiceRepository.findAll().stream()
                .map(Purchaseinvoice::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        return "PINV-" + String.format("%06d", nextId);
    }
}
