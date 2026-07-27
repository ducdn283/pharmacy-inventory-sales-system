package com.example.project.service;

import com.example.project.dto.request.InvoiceCreateRequest;
import com.example.project.dto.request.InvoiceDetailCreateRequest;
import com.example.project.dto.response.CustomerOptionResponse;
import com.example.project.dto.response.InvoiceDetailItemResponse;
import com.example.project.dto.response.InvoiceDetailPageResponse;
import com.example.project.dto.response.InvoiceDetailProductGroupResponse;
import com.example.project.dto.response.InvoiceDetailUnitLineResponse;
import com.example.project.dto.response.InvoiceLineResponse;
import com.example.project.dto.response.InvoiceListItemResponse;
import com.example.project.dto.response.InvoiceResponse;
import com.example.project.dto.response.SellBatchOptionResponse;
import com.example.project.dto.response.SellProductOptionResponse;
import com.example.project.dto.response.SellUnitOptionResponse;
import com.example.project.entity.Account;
import com.example.project.entity.Batch;
import com.example.project.entity.Customer;
import com.example.project.entity.Invoice;
import com.example.project.entity.Invoicedetail;
import com.example.project.entity.Product;
import com.example.project.entity.Productunit;
import com.example.project.entity.Type;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.BatchRepository;
import com.example.project.repository.CustomerRepository;
import com.example.project.repository.FinancialsettingRepository;
import com.example.project.repository.InvoiceRepository;
import com.example.project.repository.InvoicedetailRepository;
import com.example.project.repository.ProductRepository;
import com.example.project.repository.ProductunitRepository;
import com.example.project.repository.ReturnRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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

@Service
public class InvoiceService {
    private static final String RETURN_NONE = "NONE";
    private static final String RETURN_PARTIAL = "PARTIAL";
    private static final String RETURN_FULL = "FULL";

    private static final String INVOICE_TYPE_NORMAL = "Bán hàng";
    private static final String INVOICE_TYPE_NORMAL_LEGACY = "normal";
    private static final String INVOICE_TYPE_ADJUSTMENT = "Điều chỉnh";
    private static final String INVOICE_TYPE_ADJUSTMENT_LEGACY = "adjustment";
    private static final String INVOICE_TYPE_RETURN = "return";

    private static final String PAYMENT_CASH = "CASH";
    private static final String PAYMENT_BANKING = "BANKING";
    private static final String PAYMENT_MIXED = "MIXED";
    private static final String PAYMENT_DEBT = "DEBT";

    private static final String STATUS_COMPLETED = "Hoàn thành";
    private static final String STATUS_DEBT = "Còn nợ";
    private static final String STATUS_SIGNED = "Đã ký";
    private static final String STATUS_RETURNED_FULL = "Đã trả hàng toàn bộ";
    private static final String STATUS_RETURNED_PARTIAL = "Đã trả hàng 1 phần";
    private static final String INVOICE_NUMBER_PREFIX = "HD";
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final InvoiceRepository invoiceRepository;
    private final InvoicedetailRepository invoicedetailRepository;
    private final ProductRepository productRepository;
    private final ProductunitRepository productunitRepository;
    private final BatchRepository batchRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final FinancialsettingRepository financialsettingRepository;
    private final ReturnRepository returnRepository;
    // Lazily opens/reuses the seller's shift the moment a sale invoice is actually recorded —
    // mirrors the same hook on the Return side (see ShiftreportService), unconditionally (even a
    // fully-on-credit invoice with no cash/banking movement still counts as a transaction).
    private final ShiftreportService shiftreportService;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          InvoicedetailRepository invoicedetailRepository,
                          ProductRepository productRepository,
                          ProductunitRepository productunitRepository,
                          BatchRepository batchRepository,
                          CustomerRepository customerRepository,
                          AccountRepository accountRepository,
                          FinancialsettingRepository financialsettingRepository,
                          ReturnRepository returnRepository,
                          ShiftreportService shiftreportService) {
        this.invoiceRepository = invoiceRepository;
        this.invoicedetailRepository = invoicedetailRepository;
        this.productRepository = productRepository;
        this.productunitRepository = productunitRepository;
        this.batchRepository = batchRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.financialsettingRepository = financialsettingRepository;
        this.returnRepository = returnRepository;
        this.shiftreportService = shiftreportService;
    }

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

    @Transactional(readOnly = true)
    public List<String> listStatuses() {
        return invoiceRepository.findAll().stream()
                .map(Invoice::getStatus)
                .filter(status -> status != null && !status.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

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

    public Map<String, String> paymentTypeLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PAYMENT_CASH, "Tiền mặt");
        labels.put(PAYMENT_BANKING, "Chuyển khoản");
        labels.put(PAYMENT_MIXED, "TM + CK");
        labels.put(PAYMENT_DEBT, "Ghi nợ");
        return labels;
    }

    @Transactional(readOnly = true)
    public long countAll() {
        return invoiceRepository.count();
    }

    @Transactional(readOnly = true)
    public long countToday() {
        LocalDate today = LocalDate.now();
        return invoiceRepository.findAll().stream()
                .filter(invoice -> invoice.getDate() != null && toLocalDate(invoice.getDate()).equals(today))
                .count();
    }

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

    @Transactional(readOnly = true)
    public long countDebt() {
        return invoiceRepository.findAll().stream()
                .filter(invoice -> isPositive(invoice.getDebtAmount()))
                .count();
    }

    @Transactional(readOnly = true)
    public BigDecimal sumDebtTotal() {
        return invoiceRepository.findAll().stream()
                .map(Invoice::getDebtAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public long countReturned() {
        return returnStateByInvoice().values().stream()
                .filter(code -> RETURN_PARTIAL.equals(code) || RETURN_FULL.equals(code))
                .count();
    }

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

    @Transactional(readOnly = true)
    public List<SellProductOptionResponse> listSellableProducts() {
        Map<Integer, Long> stockByProduct = new LinkedHashMap<>();
        for (Object[] row : batchRepository.sumStorageGroupedByProduct()) {
            stockByProduct.put((Integer) row[0], (Long) row[1]);
        }

        Map<Integer, List<Productunit>> unitsByProduct = new LinkedHashMap<>();
        for (Productunit unit : productunitRepository.findAllWithProduct()) {
            if (Boolean.FALSE.equals(unit.getIsActive()) || unit.getProductID() == null) {
                continue;
            }
            unitsByProduct.computeIfAbsent(unit.getProductID().getProductID(), id -> new ArrayList<>()).add(unit);
        }

        List<SellProductOptionResponse> options = new ArrayList<>();
        for (Product product : productRepository.findAll()) {
            if (!Boolean.TRUE.equals(product.getStatus())) {
                continue;
            }
            long baseStock = stockByProduct.getOrDefault(product.getProductID(), 0L);
            List<Productunit> units = unitsByProduct.getOrDefault(product.getProductID(), List.of());
            if (baseStock <= 0 || units.isEmpty()) {
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
                    .findInStockBatchesByProduct(product.getProductID())
                    .stream()
                    .map(batch -> new SellBatchOptionResponse(
                            batch.getId(),
                            batch.getBatchCode(),
                            batch.getLotNumber(),
                            batch.getExpirationDate(),
                            formatLocalDate(batch.getExpirationDate()),
                            batch.getStorageQuantity()))
                    .toList();

            options.add(new SellProductOptionResponse(
                    product.getProductID(),
                    product.getCode(),
                    product.getName(),
                    product.getBarcode(),
                    baseStock,
                    unitOptions,
                    batchOptions));
        }

        options.sort(Comparator.comparing(SellProductOptionResponse::getName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return options;
    }

    @Transactional(readOnly = true)
    public List<CustomerOptionResponse> listCustomers() {
        return customerRepository.findAll().stream()
                .sorted(Comparator.comparing(Customer::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(customer -> new CustomerOptionResponse(
                        customer.getId(), customer.getName(), customer.getPhoneNumber(), customer.getCustomerType()))
                .toList();
    }

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

        LocalDateTime invoiceDateTime = LocalDateTime.now(VN_ZONE);

        Invoice invoice = new Invoice();
        invoice.setInvoicePattern(buildInvoicePattern(invoiceDateTime.toLocalDate()));
        invoice.setInvoiceNumber(generateInvoiceNumber());
        // Store Vietnam wall-clock time directly (mirrors ProcurementplanService using LocalDateTime.now(VN_ZONE)).
        invoice.setDate(invoiceDateTime);
        invoice.setEmployeeID(employee);
        invoice.setCustomerID(customer);
        invoice.setInvoiceType(INVOICE_TYPE_NORMAL);
        invoice.setPrescriptionRequired(prescriptionRequired);
        invoice.setPrescriptionCode(prescriptionCode);
        invoice.setNote(trimToNull(request.getNote()));
        // NOT NULL money columns must hold a value on the first flush (the detail rows below need the
        // invoice id first); the real figures are computed and re-saved once the lines are priced.
        invoice.setSubtotal(BigDecimal.ZERO);
        invoice.setDiscount(BigDecimal.ZERO);
        invoice.setTotal(BigDecimal.ZERO);
        invoice.setPaidByCash(BigDecimal.ZERO);
        invoice.setPaidByBanking(BigDecimal.ZERO);
        invoice.setDebtAmount(BigDecimal.ZERO);
        invoice.setStatus(STATUS_COMPLETED);

        Invoice savedInvoice = invoiceRepository.save(invoice);

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalVATOutput = BigDecimal.ZERO;
        for (InvoiceDetailCreateRequest item : request.getDetails()) {
            SavedLineTotals lineTotals = saveLineAndDeductStock(savedInvoice, item);
            subtotal = subtotal.add(lineTotals.subtotal());
            totalVATOutput = totalVATOutput.add(lineTotals.vatAmount());
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
        savedInvoice.setTotalVATOutput(totalVATOutput);
        savedInvoice.setStatus(debt.compareTo(BigDecimal.ZERO) > 0 ? STATUS_DEBT : STATUS_COMPLETED);

        // A sale is a real transaction the instant it's recorded, regardless of payment mix —
        // open/reuse the seller's shift and attach it (no amount-based condition).
        savedInvoice.setShiftReportID(shiftreportService.ensureOpenShiftFor(currentAccountId));

        invoiceRepository.save(savedInvoice);

        return savedInvoice.getId();
    }

    private SavedLineTotals saveLineAndDeductStock(Invoice invoice, InvoiceDetailCreateRequest item) {
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
        BigDecimal vatRate = resolveVatRateSnapshot(product);
        BigDecimal preTaxAmount = calculateSaleLinePreTaxAmount(lineSubtotal, vatRate);
        BigDecimal vatAmount = calculateSaleLineVatAmount(preTaxAmount, vatRate);

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
            detail.setVatRate(vatRate);
            detail.setPreTaxAmount(preTaxAmount);
            detail.setVatAmount(vatAmount);
            invoicedetailRepository.save(detail);
            return new SavedLineTotals(lineSubtotal, vatAmount);
        }

        // Deduction spanned more than one batch — persist one row per batch actually touched, keeping
        // the sale unit the cashier chose so invoice detail matches the POS screen. Money and sale
        // quantity are split proportionally by base quantity; the last chunk absorbs rounding remainders.
        BigDecimal remainingSubtotal = lineSubtotal;
        BigDecimal remainingPreTax = preTaxAmount;
        BigDecimal remainingVat = vatAmount;
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
            BigDecimal chunkPreTax;
            BigDecimal chunkVat;
            if (lastChunk) {
                chunkSubtotal = remainingSubtotal;
                chunkPreTax = remainingPreTax;
                chunkVat = remainingVat;
            } else {
                BigDecimal share = BigDecimal.valueOf(chunkBaseQty)
                        .divide(BigDecimal.valueOf(remainingBaseQty == 0 ? 1 : remainingBaseQty),
                                10, RoundingMode.HALF_UP);
                chunkSubtotal = lineSubtotal.multiply(share).setScale(2, RoundingMode.HALF_UP);
                chunkPreTax = preTaxAmount.multiply(share).setScale(2, RoundingMode.HALF_UP);
                chunkVat = vatAmount.multiply(share).setScale(2, RoundingMode.HALF_UP);
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
            detail.setVatRate(vatRate);
            detail.setPreTaxAmount(chunkPreTax);
            detail.setVatAmount(chunkVat);
            invoicedetailRepository.save(detail);

            remainingSubtotal = remainingSubtotal.subtract(chunkSubtotal);
            remainingPreTax = remainingPreTax.subtract(chunkPreTax);
            remainingVat = remainingVat.subtract(chunkVat);
            remainingBaseQty -= chunkBaseQty;
            remainingSellQty -= chunkSellQty;
        }

        return new SavedLineTotals(lineSubtotal, vatAmount);
    }

    /**
     * Snapshot thuế suất GTGT tại thời điểm bán: {@code Product.vatRateOverride} nếu có,
     * ngược lại {@code Type.defaultVATRate}; lưu vào dòng hóa đơn, không tham chiếu động tới Product.
     */
    private BigDecimal resolveVatRateSnapshot(Product product) {
        if (product.getVatRateOverride() != null) {
            return product.getVatRateOverride();
        }
        Type type = product.getTypeID();
        if (type != null && type.getDefaultVATRate() != null) {
            return type.getDefaultVATRate();
        }
        return BigDecimal.ZERO;
    }

    /** Giá trị dòng hàng chưa gồm thuế GTGT — subtotal đã gồm thuế: subtotal ÷ (1 + vatRate/100). */
    private BigDecimal calculateSaleLinePreTaxAmount(BigDecimal grossSubtotal, BigDecimal vatRatePercent) {
        if (grossSubtotal == null || grossSubtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal rate = vatRatePercent != null ? vatRatePercent : BigDecimal.ZERO;
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            return grossSubtotal.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal divisor = BigDecimal.ONE.add(
                rate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        return grossSubtotal.divide(divisor, 2, RoundingMode.HALF_UP);
    }

    /** Số tiền thuế GTGT đầu ra của dòng hàng — preTaxAmount × vatRate / 100. */
    private BigDecimal calculateSaleLineVatAmount(BigDecimal preTaxAmount, BigDecimal vatRatePercent) {
        BigDecimal preTax = preTaxAmount != null ? preTaxAmount : BigDecimal.ZERO;
        BigDecimal rate = vatRatePercent != null ? vatRatePercent : BigDecimal.ZERO;
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return preTax.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private record SavedLineTotals(BigDecimal subtotal, BigDecimal vatAmount) {}

    /** One batch's contribution to a single line's FEFO deduction. */
    private record BatchAllocation(Batch batch, int baseQtyTaken) {}

    /** Deducts {@code baseQty} from a chosen batch or FEFO across batches. */
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
            return List.of(new BatchAllocation(batch, baseQty));
        }

        List<Batch> batches = batchRepository.findInStockBatchesByProduct(product.getProductID());
        long available = batches.stream()
                .mapToLong(batch -> batch.getStorageQuantity() == null ? 0 : batch.getStorageQuantity())
                .sum();
        if (available < baseQty) {
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
            int inBatch = batch.getStorageQuantity() == null ? 0 : batch.getStorageQuantity();
            if (inBatch <= 0) {
                continue;
            }
            int take = Math.min(inBatch, remaining);
            batch.setStorageQuantity(inBatch - take);
            batchRepository.save(batch);
            allocations.add(new BatchAllocation(batch, take));
            remaining -= take;
        }
        return allocations;
    }

    private String generateInvoiceNumber() {
        int nextId = invoiceRepository.findAll().stream()
                .map(Invoice::getId)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        return INVOICE_NUMBER_PREFIX + String.format("%06d", nextId);
    }

    /**
     * Ký hiệu hóa đơn 7 ký tự: 2 (bán hàng) + K (không mã CQT) + YY (năm) + M (máy tính tiền) + AA.
     * Khi ký đẩy lên CQT, ký hiệu K được chuyển thành C (xem {@link #toSignedInvoicePattern}).
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

    /** Chuyển ký hiệu K (không mã CQT) → C (có mã CQT) khi hóa đơn được ký. */
    private String toSignedInvoicePattern(String pattern) {
        if (pattern == null || pattern.length() < 2) {
            return pattern;
        }
        return pattern.charAt(0) + "C" + pattern.substring(2);
    }

    private BigDecimal maxZero(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The lines of one invoice, for the quick-view modal (JSON). */
    @Transactional(readOnly = true)
    public List<InvoiceLineResponse> loadLines(Integer invoiceId) {
        if (!invoiceRepository.existsById(invoiceId)) {
            throw new IllegalArgumentException("Không tìm thấy hóa đơn");
        }
        return invoicedetailRepository.findByInvoiceIdWithRelations(invoiceId).stream()
                .map(this::toLine)
                .toList();
    }

    /** Marks a sale invoice as signed ({@code status = Đã ký}). Owner and Accountant only. */
    @Transactional
    public void sign(Integer invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn"));
        if (isStatus(invoice.getStatus(), STATUS_SIGNED)) {
            throw new IllegalArgumentException("Hóa đơn đã được ký");
        }
        invoice.setInvoicePattern(toSignedInvoicePattern(invoice.getInvoicePattern()));
        invoice.setStatus(STATUS_SIGNED);
        invoiceRepository.save(invoice);
    }

    /** Signs multiple sale invoices. Skips ones already signed; returns how many were updated. */
    @Transactional
    public int signMany(List<Integer> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một hóa đơn");
        }

        int signed = 0;
        for (Integer invoiceId : invoiceIds.stream().distinct().toList()) {
            Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
            if (invoice == null || isStatus(invoice.getStatus(), STATUS_SIGNED)) {
                continue;
            }
            invoice.setInvoicePattern(toSignedInvoicePattern(invoice.getInvoicePattern()));
            invoice.setStatus(STATUS_SIGNED);
            invoiceRepository.save(invoice);
            signed++;
        }

        if (signed == 0) {
            throw new IllegalArgumentException("Không có hóa đơn nào được ký (có thể đã ký trước đó)");
        }
        return signed;
    }

    /** Full sale-invoice detail for the detail page. */
    @Transactional(readOnly = true)
    public InvoiceDetailPageResponse getDetail(Integer invoiceId) {
        Invoice invoice = invoiceRepository.findByIdWithRelations(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn"));

        List<Invoicedetail> lines = invoicedetailRepository.findByInvoiceIdWithRelations(invoiceId);
        Map<Integer, String> returnStates = returnStateByInvoice();
        String returnCode = returnStates.getOrDefault(invoiceId, RETURN_NONE);

        List<InvoiceDetailItemResponse> items = lines.stream()
                .map(this::toDetailItem)
                .toList();

        List<InvoiceDetailProductGroupResponse> productGroups = buildProductGroups(lines);

        int totalQuantity = items.stream()
                .map(InvoiceDetailItemResponse::getQuantity)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        BigDecimal totalVATOutput = invoice.getTotalVATOutput();
        if (totalVATOutput == null) {
            totalVATOutput = lines.stream()
                    .map(Invoicedetail::getVatAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        Customer customer = invoice.getCustomerID();
        Invoice original = invoice.getOriginalInvoiceID();
        Invoice root = invoice.getRootInvoiceID();

        Map<Integer, String> returnSlips = returnRepository
                .findByInvoiceID_IdOrderByReturnDateDesc(invoiceId)
                .stream()
                .collect(Collectors.toMap(
                        ret -> ret.getId(),
                        ret -> ret.getReturnCode(),
                        (a, b) -> a,
                        LinkedHashMap::new));

        String statusName = invoice.getStatus() != null ? invoice.getStatus() : "Không rõ";
        String taxCode = isStatus(statusName, STATUS_SIGNED)
                ? financialsettingRepository.findFirstByOrderByIdAsc()
                        .map(setting -> trimToNull(setting.getTaxCode()))
                        .orElse(null)
                : null;

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
                root != null ? root.getId() : null,
                root != null ? invoiceCode(root) : null,
                invoice.getSubtotal(),
                invoice.getDiscount() != null ? invoice.getDiscount() : BigDecimal.ZERO,
                totalVATOutput,
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

    private List<InvoiceDetailProductGroupResponse> buildProductGroups(List<Invoicedetail> lines) {
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
                            .map(this::toUnitLine)
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

    private InvoiceDetailUnitLineResponse toUnitLine(Invoicedetail line) {
        Productunit unit = line.getProductUnitID();
        return new InvoiceDetailUnitLineResponse(
                unit != null ? unit.getId() : null,
                line.getUnitName(),
                false,
                line.getQuantity(),
                line.getUnitSellPrice(),
                line.getSubtotal(),
                line.getReturnedQty() != null ? line.getReturnedQty() : 0,
                formatBatchLabel(line.getBatchID()));
    }

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
                line.getVatRate(),
                line.getPreTaxAmount(),
                line.getVatAmount(),
                line.getReturnedQty() != null ? line.getReturnedQty() : 0);
    }

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
                line.getReturnedQty() != null ? line.getReturnedQty() : 0);
    }

    /** The visible invoice number (the {@code invoiceNumber} column). */
    private String invoiceCode(Invoice invoice) {
        String number = invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber().trim() : "";
        return number.isEmpty() ? "—" : number;
    }

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

    private boolean matchesSeller(Invoice invoice, Integer sellerId) {
        if (sellerId == null) {
            return true;
        }
        return invoice.getEmployeeID() != null
                && sellerId.equals(invoice.getEmployeeID().getId());
    }

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

    private String invoiceTypeDisplay(String invoiceType) {
        if (invoiceType == null || invoiceType.isBlank()) {
            return "—";
        }
        if (INVOICE_TYPE_NORMAL.equalsIgnoreCase(invoiceType)
                || INVOICE_TYPE_NORMAL_LEGACY.equalsIgnoreCase(invoiceType)) {
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

    private boolean isStatus(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private String formatDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(dateTime);
    }

    private String formatLocalDate(LocalDate date) {
        return date == null ? "" : date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
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
