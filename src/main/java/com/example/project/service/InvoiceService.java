package com.example.project.service;

import com.example.project.dto.request.InvoiceCreateRequest;
import com.example.project.dto.request.InvoiceDetailCreateRequest;
import com.example.project.dto.response.CustomerOptionResponse;
import com.example.project.dto.response.InvoiceDetailItemResponse;
import com.example.project.dto.response.InvoiceDetailPageResponse;
import com.example.project.dto.response.InvoiceDetailProductGroupResponse;
import com.example.project.dto.response.InvoiceDetailUnitLineResponse;
import com.example.project.dto.response.InvoiceLineResponse;
import com.example.project.dto.response.InvoicePrintLineResponse;
import com.example.project.dto.response.InvoicePrintPageResponse;
import com.example.project.dto.response.InvoiceListItemResponse;
import com.example.project.dto.response.InvoiceResponse;
import com.example.project.dto.response.SellBatchOptionResponse;
import com.example.project.dto.response.SellProductOptionResponse;
import com.example.project.dto.response.SellUnitOptionResponse;
import com.example.project.entity.Account;
import com.example.project.entity.Financialsetting;
import com.example.project.entity.Batch;
import com.example.project.entity.Customer;
import com.example.project.entity.Invoice;
import com.example.project.entity.Invoicedetail;
import com.example.project.entity.Product;
import com.example.project.entity.Productunit;
import com.example.project.entity.Position;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.BatchRepository;
import com.example.project.repository.CustomerRepository;
import com.example.project.repository.FinancialsettingRepository;
import com.example.project.repository.InvoiceRepository;
import com.example.project.repository.InvoicedetailRepository;
import com.example.project.repository.PositionRepository;
import com.example.project.repository.ProductRepository;
import com.example.project.repository.ProductunitRepository;
import com.example.project.repository.ReturnRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
 * Nghiệp vụ hóa đơn bán hàng ({@link Invoice}) phục vụ màn Chủ nhà thuốc/Dược sĩ/Kế toán
 * ({@code /owner/invoices/**}, {@code /pharmacist/invoices/**}, {@code /accountant/invoices/**}).
 */
@Service
public class InvoiceService {
    private static final String RETURN_NONE = "NONE";
    private static final String RETURN_PARTIAL = "PARTIAL";
    private static final String RETURN_FULL = "FULL";

    private static final String INVOICE_TYPE_NORMAL = "Bán hàng";
    private static final String INVOICE_TYPE_NORMAL_LEGACY = "normal";
    /** Loại dữ liệu cũ — hiển thị là "Bán hàng". */
    private static final String INVOICE_TYPE_VAT_LEGACY = "Hóa đơn GTGT";
    private static final String INVOICE_TYPE_ADJUSTMENT = "Điều chỉnh";
    private static final String INVOICE_TYPE_ADJUSTMENT_LEGACY = "adjustment";
    private static final String INVOICE_TYPE_REPLACEMENT = "Thay thế";
    private static final String INVOICE_TYPE_RETURN = "return";
    private static final BigDecimal FULL_REFUND_RATE = new BigDecimal("100");

    private static final String PAYMENT_CASH = "CASH";
    private static final String PAYMENT_BANKING = "BANKING";
    private static final String PAYMENT_MIXED = "MIXED";
    private static final String PAYMENT_DEBT = "DEBT";

    private static final String STATUS_COMPLETED = "Hoàn thành";
    private static final String PRESCRIPTION_PRODUCT_TYPE = "Thuốc kê đơn";
    private static final String STATUS_DEBT = "Còn nợ";
    private static final String STATUS_SIGNED = "Đã ký";
    private static final String STATUS_RETURNED_FULL = "Đã trả hàng toàn bộ";
    private static final String STATUS_RETURNED_PARTIAL = "Đã trả hàng 1 phần";
    private static final List<String> ALL_STATUSES = List.of(STATUS_COMPLETED, STATUS_DEBT);
    private static final String RETAIL_BUYER_PRINT_LABEL = "Bán cho người tiêu dùng";
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final String[] MONEY_WORD_DIGITS = {
            "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"
    };

    private final InvoiceRepository invoiceRepository;
    private final InvoicedetailRepository invoicedetailRepository;
    private final ProductRepository productRepository;
    private final ProductunitRepository productunitRepository;
    private final PositionRepository positionRepository;
    private final BatchRepository batchRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final FinancialsettingRepository financialsettingRepository;
    private final FinancialsettingService financialsettingService;
    private final ReturnRepository returnRepository;
    // Mở/tái sử dụng ca bán ngay khi ghi nhận hóa đơn — tương tự phía trả hàng (xem ShiftreportService),
    // luôn thực hiện kể cả hóa đơn ghi nợ toàn phần không có dòng tiền mặt/chuyển khoản.
    private final ShiftreportService shiftreportService;
    private InventoryAlertEventService inventoryAlertEventService;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          InvoicedetailRepository invoicedetailRepository,
                          ProductRepository productRepository,
                          ProductunitRepository productunitRepository,
                          PositionRepository positionRepository,
                          BatchRepository batchRepository,
                          CustomerRepository customerRepository,
                          AccountRepository accountRepository,
                          FinancialsettingRepository financialsettingRepository,
                          FinancialsettingService financialsettingService,
                          ReturnRepository returnRepository,
                          ShiftreportService shiftreportService) {
        this.invoiceRepository = invoiceRepository;
        this.invoicedetailRepository = invoicedetailRepository;
        this.productRepository = productRepository;
        this.productunitRepository = productunitRepository;
        this.positionRepository = positionRepository;
        this.batchRepository = batchRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.financialsettingRepository = financialsettingRepository;
        this.financialsettingService = financialsettingService;
        this.returnRepository = returnRepository;
        this.shiftreportService = shiftreportService;
    }

    /** Gắn service cảnh báo tồn kho — inject sau để tránh vòng phụ thuộc. */
    @Autowired
    public void setInventoryAlertEventService(
            InventoryAlertEventService inventoryAlertEventService
    ) {
        this.inventoryAlertEventService =
                inventoryAlertEventService;
    }

    /**
     * Danh sách hóa đơn có phân trang, lọc theo mã, khoảng ngày, hình thức thanh toán, trạng thái và người bán.
     * Lọc trong bộ nhớ vì cần so khớp mã linh hoạt và trạng thái trả hàng.
     */
    @Transactional(readOnly = true)
    public Page<InvoiceListItemResponse> list(String search,
                                              String fromDate,
                                              String toDate,
                                              String paymentType,
                                              String status,
                                              Integer sellerId,
                                              Pageable pageable) {
        String normalizedKeyword = normalize(search);
        LocalDate from = parseDate(fromDate);
        LocalDate to = parseDate(toDate);
        String normalizedStatus = status == null ? "" : status.trim();
        Map<Integer, String> returnStates = returnStateByInvoice();

        List<InvoiceListItemResponse> filtered = invoiceRepository.findAllWithRelations()
                .stream()
                .filter(invoice -> matchesKeyword(invoice, normalizedKeyword))
                .filter(invoice -> matchesDate(invoice, from, to))
                .filter(invoice -> matchesPaymentType(invoice, paymentType))
                .filter(invoice -> matchesSeller(invoice, sellerId))
                .filter(invoice -> normalizedStatus.isEmpty()
                        || normalize(normalizedStatus).equals(normalize(invoice.getStatus())))
                .sorted(Comparator.comparing(Invoice::getDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(invoice -> toListItem(invoice, returnStates))
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());

        List<InvoiceListItemResponse> content = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, end);

        return new PageImpl<>(content, pageable, filtered.size());
    }

    /** Các trạng thái hóa đơn dùng cho dropdown lọc trên danh sách. */
    @Transactional(readOnly = true)
    public List<String> listStatuses() {
        return ALL_STATUSES;
    }

    /** Danh sách người bán đã có hóa đơn — dùng cho dropdown lọc. */
    @Transactional(readOnly = true)
    public List<CustomerOptionResponse> listSellers() {
        Map<Integer, String> byId = new LinkedHashMap<>();
        for (Invoice invoice : invoiceRepository.findAllWithRelations()) {
            Account employee = invoice.getEmployeeID();
            if (employee != null && employee.getId() != null) {
                byId.putIfAbsent(employee.getId(), employee.getName());
            }
        }
        return byId.entrySet().stream()
                .map(entry -> new CustomerOptionResponse(entry.getKey(), entry.getValue(), null, null))
                .sorted(Comparator.comparing(CustomerOptionResponse::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /** Nhãn hiển thị cho các hình thức thanh toán trên danh sách và form bán hàng. */
    public Map<String, String> paymentTypeLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PAYMENT_CASH, "Tiền mặt");
        labels.put(PAYMENT_BANKING, "Chuyển khoản");
        labels.put(PAYMENT_MIXED, "TM + CK");
        labels.put(PAYMENT_DEBT, "Ghi nợ");
        return labels;
    }

    /** Tổng số hóa đơn — thẻ thống kê trên danh sách. */
    @Transactional(readOnly = true)
    public long countAll() {
        return invoiceRepository.count();
    }

    /** Số hóa đơn phát sinh trong ngày — thẻ thống kê trên danh sách. */
    @Transactional(readOnly = true)
    public long countToday() {
        LocalDate today = LocalDate.now();
        return invoiceRepository.findAll().stream()
                .filter(invoice -> invoice.getDate() != null && toLocalDate(invoice.getDate()).equals(today))
                .count();
    }

    /** Doanh thu hôm nay (bỏ qua hóa đơn đã hủy) — thẻ thống kê trên danh sách. */
    @Transactional(readOnly = true)
    public BigDecimal sumTodayRevenue() {
        LocalDate today = LocalDate.now();
        return invoiceRepository.findAll().stream()
                .filter(invoice -> invoice.getDate() != null && toLocalDate(invoice.getDate()).equals(today))
                .filter(invoice -> !normalize(invoice.getStatus()).contains("huy"))
                .map(Invoice::getTotal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Số hóa đơn còn nợ — thẻ thống kê trên danh sách. */
    @Transactional(readOnly = true)
    public long countDebt() {
        return invoiceRepository.findAll().stream()
                .filter(invoice -> isPositive(invoice.getDebtAmount()))
                .count();
    }

    /** Tổng số tiền còn nợ — thẻ thống kê trên danh sách. */
    @Transactional(readOnly = true)
    public BigDecimal sumDebtTotal() {
        return invoiceRepository.findAll().stream()
                .map(Invoice::getDebtAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Số hóa đơn đã trả hàng (một phần hoặc toàn bộ) — thẻ thống kê trên danh sách. */
    @Transactional(readOnly = true)
    public long countReturned() {
        return returnStateByInvoice().values().stream()
                .filter(code -> RETURN_PARTIAL.equals(code) || RETURN_FULL.equals(code))
                .count();
    }

    /** Map id hóa đơn → mã trạng thái trả (NONE / PARTIAL / FULL) — badge trên danh sách. */
    private Map<Integer, String> returnStateByInvoice() {
        Map<Integer, String> map = new LinkedHashMap<>();
        for (Object[] row : invoicedetailRepository.sumQuantitiesGroupedByInvoice()) {
            Integer invoiceId = (Integer) row[0];
            long sold = row[1] != null ? ((Number) row[1]).longValue() : 0;
            long returned = row[2] != null ? ((Number) row[2]).longValue() : 0;
            String code;
            if (returned <= 0) {
                code = RETURN_NONE;
            } else if (returned >= sold) {
                code = RETURN_FULL;
            } else {
                code = RETURN_PARTIAL;
            }
            map.put(invoiceId, code);
        }
        return map;
    }

    /** Danh sách sản phẩm có thể bán kèm lô, đơn vị và tồn kho — form bán hàng. */
    @Transactional(readOnly = true)
    public List<SellProductOptionResponse> listSellableProducts() {
        Map<Integer, List<String>> positionsByProduct = new LinkedHashMap<>();
        for (Position position : positionRepository.findAllWithProduct()) {
            Product product = position.getProductID();
            if (product == null || product.getProductID() == null) {
                continue;
            }
            String name = trimToNull(position.getName());
            if (name == null) {
                continue;
            }
            positionsByProduct
                    .computeIfAbsent(product.getProductID(), ignored -> new ArrayList<>())
                    .add(name);
        }

        Map<Integer, List<Productunit>> unitsByProduct = new LinkedHashMap<>();
        for (Productunit unit : productunitRepository.findAllWithProduct()) {
            if (Boolean.FALSE.equals(unit.getIsActive()) || unit.getProductID() == null) {
                continue;
            }
            unitsByProduct.computeIfAbsent(unit.getProductID().getProductID(), id -> new ArrayList<>()).add(unit);
        }

        List<SellProductOptionResponse> options = new ArrayList<>();
        for (Product product : productRepository.findAllWithRelations()) {
            if (!Boolean.TRUE.equals(product.getStatus())) {
                continue;
            }
            List<Productunit> units = unitsByProduct.getOrDefault(product.getProductID(), List.of());
            if (units.isEmpty()) {
                continue;
            }

            List<SellUnitOptionResponse> unitOptions = units.stream()
                    .sorted(Comparator.comparing(Productunit::getRatio,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(unit -> new SellUnitOptionResponse(
                            unit.getId(),
                            unit.getUnitName(),
                            unit.getRatio(),
                            unit.getSellPrice(),
                            Boolean.TRUE.equals(unit.getIsDefault())))
                    .toList();

            List<SellBatchOptionResponse> batchOptions = batchRepository
                    .findInStockBatchesByProductForSale(product.getProductID())
                    .stream()
                    .map(batch -> new SellBatchOptionResponse(
                            batch.getId(),
                            batch.getBatchCode(),
                            batch.getLotNumber(),
                            batch.getExpirationDate(),
                            formatLocalDate(batch.getExpirationDate()),
                            batch.getStorageQuantity(),
                            isBatchExpired(batch)))
                    .toList();

            long baseStock = batchOptions.stream()
                    .mapToLong(batch -> batch.getStorageQuantity() != null ? batch.getStorageQuantity() : 0L)
                    .sum();
            if (baseStock <= 0) {
                continue;
            }

            options.add(new SellProductOptionResponse(
                    product.getProductID(),
                    product.getCode(),
                    product.getName(),
                    product.getBarcode(),
                    baseStock,
                    unitOptions,
                    batchOptions,
                    isPrescriptionProduct(product),
                    positionsByProduct.getOrDefault(product.getProductID(), List.of())));
        }

        options.sort(Comparator.comparing(SellProductOptionResponse::getName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return options;
    }

    /** Danh sách khách hàng cho dropdown trên form bán hàng. */
    @Transactional(readOnly = true)
    public List<CustomerOptionResponse> listCustomers() {
        return customerRepository.findAll().stream()
                .sorted(Comparator.comparing(Customer::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(customer -> new CustomerOptionResponse(
                        customer.getId(), customer.getName(), customer.getPhoneNumber(), customer.getCustomerType()))
                .toList();
    }

    /**
     * Tạo hóa đơn bán hàng: validate, trừ tồn kho theo lô FEFO, tính tiền và gắn ca bán.
     * {@code allowDebt}: Dược sĩ không được ghi nợ; Chủ nhà thuốc được phép.
     */
    @Transactional
    public Integer createSaleInvoice(InvoiceCreateRequest request, Integer currentAccountId, boolean allowDebt) {
        if (request.getDetails() == null || request.getDetails().isEmpty()) {
            throw new IllegalArgumentException("Hóa đơn phải có ít nhất một sản phẩm");
        }

        Account employee = accountRepository.findById(currentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản hiện tại"));

        Customer customer = null;
        if (request.getCustomerId() != null) {
            customer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khách hàng"));
        }

        boolean prescriptionRequired = Boolean.TRUE.equals(request.getPrescriptionRequired());
        String prescriptionCode = trimToNull(request.getPrescriptionCode());
        if (prescriptionRequired && prescriptionCode == null) {
            throw new IllegalArgumentException("Vui lòng nhập mã đơn thuốc cho hóa đơn kê đơn");
        }
        if (prescriptionRequired && !prescriptionCode.matches("^[A-Za-z0-9]{12}-[cnhy]$")) {
            throw new IllegalArgumentException(
                    "Mã đơn thuốc không đúng định dạng (12 ký tự chữ/số, dấu \"-\", rồi c/n/h/y)");
        }
        if (invoiceContainsPrescriptionProduct(request.getDetails())) {
            if (!prescriptionRequired) {
                throw new IllegalArgumentException("Hóa đơn có thuốc kê đơn — vui lòng tick \"Hóa đơn thuốc kê đơn\"");
            }
            if (prescriptionCode == null) {
                throw new IllegalArgumentException("Vui lòng nhập mã đơn thuốc để bán thuốc kê đơn");
            }
        }
        if (!prescriptionRequired && prescriptionCode != null) {
            prescriptionCode = null;
        }

        LocalDateTime invoiceDateTime = LocalDateTime.now(VN_ZONE);

        Invoice invoice = new Invoice();
        invoice.setInvoicePattern(buildInvoicePattern(invoiceDateTime.toLocalDate()));
        invoice.setInvoiceNumber(generateInvoiceNumber());
        // Lưu giờ tường VN trực tiếp (giống ProcurementplanService dùng LocalDateTime.now(VN_ZONE)).
        invoice.setDate(invoiceDateTime);
        invoice.setEmployeeID(employee);
        invoice.setCustomerID(customer);
        invoice.setInvoiceType(INVOICE_TYPE_NORMAL);
        invoice.setPrescriptionRequired(prescriptionRequired);
        invoice.setPrescriptionCode(prescriptionCode);
        invoice.setNote(trimToNull(request.getNote()));
        // Cột tiền NOT NULL phải có giá trị trước flush đầu (dòng chi tiết bên dưới cần id hóa đơn);
        // số liệu thật được tính và lưu lại sau khi tính giá từng dòng.
        invoice.setSubtotal(BigDecimal.ZERO);
        invoice.setDiscount(BigDecimal.ZERO);
        invoice.setTotal(BigDecimal.ZERO);
        invoice.setPaidByCash(BigDecimal.ZERO);
        invoice.setPaidByBanking(BigDecimal.ZERO);
        invoice.setDebtAmount(BigDecimal.ZERO);
        invoice.setStatus(STATUS_COMPLETED);

        Invoice savedInvoice = saveInvoiceGuardingConcurrentEdit(invoice);

        BigDecimal subtotal = BigDecimal.ZERO;
        for (InvoiceDetailCreateRequest item : request.getDetails()) {
            subtotal = subtotal.add(saveLineAndDeductStock(savedInvoice, item));
        }

        BigDecimal discount = maxZero(request.getDiscount());
        if (discount.compareTo(subtotal) > 0) {
            throw new IllegalArgumentException("Giảm giá không được lớn hơn tiền hàng");
        }
        BigDecimal total = subtotal.subtract(discount);

        BigDecimal paidByCash = maxZero(request.getPaidByCash());
        BigDecimal paidByBanking = maxZero(request.getPaidByBanking());
        BigDecimal paid = paidByCash.add(paidByBanking);
        if (paid.compareTo(total) > 0) {
            throw new IllegalArgumentException("Số tiền thanh toán không được lớn hơn tổng tiền hóa đơn");
        }

        BigDecimal debt = total.subtract(paid);
        if (debt.compareTo(BigDecimal.ZERO) > 0 && !allowDebt) {
            throw new IllegalArgumentException("Phải thu đủ tiền, không được ghi nợ");
        }
        if (debt.compareTo(BigDecimal.ZERO) > 0 && customer == null) {
            throw new IllegalArgumentException("Khách lẻ phải thanh toán đủ; chọn khách hàng để ghi nợ");
        }

        savedInvoice.setSubtotal(subtotal);
        savedInvoice.setDiscount(discount);
        savedInvoice.setTotal(total);
        savedInvoice.setPaidByCash(paidByCash);
        savedInvoice.setPaidByBanking(paidByBanking);
        savedInvoice.setDebtAmount(debt);
        savedInvoice.setStatus(debt.compareTo(BigDecimal.ZERO) > 0 ? STATUS_DEBT : STATUS_COMPLETED);

        // Giao dịch bán được ghi nhận ngay khi lưu hóa đơn, bất kể hình thức thanh toán —
        // mở/tái sử dụng ca bán và gắn vào hóa đơn (không phụ thuộc số tiền).
        savedInvoice.setShiftReportID(shiftreportService.ensureOpenShiftFor(currentAccountId));

        saveInvoiceGuardingConcurrentEdit(savedInvoice);

        financialsettingService.applyFundDelta(paidByCash, paidByBanking);

        return savedInvoice.getId();
    }

    /**
     * Lưu một dòng hàng bán: trừ tồn kho, tạo {@link Invoicedetail} và trả tiền hàng của dòng.
     * Nếu FEFO trải nhiều lô thì tách thành nhiều dòng chi tiết.
     */
    private BigDecimal saveLineAndDeductStock(Invoice invoice, InvoiceDetailCreateRequest item) {
        if (item.getProductId() == null || item.getProductUnitId() == null) {
            throw new IllegalArgumentException("Dòng hàng chưa chọn sản phẩm hoặc đơn vị bán");
        }
        if (item.getQuantity() == null || item.getQuantity() <= 0) {
            throw new IllegalArgumentException("Số lượng bán phải lớn hơn 0");
        }

        Product product = productRepository.findDetailById(item.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm"));
        Productunit unit = productunitRepository.findById(item.getProductUnitId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn vị bán"));
        if (unit.getProductID() == null || !product.getProductID().equals(unit.getProductID().getProductID())) {
            throw new IllegalArgumentException("Đơn vị bán không thuộc sản phẩm đã chọn");
        }

        BigDecimal ratio = unit.getRatio() != null && unit.getRatio().compareTo(BigDecimal.ZERO) > 0
                ? unit.getRatio() : BigDecimal.ONE;
        int quantity = item.getQuantity();
        int baseQty = ratio.multiply(BigDecimal.valueOf(quantity)).setScale(0, RoundingMode.HALF_UP).intValue();

        BigDecimal unitSellPrice = unit.getSellPrice() != null ? unit.getSellPrice() : BigDecimal.ZERO;
        BigDecimal lineSubtotal = unitSellPrice.multiply(BigDecimal.valueOf(quantity));

        List<BatchAllocation> allocations = deductStock(
                product, baseQty, quantity, ratio, unit.getUnitName(), item.getBatchId());

        if (allocations.size() <= 1) {
            Batch batch = allocations.isEmpty() ? null : allocations.get(0).batch();
            Invoicedetail detail = new Invoicedetail();
            detail.setInvoiceID(invoice);
            detail.setProductID(product);
            detail.setProductUnitID(unit);
            detail.setBatchID(batch);
            detail.setQuantity(quantity);
            detail.setUnitName(unit.getUnitName());
            detail.setBaseQtyDeducted(baseQty);
            detail.setUnitSellPrice(unitSellPrice);
            detail.setSubtotal(lineSubtotal);
            detail.setReturnedQty(0);
            detail.setNote(trimToNull(item.getNote()));
            invoicedetailRepository.save(detail);
            return lineSubtotal;
        }

        BigDecimal remainingSubtotal = lineSubtotal;
        int remainingBaseQty = baseQty;
        int remainingSellQty = quantity;

        for (int i = 0; i < allocations.size(); i++) {
            BatchAllocation allocation = allocations.get(i);
            boolean lastChunk = i == allocations.size() - 1;
            int chunkBaseQty = allocation.baseQtyTaken();

            int chunkSellQty;
            if (lastChunk) {
                chunkSellQty = remainingSellQty;
            } else {
                BigDecimal share = BigDecimal.valueOf(chunkBaseQty)
                        .divide(BigDecimal.valueOf(remainingBaseQty == 0 ? 1 : remainingBaseQty),
                                10, RoundingMode.HALF_UP);
                chunkSellQty = BigDecimal.valueOf(quantity)
                        .multiply(share)
                        .setScale(0, RoundingMode.HALF_UP)
                        .intValue();
            }

            BigDecimal chunkSubtotal;
            if (lastChunk) {
                chunkSubtotal = remainingSubtotal;
            } else {
                BigDecimal share = BigDecimal.valueOf(chunkBaseQty)
                        .divide(BigDecimal.valueOf(remainingBaseQty == 0 ? 1 : remainingBaseQty),
                                10, RoundingMode.HALF_UP);
                chunkSubtotal = lineSubtotal.multiply(share).setScale(2, RoundingMode.HALF_UP);
            }

            Invoicedetail detail = new Invoicedetail();
            detail.setInvoiceID(invoice);
            detail.setProductID(product);
            detail.setProductUnitID(unit);
            detail.setBatchID(allocation.batch());
            detail.setQuantity(chunkSellQty);
            detail.setUnitName(unit.getUnitName());
            detail.setBaseQtyDeducted(chunkBaseQty);
            detail.setUnitSellPrice(unitSellPrice);
            detail.setSubtotal(chunkSubtotal);
            detail.setReturnedQty(0);
            detail.setNote(trimToNull(item.getNote()));
            invoicedetailRepository.save(detail);

            remainingSubtotal = remainingSubtotal.subtract(chunkSubtotal);
            remainingBaseQty -= chunkBaseQty;
            remainingSellQty -= chunkSellQty;
        }

        return lineSubtotal;
    }

    /** Phần trừ tồn của một lô trong một dòng hàng (FEFO). */
    private record BatchAllocation(Batch batch, int baseQtyTaken) {}

    /** Trừ {@code baseQty} từ lô chỉ định hoặc FEFO trên nhiều lô (FIFO theo ngày nhập trong cùng HSD). */
    private List<BatchAllocation> deductStock(Product product, int baseQty, int sellQuantity,
                                                BigDecimal ratio, String unitName, Integer batchId) {
        if (batchId != null) {
            Batch batch = batchRepository.findById(batchId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lô hàng"));
            if (batch.getProductID() == null
                    || !product.getProductID().equals(batch.getProductID().getProductID())) {
                throw new IllegalArgumentException("Lô hàng không thuộc sản phẩm đã chọn");
            }
            if (!Boolean.TRUE.equals(batch.getStatus())) {
                throw new IllegalArgumentException("Lô hàng không còn hoạt động");
            }
            if (isBatchExpired(batch)) {
                String code = batch.getBatchCode() != null ? batch.getBatchCode() : String.valueOf(batch.getId());
                String hsd = formatLocalDate(batch.getExpirationDate());
                throw new IllegalArgumentException("Lô \"" + code + "\" đã hết hạn sử dụng"
                        + (hsd.isBlank() ? "" : " (" + hsd + ") — không thể bán"));
            }
            int inBatch = batch.getStorageQuantity() == null ? 0 : batch.getStorageQuantity();
            if (inBatch < baseQty) {
                BigDecimal safeRatio = ratio != null && ratio.compareTo(BigDecimal.ZERO) > 0
                        ? ratio : BigDecimal.ONE;
                long availableInUnit = BigDecimal.valueOf(inBatch)
                        .divide(safeRatio, 0, RoundingMode.DOWN).longValue();
                String unit = unitName != null ? unitName : "";
                String code = batch.getBatchCode() != null ? batch.getBatchCode() : String.valueOf(batch.getId());
                throw new IllegalArgumentException("Lô \"" + code + "\" của \"" + product.getName()
                        + "\" không đủ tồn (còn " + availableInUnit + " " + unit
                        + ", cần " + sellQuantity + " " + unit + ")");
            }
            batch.setStorageQuantity(inBatch - baseQty);
            batchRepository.save(batch);

            scheduleInventoryAlert(
                    product,
                    batch
            );

            return List.of(
                    new BatchAllocation(
                            batch,
                            baseQty
                    )
            );
        }

        List<Batch> batches = batchRepository.findInStockBatchesByProductForSale(product.getProductID());
        long available = batches.stream()
                .filter(batch -> !isBatchExpired(batch))
                .mapToLong(batch -> batch.getStorageQuantity() == null ? 0 : batch.getStorageQuantity())
                .sum();
        if (available < baseQty) {
            if (hasOnlyExpiredStock(batches)) {
                throwNoSellableBatchStock(product);
            }
            BigDecimal safeRatio = ratio != null && ratio.compareTo(BigDecimal.ZERO) > 0 ? ratio : BigDecimal.ONE;
            long availableInUnit = BigDecimal.valueOf(available)
                    .divide(safeRatio, 0, RoundingMode.DOWN).longValue();
            String unit = unitName != null ? unitName : "";
            throw new IllegalArgumentException("Sản phẩm \"" + product.getName()
                    + "\" không đủ tồn kho (còn " + availableInUnit + " " + unit
                    + ", cần " + sellQuantity + " " + unit + ")");
        }

        List<BatchAllocation> allocations = new ArrayList<>();
        int remaining = baseQty;
        for (Batch batch : batches) {
            if (remaining <= 0) {
                break;
            }
            if (isBatchExpired(batch)) {
                continue;
            }
            int inBatch = batch.getStorageQuantity() == null ? 0 : batch.getStorageQuantity();
            if (inBatch <= 0) {
                continue;
            }
            int take = Math.min(inBatch, remaining);
            batch.setStorageQuantity(inBatch - take);
            batchRepository.save(batch);

            scheduleInventoryAlert(
                    product,
                    batch
            );

            allocations.add(
                    new BatchAllocation(
                            batch,
                            take
                    )
            );

            remaining -= take;
        }
        return allocations;
    }

    /** Lên lịch kiểm tra cảnh báo tồn sau khi trừ lô — chạy sau commit giao dịch. */
    private void scheduleInventoryAlert(
            Product product,
            Batch batch
    ) {
        if (inventoryAlertEventService == null
                || product == null
                || batch == null) {
            return;
        }

        inventoryAlertEventService
                .checkBatchAfterCommit(
                        product.getProductID(),
                        batch.getId()
                );
    }

    /** Số hóa đơn bán hàng: 8 chữ số, không prefix (vd. 00008131). */
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

    /**
     * Ký hiệu hóa đơn 7 ký tự: 2 (bán hàng) + K (không mã CQT) + YY (năm) + M (máy tính tiền) + AA.
     * Hai ký tự cuối lấy từ {@code vatInvoiceSeries} trong thiết lập tài chính.
     */
    private String buildInvoicePattern(LocalDate date) {
        String vatInvoiceSeries = financialsettingRepository.findFirstByOrderByIdAsc()
                .map(setting -> setting.getVatInvoiceSeries())
                .orElse(null);
        if (vatInvoiceSeries == null || vatInvoiceSeries.isBlank()) {
            throw new IllegalArgumentException("Chưa cấu hình ký hiệu mẫu số hóa đơn (vatInvoiceSeries)");
        }

        String series = vatInvoiceSeries.trim().toUpperCase(Locale.ROOT);
        if (series.length() < 2) {
            throw new IllegalArgumentException("Ký hiệu mẫu số hóa đơn phải có ít nhất 2 ký tự chữ cái cuối");
        }
        String sellerSuffix = series.substring(series.length() - 2);
        if (!sellerSuffix.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException(
                    "Hai ký tự cuối của ký hiệu mẫu số hóa đơn phải là chữ cái (VD: AA, YY)");
        }

        String yearPart = String.format("%02d", date.getYear() % 100);
        return "2K" + yearPart + "M" + sellerSuffix;
    }

    /** Trả 0 nếu {@code value} null hoặc âm. */
    private BigDecimal maxZero(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    /** Trim chuỗi; trả {@code null} nếu rỗng. */
    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Kiểm tra sản phẩm thuộc loại thuốc kê đơn. */
    private boolean isPrescriptionProduct(Product product) {
        if (product == null || product.getTypeID() == null || product.getTypeID().getName() == null) {
            return false;
        }
        return PRESCRIPTION_PRODUCT_TYPE.equalsIgnoreCase(product.getTypeID().getName().trim());
    }

    /** Kiểm tra form bán có ít nhất một dòng thuốc kê đơn. */
    private boolean invoiceContainsPrescriptionProduct(List<InvoiceDetailCreateRequest> details) {
        if (details == null) {
            return false;
        }
        for (InvoiceDetailCreateRequest item : details) {
            if (item == null || item.getProductId() == null
                    || item.getQuantity() == null || item.getQuantity() <= 0) {
                continue;
            }
            Product product = productRepository.findDetailById(item.getProductId()).orElse(null);
            if (isPrescriptionProduct(product)) {
                return true;
            }
        }
        return false;
    }

    /** Các dòng hàng của một hóa đơn — hộp thoại xem nhanh trên danh sách (dạng JSON). */
    @Transactional(readOnly = true)
    public List<InvoiceLineResponse> loadLines(Integer invoiceId) {
        if (!invoiceRepository.existsById(invoiceId)) {
            throw new IllegalArgumentException("Không tìm thấy hóa đơn");
        }
        return invoicedetailRepository.findByInvoiceIdWithRelations(invoiceId).stream()
                .map(this::toLine)
                .toList();
    }

    /** Chi tiết hóa đơn bán hàng — trang xem chi tiết (không hiển thị giá vốn). */
    @Transactional(readOnly = true)
    public InvoiceDetailPageResponse getDetail(Integer invoiceId) {
        return getDetail(invoiceId, false);
    }

    /**
     * Chi tiết hóa đơn bán hàng — trang xem chi tiết.
     * {@code includeCostForOwner}: Chủ nhà thuốc thấy thêm giá vốn và lợi nhuận.
     */
    public InvoiceDetailPageResponse getDetail(Integer invoiceId, boolean includeCostForOwner) {
        Invoice invoice = invoiceRepository.findByIdWithRelations(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn"));

        List<Invoicedetail> lines = invoicedetailRepository.findByInvoiceIdWithRelations(invoiceId);
        Map<Integer, String> returnStates = returnStateByInvoice();
        String returnCode = returnStates.getOrDefault(invoiceId, RETURN_NONE);

        List<InvoiceDetailItemResponse> items = lines.stream()
                .map(this::toDetailItem)
                .toList();

        List<InvoiceDetailProductGroupResponse> productGroups = buildProductGroups(lines, includeCostForOwner);
        BigDecimal totalCost = includeCostForOwner ? sumLineCost(lines) : null;

        int totalQuantity = items.stream()
                .map(InvoiceDetailItemResponse::getQuantity)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        Customer customer = invoice.getCustomerID();
        Invoice original = invoice.getOriginalInvoiceID();

        Map<Integer, String> returnSlips = returnRepository
                .findByInvoiceID_IdOrderByReturnDateDesc(invoiceId)
                .stream()
                .collect(Collectors.toMap(
                        ret -> ret.getId(),
                        ret -> ret.getReturnCode(),
                        (a, b) -> a,
                        LinkedHashMap::new));

        String statusName = invoice.getStatus() != null ? invoice.getStatus() : "Không rõ";
        Financialsetting setting = financialsettingRepository.findFirstByOrderByIdAsc().orElse(null);
        String taxCode = setting != null ? trimToNull(setting.getTaxCode()) : null;

        return new InvoiceDetailPageResponse(
                invoice.getId(),
                invoiceCode(invoice),
                invoice.getInvoicePattern(),
                taxCode,
                invoice.getDate(),
                formatDate(invoice.getDate()),
                customer != null ? customer.getName() : "Khách lẻ",
                customer != null ? customer.getPhoneNumber() : null,
                invoice.getEmployeeID() != null ? invoice.getEmployeeID().getName() : "Không rõ",
                invoiceTypeDisplay(invoice.getInvoiceType()),
                statusName,
                statusCssClass(invoice.getStatus()),
                Boolean.TRUE.equals(invoice.getPrescriptionRequired()),
                invoice.getPrescriptionCode(),
                returnStatusDisplay(returnCode),
                returnStatusCssClass(returnCode),
                returnSlips,
                original != null ? original.getId() : null,
                original != null ? invoiceCode(original) : null,
                invoice.getSubtotal(),
                invoice.getDiscount() != null ? invoice.getDiscount() : BigDecimal.ZERO,
                totalCost,
                invoice.getTotal(),
                invoice.getPaidByCash(),
                invoice.getPaidByBanking(),
                invoice.getDebtAmount() != null ? invoice.getDebtAmount() : BigDecimal.ZERO,
                paymentDisplay(invoice),
                invoice.getNote(),
                productGroups.size(),
                totalQuantity,
                items,
                productGroups);
    }

    /** Dữ liệu in hóa đơn đầy đủ. */
    @Transactional(readOnly = true)
    public InvoicePrintPageResponse getPrintPage(Integer invoiceId) {
        return getPrintPage(invoiceId, false);
    }

    /**
     * Dữ liệu in hóa đơn.
     * {@code aggregateLinesForReceipt}: gộp dòng hàng khi in phiếu thu nhỏ.
     */
    @Transactional(readOnly = true)
    public InvoicePrintPageResponse getPrintPage(Integer invoiceId, boolean aggregateLinesForReceipt) {
        Invoice invoice = invoiceRepository.findByIdWithRelations(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn"));

        List<Invoicedetail> lines = invoicedetailRepository.findByInvoiceIdWithRelations(invoiceId);

        int totalQuantity = lines.stream()
                .map(Invoicedetail::getQuantity)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        boolean signed = hasCqtCode(invoice);
        LocalDateTime signedAt = invoice.getDate();

        Financialsetting setting = financialsettingRepository.findFirstByOrderByIdAsc().orElse(null);
        Customer customer = invoice.getCustomerID();

        String retainedPercentDisplay = formatRetainedPercentDisplay(invoice);
        List<InvoicePrintLineResponse> printLines = lines.stream()
                .map(line -> toPrintLine(invoice, line, retainedPercentDisplay))
                .toList();
        if (aggregateLinesForReceipt) {
            printLines = aggregatePrintLinesForReceipt(printLines);
        }

        String buyerCompanyName;
        String buyerTaxCode;
        String buyerAddress;
        if (isRetailCustomer(customer)) {
            buyerCompanyName = RETAIL_BUYER_PRINT_LABEL;
            buyerTaxCode = "";
            buyerAddress = "";
        } else {
            buyerCompanyName = nullToEmpty(customer.getName());
            buyerTaxCode = nullToEmpty(customer.getTaxCode());
            buyerAddress = nullToEmpty(customer.getAddress());
        }

        BigDecimal printTotal = invoice.getTotal() != null ? invoice.getTotal() : BigDecimal.ZERO;

        return new InvoicePrintPageResponse(
                invoice.getId(),
                invoiceCode(invoice),
                invoice.getInvoicePattern(),
                formatInvoiceSerialNumber(invoice),
                invoiceTypeDisplay(invoice.getInvoiceType()),
                signed,
                formatDateLong(invoice.getDate()),
                formatDate(invoice.getDate()),
                pharmacyBrandShort(setting),
                receiptInvoiceCode(invoice),
                signed ? buildTaxAuthorityCode(invoice, setting) : null,
                signed ? formatSignedAt(signedAt) : null,
                setting != null ? nullToEmpty(setting.getLocationName()) : "",
                setting != null ? nullToEmpty(setting.getTaxCode()) : "",
                setting != null ? nullToEmpty(setting.getAddress()) : "",
                setting != null ? nullToEmpty(setting.getLocationCode()) : "",
                setting != null ? nullToEmpty(setting.getPhoneNumber()) : "",
                setting != null ? nullToEmpty(setting.getEmail()) : "",
                setting != null ? nullToEmpty(setting.getBankAccountNumber()) : "",
                setting != null ? nullToEmpty(setting.getBankName()) : "",
                buyerCompanyName,
                buyerTaxCode,
                buyerAddress,
                paymentMethodShort(invoice),
                moneyAmountInWords(printTotal),
                customer != null ? customer.getName() : "Khách lẻ",
                customer != null ? customer.getPhoneNumber() : null,
                invoice.getEmployeeID() != null ? invoice.getEmployeeID().getName() : "Không rõ",
                totalQuantity,
                invoice.getSubtotal(),
                invoice.getDiscount() != null ? invoice.getDiscount() : BigDecimal.ZERO,
                printTotal,
                invoice.getPaidByCash(),
                invoice.getPaidByBanking(),
                invoice.getDebtAmount() != null ? invoice.getDebtAmount() : BigDecimal.ZERO,
                paymentDisplay(invoice),
                invoice.getNote(),
                printLines);
    }

    /** Map một dòng hàng sang DTO in — đánh dấu dòng tiền giữ lại trên hóa đơn thay thế. */
    private InvoicePrintLineResponse toPrintLine(Invoice invoice, Invoicedetail line,
                                                 String retainedPercentDisplay) {
        Product product = line.getProductID();
        boolean retainedMoneyLine = isReplacementInvoice(invoice)
                && line.getQuantity() != null
                && line.getQuantity() == 0;
        return new InvoicePrintLineResponse(
                product != null ? product.getCode() : "",
                product != null ? product.getName() : "Không rõ",
                line.getUnitName(),
                line.getQuantity(),
                line.getUnitSellPrice(),
                line.getSubtotal(),
                trimToNull(line.getNote()),
                retainedMoneyLine,
                retainedMoneyLine ? retainedPercentDisplay : null);
    }

    /**
     * Gộp các dòng cùng sản phẩm/đơn vị/giá trên phiếu in bán hàng (khi trừ tồn nhiều lô tạo nhiều
     * {@code Invoicedetail}).
     */
    private List<InvoicePrintLineResponse> aggregatePrintLinesForReceipt(
            List<InvoicePrintLineResponse> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, InvoicePrintLineResponse> merged = new LinkedHashMap<>();
        List<InvoicePrintLineResponse> retainedLines = new ArrayList<>();

        for (InvoicePrintLineResponse line : lines) {
            if (line.isRetainedMoneyLine()) {
                retainedLines.add(line);
                continue;
            }
            String key = printLineAggregateKey(line);
            merged.merge(key, line, this::mergePrintLines);
        }

        List<InvoicePrintLineResponse> result = new ArrayList<>(merged.values());
        result.addAll(retainedLines);
        return result;
    }

    /** Khóa gộp dòng in: mã SP + tên + đơn vị + giá + ghi chú. */
    private String printLineAggregateKey(InvoicePrintLineResponse line) {
        return nullToEmpty(line.getProductCode()) + "\0"
                + nullToEmpty(line.getProductName()) + "\0"
                + nullToEmpty(line.getUnitName()) + "\0"
                + (line.getUnitSellPrice() != null ? line.getUnitSellPrice().toPlainString() : "") + "\0"
                + nullToEmpty(line.getNote());
    }

    /** Cộng số lượng và thành tiền hai dòng in cùng khóa gộp. */
    private InvoicePrintLineResponse mergePrintLines(InvoicePrintLineResponse left,
                                                     InvoicePrintLineResponse right) {
        int quantity = safeQuantity(left.getQuantity()) + safeQuantity(right.getQuantity());
        BigDecimal subtotal = safeMoney(left.getLineSubtotal()).add(safeMoney(right.getLineSubtotal()));
        return new InvoicePrintLineResponse(
                left.getProductCode(),
                left.getProductName(),
                left.getUnitName(),
                quantity,
                left.getUnitSellPrice(),
                subtotal,
                left.getNote(),
                false,
                null);
    }

    /** Số lượng an toàn — null coi là 0. */
    private int safeQuantity(Integer quantity) {
        return quantity != null ? quantity : 0;
    }

    /** Số tiền an toàn — null coi là 0. */
    private BigDecimal safeMoney(BigDecimal amount) {
        return amount != null ? amount : BigDecimal.ZERO;
    }

    /** Hóa đơn loại "Thay thế" (sinh sau phiếu trả). */
    private boolean isReplacementInvoice(Invoice invoice) {
        return invoice != null && INVOICE_TYPE_REPLACEMENT.equals(invoice.getInvoiceType());
    }

    /**
     * Tỷ lệ % nhà thuốc giữ lại trên phiếu trả (= 100% − {@code Return.appliedRefundRate}), dùng trên
     * phiếu in hóa đơn thay thế.
     */
    private String formatRetainedPercentDisplay(Invoice invoice) {
        if (!isReplacementInvoice(invoice) || invoice.getReturnID() == null) {
            return null;
        }
        BigDecimal refundRate = invoice.getReturnID().getAppliedRefundRate();
        if (refundRate == null || refundRate.compareTo(FULL_REFUND_RATE) >= 0) {
            return null;
        }
        BigDecimal retainedRate = FULL_REFUND_RATE.subtract(refundRate).max(BigDecimal.ZERO);
        return retainedRate.stripTrailingZeros().toPlainString().replace('.', ',') + "%";
    }

    /** Gom các dòng chi tiết theo sản phẩm — khối hiển thị trên trang chi tiết. */
    private List<InvoiceDetailProductGroupResponse> buildProductGroups(List<Invoicedetail> lines,
                                                                     boolean includeCostForOwner) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }

        Map<Integer, List<Invoicedetail>> grouped = new LinkedHashMap<>();
        for (Invoicedetail line : lines) {
            Product product = line.getProductID();
            if (product == null) {
                continue;
            }
            grouped.computeIfAbsent(product.getProductID(), ignored -> new ArrayList<>()).add(line);
        }

        return grouped.values().stream()
                .map(productLines -> {
                    Product product = productLines.get(0).getProductID();
                    List<InvoiceDetailUnitLineResponse> unitLines = productLines.stream()
                            .map(line -> toUnitLine(line, includeCostForOwner))
                            .toList();
                    BigDecimal productSubtotal = productLines.stream()
                            .map(Invoicedetail::getSubtotal)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new InvoiceDetailProductGroupResponse(
                            product.getProductID(),
                            product.getCode() != null ? product.getCode() : "",
                            product.getName() != null ? product.getName() : "Không rõ",
                            productSubtotal,
                            unitLines);
                })
                .toList();
    }

    /** Map một dòng chi tiết sang DTO đơn vị bán trong khối sản phẩm. */
    private InvoiceDetailUnitLineResponse toUnitLine(Invoicedetail line, boolean includeCostForOwner) {
        Productunit unit = line.getProductUnitID();
        BigDecimal unitCostPrice = includeCostForOwner ? unitCostPrice(line) : null;
        return new InvoiceDetailUnitLineResponse(
                unit != null ? unit.getId() : null,
                line.getUnitName(),
                false,
                line.getQuantity(),
                line.getUnitSellPrice(),
                line.getSubtotal(),
                line.getReturnedQty() != null ? line.getReturnedQty() : 0,
                formatBatchLabel(line.getBatchID()),
                trimToNull(line.getNote()),
                unitCostPrice);
    }

    /** Tổng giá vốn tất cả dòng hàng trên hóa đơn. */
    private BigDecimal sumLineCost(List<Invoicedetail> lines) {
        return lines.stream()
                .map(this::lineCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Giá vốn / đơn vị bán = {@code batch.importPricePerBase × productUnit.ratio}.
     */
    private BigDecimal unitCostPrice(Invoicedetail line) {
        Batch batch = line.getBatchID();
        if (batch == null || batch.getImportPricePerBase() == null) {
            return BigDecimal.ZERO;
        }
        Productunit unit = line.getProductUnitID();
        BigDecimal ratio = unit != null && unit.getRatio() != null
                && unit.getRatio().compareTo(BigDecimal.ZERO) > 0
                ? unit.getRatio()
                : BigDecimal.ONE;
        return batch.getImportPricePerBase()
                .multiply(ratio)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Thành tiền vốn dòng = {@code baseQtyDeducted × batch.importPricePerBase}. */
    private BigDecimal lineCost(Invoicedetail line) {
        Batch batch = line.getBatchID();
        if (batch == null || batch.getImportPricePerBase() == null || line.getBaseQtyDeducted() == null) {
            return BigDecimal.ZERO;
        }
        return batch.getImportPricePerBase()
                .multiply(BigDecimal.valueOf(line.getBaseQtyDeducted()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Map một dòng chi tiết sang DTO bảng phẳng trên trang chi tiết. */
    private InvoiceDetailItemResponse toDetailItem(Invoicedetail line) {
        Product product = line.getProductID();
        Batch batch = line.getBatchID();
        Productunit unit = line.getProductUnitID();

        return new InvoiceDetailItemResponse(
                product != null ? product.getProductID() : null,
                product != null ? product.getCode() : "",
                product != null ? product.getName() : "Không rõ",
                batch != null ? batch.getBatchCode() : "",
                batch != null ? batchLotNumber(batch) : "",
                batch != null ? formatLocalDate(batch.getExpirationDate()) : "",
                formatBatchLabel(batch),
                line.getUnitName(),
                unit != null && Boolean.TRUE.equals(unit.getIsDefault()),
                line.getQuantity(),
                line.getUnitSellPrice(),
                line.getSubtotal(),
                line.getReturnedQty() != null ? line.getReturnedQty() : 0,
                trimToNull(line.getNote()));
    }

    /** Số lô hiển thị — ưu tiên {@code lotNumber}, fallback {@code batchCode}. */
    private String batchLotNumber(Batch batch) {
        if (batch == null) {
            return "";
        }
        String lot = batch.getLotNumber();
        if (lot != null && !lot.isBlank()) {
            return lot;
        }
        return batch.getBatchCode() != null ? batch.getBatchCode() : "";
    }

    /** Nhãn lô hiển thị: mã lô kèm HSD (vd. L001 - 31/12/2026). */
    private String formatBatchLabel(Batch batch) {
        if (batch == null) {
            return "";
        }
        String code = batch.getBatchCode() != null ? batch.getBatchCode() : "";
        String hsd = formatLocalDate(batch.getExpirationDate());
        if (hsd != null && !hsd.isBlank()) {
            return code + " - " + hsd;
        }
        return code;
    }

    /** Map {@link Invoice} sang một dòng trên danh sách hóa đơn. */
    private InvoiceListItemResponse toListItem(Invoice invoice, Map<Integer, String> returnStates) {
        String statusName = invoice.getStatus() != null ? invoice.getStatus() : "Không rõ";
        String returnCode = returnStates.getOrDefault(invoice.getId(), RETURN_NONE);

        return new InvoiceListItemResponse(
                invoice.getId(),
                invoiceCode(invoice),
                invoice.getDate(),
                formatDate(invoice.getDate()),
                invoice.getCustomerID() != null ? invoice.getCustomerID().getName() : "Khách lẻ",
                invoice.getEmployeeID() != null ? invoice.getEmployeeID().getName() : "Không rõ",
                invoiceTypeDisplay(invoice.getInvoiceType()),
                invoice.getTotal(),
                invoice.getDebtAmount(),
                paymentDisplay(invoice),
                Boolean.TRUE.equals(invoice.getPrescriptionRequired()),
                returnStatusDisplay(returnCode),
                returnStatusCssClass(returnCode),
                statusName,
                statusCssClass(statusName));
    }

    /** Map một dòng chi tiết sang DTO hộp thoại xem nhanh trên danh sách. */
    private InvoiceLineResponse toLine(Invoicedetail line) {
        Product product = line.getProductID();
        Batch batch = line.getBatchID();

        return new InvoiceLineResponse(
                line.getId(),
                product != null ? product.getName() : "Không rõ",
                batch != null ? batch.getLotNumber() : "",
                batch != null ? formatLocalDate(batch.getExpirationDate()) : "",
                line.getUnitName(),
                line.getQuantity(),
                line.getUnitSellPrice(),
                line.getSubtotal(),
                line.getReturnedQty() != null ? line.getReturnedQty() : 0,
                trimToNull(line.getNote()));
    }

    /** Số hóa đơn hiển thị (cột {@code invoiceNumber}). */
    private String invoiceCode(Invoice invoice) {
        String number = invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber().trim() : "";
        return number.isEmpty() ? "—" : number;
    }

    /** Mã hiển thị trên phiếu in POS: số hóa đơn 8 chữ số (vd. 00000018). */
    private String receiptInvoiceCode(Invoice invoice) {
        if (invoice == null) {
            return "—";
        }
        String serial = formatInvoiceSerialNumber(invoice);
        return serial.isEmpty() ? "—" : serial;
    }

    /** Tên thương hiệu ngắn lấy từ phần trước @ trong email (vd. nhathuochangngoc). */
    private String pharmacyBrandShort(Financialsetting setting) {
        if (setting == null) {
            return "";
        }
        String email = setting.getEmail();
        if (email != null && !email.isBlank()) {
            String normalized = email.trim().toLowerCase(Locale.ROOT);
            int at = normalized.indexOf('@');
            if (at > 0) {
                return normalized.substring(0, at).replaceAll("\\d+$", "");
            }
        }
        String locationName = setting.getLocationName();
        if (locationName != null && !locationName.isBlank()) {
            return locationName.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        }
        return "";
    }

    /** Lọc theo từ khóa: mã hóa đơn, tên khách, trạng thái hoặc ghi chú. */
    private boolean matchesKeyword(Invoice invoice, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        return containsNormalized(invoiceCode(invoice), normalizedKeyword)
                || containsNormalized(invoice.getCustomerID() != null
                        ? invoice.getCustomerID().getName() : null, normalizedKeyword)
                || containsNormalized(invoice.getStatus(), normalizedKeyword)
                || containsNormalized(invoice.getNote(), normalizedKeyword);
    }

    /** Lọc theo người bán (nhân viên lập hóa đơn). */
    private boolean matchesSeller(Invoice invoice, Integer sellerId) {
        if (sellerId == null) {
            return true;
        }
        return invoice.getEmployeeID() != null
                && sellerId.equals(invoice.getEmployeeID().getId());
    }

    /** Lọc theo khoảng ngày trên danh sách. */
    private boolean matchesDate(Invoice invoice, LocalDate from, LocalDate to) {
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

    /** Lọc theo hình thức thanh toán (tiền mặt / chuyển khoản / hỗn hợp / ghi nợ). */
    private boolean matchesPaymentType(Invoice invoice, String paymentType) {
        if (paymentType == null || paymentType.isBlank()) {
            return true;
        }
        boolean cash = isPositive(invoice.getPaidByCash());
        boolean banking = isPositive(invoice.getPaidByBanking());
        boolean debt = isPositive(invoice.getDebtAmount());
        return switch (paymentType.toUpperCase(Locale.ROOT)) {
            case PAYMENT_CASH -> cash && !banking;
            case PAYMENT_BANKING -> banking && !cash;
            case PAYMENT_MIXED -> cash && banking;
            case PAYMENT_DEBT -> debt;
            default -> true;
        };
    }

    /** Nhãn loại hóa đơn hiển thị — chuẩn hóa giá trị legacy. */
    private String invoiceTypeDisplay(String invoiceType) {
        if (invoiceType == null || invoiceType.isBlank()) {
            return "—";
        }
        if (INVOICE_TYPE_NORMAL.equalsIgnoreCase(invoiceType)
                || INVOICE_TYPE_NORMAL_LEGACY.equalsIgnoreCase(invoiceType)
                || INVOICE_TYPE_VAT_LEGACY.equals(invoiceType)) {
            return "Bán hàng";
        }
        if (INVOICE_TYPE_ADJUSTMENT.equalsIgnoreCase(invoiceType)
                || INVOICE_TYPE_ADJUSTMENT_LEGACY.equalsIgnoreCase(invoiceType)) {
            return "Điều chỉnh";
        }
        return switch (invoiceType.toLowerCase(Locale.ROOT)) {
            case INVOICE_TYPE_RETURN -> "Trả hàng";
            default -> invoiceType;
        };
    }

    /** Viết tắt hình thức thanh toán trên mẫu in (TM / CK / TM/CK / Ghi nợ). */
    private String paymentMethodShort(Invoice invoice) {
        boolean cash = isPositive(invoice.getPaidByCash());
        boolean banking = isPositive(invoice.getPaidByBanking());
        if (cash && banking) {
            return "TM/CK";
        }
        if (cash) {
            return "TM";
        }
        if (banking) {
            return "CK";
        }
        if (isPositive(invoice.getDebtAmount())) {
            return "Ghi nợ";
        }
        return "TM";
    }

    /** Định dạng ngày dài trên mẫu in (vd. Ngày 24 tháng 06 năm 2026). */
    private String formatDateLong(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        LocalDate date = dateTime.toLocalDate();
        return String.format("Ngày %d tháng %02d năm %d",
                date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    /** dd/MM/yyyy — dùng trên khung chữ ký điện tử của phiếu in. */
    private String formatSignedAt(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy").format(dateTime);
    }

    /** Số hóa đơn 8 chữ số trên mẫu in — pad từ {@code invoiceNumber} hoặc id. */
    private String formatInvoiceSerialNumber(Invoice invoice) {
        if (invoice == null) {
            return "";
        }
        String number = invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber().trim() : "";
        String digits = number.replaceAll("\\D", "");
        if (digits.isEmpty() && invoice.getId() != null) {
            digits = String.valueOf(invoice.getId());
        }
        if (digits.isEmpty()) {
            return "";
        }
        try {
            return String.format("%08d", Long.parseLong(digits));
        } catch (NumberFormatException ex) {
            return digits;
        }
    }

    /** Ghép mã CQT trên mẫu in hóa đơn đã ký. */
    private String buildTaxAuthorityCode(Invoice invoice, Financialsetting setting) {
        if (invoice == null || invoice.getInvoicePattern() == null || invoice.getInvoicePattern().length() < 4) {
            return null;
        }
        String pattern = invoice.getInvoicePattern();
        char kind = pattern.charAt(0);
        String yearPart = pattern.substring(2, 4);
        String series = setting != null && setting.getVatInvoiceSeries() != null
                ? setting.getVatInvoiceSeries().trim().toUpperCase(Locale.ROOT)
                : "BGALS";
        if (series.length() < 5) {
            series = "BGALS";
        }
        int invoiceKey = invoice.getId() != null ? invoice.getId() : 0;
        return String.format("M%c-%s-%s-%011d", kind, yearPart, series, invoiceKey);
    }

    /** Đọc số tiền thành chữ tiếng Việt kèm hậu tố "đồng chẵn". */
    private String moneyAmountInWords(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        long value = amount.setScale(0, RoundingMode.HALF_UP).longValue();
        if (value == 0L) {
            return "Không đồng chẵn.";
        }
        if (value < 0L) {
            return "Âm " + capitalizeMoneyWords(readMoneyNumber(-value)) + " đồng chẵn.";
        }
        return capitalizeMoneyWords(readMoneyNumber(value)) + " đồng chẵn.";
    }

    /** Đọc số nguyên dương thành chữ — chia theo khối nghìn/triệu/tỷ. */
    private String readMoneyNumber(long number) {
        if (number == 0L) {
            return MONEY_WORD_DIGITS[0];
        }

        String[] units = {"", " nghìn", " triệu", " tỷ", " nghìn tỷ", " triệu tỷ"};
        StringBuilder result = new StringBuilder();
        int unitIndex = 0;

        while (number > 0L) {
            int chunk = (int) (number % 1000L);
            if (chunk != 0) {
                String chunkWords = readMoneyThreeDigits(chunk, unitIndex > 0);
                if (!result.isEmpty()) {
                    result.insert(0, chunkWords + units[unitIndex] + " ");
                } else {
                    result.insert(0, chunkWords + units[unitIndex]);
                }
            }
            number /= 1000L;
            unitIndex++;
        }

        return result.toString().trim();
    }

    /** Đọc một khối 3 chữ số (0–999) thành chữ tiếng Việt. */
    private String readMoneyThreeDigits(int number, boolean fullReading) {
        int hundreds = number / 100;
        int tens = (number % 100) / 10;
        int ones = number % 10;
        StringBuilder words = new StringBuilder();

        if (hundreds > 0) {
            words.append(MONEY_WORD_DIGITS[hundreds]).append(" trăm");
            if (tens == 0 && ones > 0) {
                words.append(" lẻ");
            }
        } else if (fullReading && (tens > 0 || ones > 0)) {
            words.append("không trăm");
        }

        if (tens > 1) {
            if (!words.isEmpty()) {
                words.append(' ');
            }
            words.append(MONEY_WORD_DIGITS[tens]).append(" mươi");
            if (ones == 1) {
                words.append(" mốt");
            } else if (ones == 4) {
                words.append(" tư");
            } else if (ones == 5) {
                words.append(" lăm");
            } else if (ones > 0) {
                words.append(' ').append(MONEY_WORD_DIGITS[ones]);
            }
        } else if (tens == 1) {
            if (!words.isEmpty()) {
                words.append(' ');
            }
            words.append("mười");
            if (ones == 5) {
                words.append(" lăm");
            } else if (ones > 0) {
                words.append(' ').append(MONEY_WORD_DIGITS[ones]);
            }
        } else if (ones > 0) {
            if (!words.isEmpty()) {
                words.append(' ');
            }
            if (hundreds > 0 || fullReading) {
                words.append("lẻ ");
            }
            words.append(MONEY_WORD_DIGITS[ones]);
        }

        return words.toString().trim();
    }

    /** Viết hoa chữ cái đầu chuỗi số tiền bằng chữ. */
    private String capitalizeMoneyWords(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Chuỗi an toàn — null/blank trả chuỗi rỗng. */
    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** Khách lẻ: không có bản ghi khách, hoặc bản ghi placeholder "Khách lẻ". */
    private boolean isRetailCustomer(Customer customer) {
        if (customer == null) {
            return true;
        }
        String name = nullToEmpty(customer.getName());
        return name.isBlank() || "Khách lẻ".equalsIgnoreCase(name);
    }

    /** Mô tả hình thức thanh toán đầy đủ trên danh sách/chi tiết. */
    private String paymentDisplay(Invoice invoice) {
        boolean cash = isPositive(invoice.getPaidByCash());
        boolean banking = isPositive(invoice.getPaidByBanking());
        boolean debt = isPositive(invoice.getDebtAmount());

        String paid;
        if (cash && banking) {
            paid = "TM + CK";
        } else if (cash) {
            paid = "Tiền mặt";
        } else if (banking) {
            paid = "Chuyển khoản";
        } else {
            paid = debt ? "Ghi nợ" : "—";
        }
        if (debt && (cash || banking)) {
            paid += " + Nợ";
        }
        return paid;
    }

    /** Nhãn trạng thái trả hàng hiển thị trên danh sách/chi tiết. */
    private String returnStatusDisplay(String returnStatus) {
        if (returnStatus == null || returnStatus.isBlank()) {
            return "Không";
        }
        return switch (returnStatus.toUpperCase(Locale.ROOT)) {
            case RETURN_PARTIAL -> "Trả một phần";
            case RETURN_FULL -> "Đã trả toàn bộ";
            default -> "Không";
        };
    }

    /** Class CSS badge trạng thái trả hàng. */
    private String returnStatusCssClass(String returnStatus) {
        if (returnStatus == null) {
            return "return-none";
        }
        return switch (returnStatus.toUpperCase(Locale.ROOT)) {
            case RETURN_PARTIAL -> "status-return-partial";
            case RETURN_FULL -> "status-return-full";
            default -> "status-default";
        };
    }

    /** Class CSS badge trạng thái hóa đơn. */
    private String statusCssClass(String statusName) {
        if (isStatus(statusName, STATUS_RETURNED_FULL)) {
            return "status-return-full";
        }
        if (isStatus(statusName, STATUS_RETURNED_PARTIAL)) {
            return "status-return-partial";
        }
        if (isStatus(statusName, STATUS_SIGNED)) {
            return "status-signed";
        }
        String normalized = normalize(statusName);
        if (normalized.contains("hoan thanh")) {
            return "status-completed";
        }
        if (normalized.contains("huy")) {
            return "status-cancelled";
        }
        if (isStatus(statusName, STATUS_DEBT) || normalized.contains("con no")) {
            return "status-debt";
        }
        return "status-default";
    }

    /** So sánh trạng thái không phân biệt dấu/hoa thường. */
    private boolean isStatus(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    /** Kiểu cũ: ký hiệu C (có mã CQT) hoặc trạng thái cũ "Đã ký". */
    private boolean hasCqtCode(Invoice invoice) {
        if (invoice == null) {
            return false;
        }
        if (isStatus(invoice.getStatus(), STATUS_SIGNED)) {
            return true;
        }
        String pattern = invoice.getInvoicePattern();
        return pattern != null && pattern.length() >= 2 && pattern.charAt(1) == 'C';
    }

    /** Kiểm tra số tiền dương. */
    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /** Định dạng ngày giờ hiển thị ({@code dd/MM/yyyy HH:mm}). */
    private String formatDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(dateTime);
    }

    /** Định dạng ngày ({@code dd/MM/yyyy}). */
    private String formatLocalDate(LocalDate date) {
        return date == null ? "" : date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    /** Hôm nay theo múi giờ Việt Nam. */
    private LocalDate todayInVn() {
        return LocalDate.now(VN_ZONE);
    }

    /** Lô đã quá HSD tính tới hôm nay (VN). Không có HSD thì coi như còn hạn. */
    private boolean isBatchExpired(Batch batch) {
        LocalDate expiry = batch.getExpirationDate();
        return expiry != null && expiry.isBefore(todayInVn());
    }

    /** Còn tồn nhưng toàn bộ lô đều hết hạn — không bán được. */
    private boolean hasOnlyExpiredStock(List<Batch> batches) {
        if (batches == null || batches.isEmpty()) {
            return false;
        }
        long totalStock = batches.stream()
                .mapToLong(batch -> batch.getStorageQuantity() == null ? 0 : batch.getStorageQuantity())
                .sum();
        if (totalStock <= 0) {
            return false;
        }
        long sellableStock = batches.stream()
                .filter(batch -> !isBatchExpired(batch))
                .mapToLong(batch -> batch.getStorageQuantity() == null ? 0 : batch.getStorageQuantity())
                .sum();
        return sellableStock == 0;
    }

    /** Ném lỗi khi sản phẩm chỉ còn lô hết hạn. */
    private void throwNoSellableBatchStock(Product product) {
        throw new IllegalArgumentException("Sản phẩm \"" + product.getName()
                + "\" đã hết lô còn hạn sử dụng — không thể bán.");
    }

    /** {@link LocalDateTime} → {@link LocalDate} (giờ tường VN, không chuyển múi giờ). */
    private LocalDate toLocalDate(LocalDateTime dateTime) {
        return dateTime.toLocalDate();
    }

    /** Parse chuỗi ngày ({@code yyyy-MM-dd}) từ bộ lọc form. */
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value);
    }

    /** Kiểm tra chuỗi chứa từ khóa đã chuẩn hóa. */
    private boolean containsNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).contains(normalizedKeyword);
    }

    /** Chuẩn hóa chuỗi tìm kiếm: bỏ dấu tiếng Việt, chữ thường. */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replace("Đ", "D").replace("đ", "d");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    /** Điểm vào cho service khác cập nhật hóa đơn đã tồn tại. */
    public Invoice persistInvoice(Invoice invoice) {
        return saveInvoiceGuardingConcurrentEdit(invoice);
    }

    /**
     * Lưu hóa đơn và bắt xung đột cập nhật đồng thời
     * (thu nợ, trả hàng, bù trừ công nợ từ nhiều phiên).
     */
    private Invoice saveInvoiceGuardingConcurrentEdit(Invoice invoice) {
        try {
            return invoiceRepository.saveAndFlush(invoice);
        } catch (ObjectOptimisticLockingFailureException exception) {
            String number = invoice.getInvoiceNumber() != null
                    ? invoice.getInvoiceNumber()
                    : String.valueOf(invoice.getId());
            throw new IllegalArgumentException("Hóa đơn \"" + number
                    + "\" vừa được người khác cập nhật (thu nợ, trả hàng hoặc bù trừ công nợ)."
                    + " Vui lòng tải lại trang để xem dữ liệu mới nhất rồi thực hiện lại.", exception);
        }
    }
}
