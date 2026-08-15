package com.example.project.service;

import com.example.project.dto.request.SupplierProductUpdateRequest;
import com.example.project.dto.request.SupplierRequest;
import com.example.project.dto.response.SupplierAvailableProductResponse;
import com.example.project.dto.response.SupplierDebtInvoiceResponse;
import com.example.project.dto.response.SupplierResponse;
import com.example.project.dto.response.SupplierproductResponse;
import com.example.project.entity.Product;
import com.example.project.entity.Purchaseinvoice;
import com.example.project.entity.Supplier;
import com.example.project.entity.Supplierproduct;
import com.example.project.repository.ProductRepository;
import com.example.project.repository.SupplierRepository;
import com.example.project.repository.SupplierproductRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final SupplierproductRepository supplierproductRepository;
    private final ProductRepository productRepository;
    private final PurchaseinvoiceService purchaseinvoiceService;

    public SupplierService(SupplierRepository supplierRepository,
                           SupplierproductRepository supplierproductRepository,
                           ProductRepository productRepository,
                           PurchaseinvoiceService purchaseinvoiceService) {
        this.supplierRepository = supplierRepository;
        this.supplierproductRepository = supplierproductRepository;
        this.productRepository = productRepository;
        this.purchaseinvoiceService = purchaseinvoiceService;
    }

    // ------------------------------------------------------------------ danh sách

    @Transactional(readOnly = true)
    public Page<SupplierResponse> list(String keyword, Pageable pageable) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);

        List<SupplierResponse> filtered = supplierRepository.findAll()
                .stream()
                .filter(s -> matchesKeyword(s, kw))
                // Sắp theo MÃ (= supplierID, vì NCC-xxxxx suy thẳng từ khoá chính): sắp theo tên thì
                // mã nhảy lung tung và NCC vừa tạo không biết nằm đâu.
                .sorted(Comparator.comparing(Supplier::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(s -> {
                    SupplierResponse r = SupplierResponse.from(s);
                    r.setProductCount(supplierproductRepository.countBySupplierID_Id(s.getId()));
                    return r;
                })
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<SupplierResponse> pageContent = start >= filtered.size() ? List.of() : filtered.subList(start, end);

        return new PageImpl<>(pageContent, pageable, filtered.size());
    }

    // ------------------------------------------------------------------ thống kê

    public record SupplierStats(long total, long withProducts, long withoutProducts, long totalProducts) {
    }

    @Transactional(readOnly = true)
    public SupplierStats getStats() {
        long total = supplierRepository.count();
        long totalProducts = supplierproductRepository.count();
        long withProducts = supplierproductRepository.countDistinctSuppliers();
        long withoutProducts = total - withProducts;
        return new SupplierStats(total, withProducts, withoutProducts, totalProducts);
    }

    // ------------------------------------------------------------------ lấy theo id

    @Transactional(readOnly = true)
    public SupplierResponse getById(Integer id) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp"));
        SupplierResponse r = SupplierResponse.from(supplier);
        r.setProductCount(supplierproductRepository.countBySupplierID_Id(id));
        return r;
    }

    @Transactional(readOnly = true)
    public List<SupplierproductResponse> getProducts(Integer supplierId) {
        return supplierproductRepository.findBySupplierID_Id(supplierId)
                .stream()
                .map(SupplierproductResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------ công nợ phải trả

    /**
     * Các phiếu nhập của nhà cung cấp này còn nợ tiền, mới nhất trước.
     *
     * <p>Nguồn và cách tính lấy nguyên của {@code PurchaseinvoiceService}
     * ({@code findPayableInvoices} + {@code remainingDebt}) — cũng chính là thứ màn Công nợ dùng —
     * để hai màn không bao giờ báo hai con số khác nhau cho cùng một nhà cung cấp.</p>
     */
    @Transactional(readOnly = true)
    public List<SupplierDebtInvoiceResponse> getOutstandingPurchaseInvoices(Integer supplierId) {
        return purchaseinvoiceService.findPayableInvoices().stream()
                .filter(invoice -> invoice.getSupplierID() != null
                        && Objects.equals(supplierId, invoice.getSupplierID().getId()))
                .sorted(Comparator.comparing(Purchaseinvoice::getDate,
                        Comparator.nullsLast(Comparator.<java.time.Instant>naturalOrder())).reversed())
                .map(invoice -> SupplierDebtInvoiceResponse.from(
                        invoice, purchaseinvoiceService.remainingDebt(invoice)))
                .toList();
    }

    /** Tổng còn phải trả nhà cung cấp = cộng phần còn nợ của các phiếu nhập chưa trả xong. */
    @Transactional(readOnly = true)
    public BigDecimal getTotalDebt(Integer supplierId) {
        return getOutstandingPurchaseInvoices(supplierId).stream()
                .map(SupplierDebtInvoiceResponse::getRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------ chọn sản phẩm để thêm vào NCC

    /**
     * Sản phẩm CHƯA được nhà cung cấp này cung ứng — dữ liệu cho modal chọn "Thêm sản phẩm cung
     * ứng". Sắp theo tên; tìm/lọc/phân trang xử lý ở phía client trong modal.
     */
    @Transactional(readOnly = true)
    public List<SupplierAvailableProductResponse> getAvailableProducts(Integer supplierId) {
        Set<Integer> linkedProductIds = linkedProductIds(supplierId);
        return productRepository.findAllWithRelations()
                .stream()
                .filter(p -> !linkedProductIds.contains(p.getProductID()))
                .map(SupplierAvailableProductResponse::from)
                .toList();
    }

    /**
     * Gắn các sản phẩm được chọn vào nhà cung cấp. Bỏ qua id rỗng, sản phẩm đã gắn sẵn, và id không
     * còn tồn tại. Trả về số sản phẩm thực sự được thêm.
     */
    @Transactional
    public int addProducts(Integer supplierId, List<Integer> productIds) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp"));
        if (productIds == null || productIds.isEmpty()) {
            return 0;
        }

        Set<Integer> linkedProductIds = linkedProductIds(supplierId);
        int added = 0;
        for (Integer productId : productIds) {
            if (productId == null || linkedProductIds.contains(productId)) {
                continue;
            }
            Product product = productRepository.findById(productId).orElse(null);
            if (product == null) {
                continue;
            }
            Supplierproduct sp = new Supplierproduct();
            sp.setSupplierID(supplier);
            sp.setProductID(product);
            sp.setIsPreferred(false);
            sp.setIsActive(true);
            supplierproductRepository.save(sp);
            linkedProductIds.add(productId);
            added++;
        }
        return added;
    }

    // ------------------------------------------------ sửa một dòng sản phẩm cung ứng

    /**
     * Cập nhật trạng thái cung ứng / cờ ưu tiên / ghi chú của MỘT dòng {@code supplierproduct}.
     *
     * <p><b>Mỗi sản phẩm chỉ có MỘT nhà cung cấp ưu tiên.</b> Bật cờ ưu tiên ở đây sẽ tự tắt cờ đó ở
     * mọi nhà cung cấp khác của cùng sản phẩm — cờ này để trả lời "mua hàng này thì gọi ai trước",
     * hai NCC cùng ưu tiên là không trả lời được gì.</p>
     *
     * <p>Dòng đã NGỪNG cung ứng thì không được là dòng ưu tiên: gợi ý mua từ một NCC đã ngừng cấp hàng
     * là dẫn người dùng vào ngõ cụt.</p>
     */
    @Transactional
    public void updateSupplierProduct(Integer supplierId,
                                      Integer supplierProductId,
                                      SupplierProductUpdateRequest request) {
        Supplierproduct supplierProduct = supplierproductRepository.findById(supplierProductId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm cung ứng"));

        // Id dòng đi qua URL nên phải kiểm nó đúng là của NCC đang mở, không thì sửa được dòng của NCC khác.
        if (supplierProduct.getSupplierID() == null
                || !Objects.equals(supplierProduct.getSupplierID().getId(), supplierId)) {
            throw new IllegalArgumentException("Sản phẩm cung ứng này không thuộc nhà cung cấp đang xem");
        }

        boolean active = Boolean.TRUE.equals(request.getIsActive());
        boolean preferred = Boolean.TRUE.equals(request.getIsPreferred());
        if (preferred && !active) {
            throw new IllegalArgumentException(
                    "Không đặt được nhà cung cấp ưu tiên cho sản phẩm đã ngừng cung ứng");
        }

        supplierProduct.setIsActive(active);
        supplierProduct.setIsPreferred(preferred);
        supplierProduct.setNote(trimToNull(request.getNote()));

        if (preferred) {
            clearPreferredOnOtherSuppliers(supplierProduct);
        }

        supplierproductRepository.save(supplierProduct);
    }

    /** Tắt cờ ưu tiên ở các dòng cùng sản phẩm nhưng khác nhà cung cấp. */
    private void clearPreferredOnOtherSuppliers(Supplierproduct preferredRow) {
        Integer productId = preferredRow.getProductID() != null
                ? preferredRow.getProductID().getProductID() : null;
        if (productId == null) {
            return;
        }
        for (Supplierproduct other : supplierproductRepository.findByProductID_ProductID(productId)) {
            if (Objects.equals(other.getId(), preferredRow.getId())
                    || !Boolean.TRUE.equals(other.getIsPreferred())) {
                continue;
            }
            other.setIsPreferred(false);
            supplierproductRepository.save(other);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Set<Integer> linkedProductIds(Integer supplierId) {
        return supplierproductRepository.findBySupplierID_Id(supplierId)
                .stream()
                .map(sp -> sp.getProductID() != null ? sp.getProductID().getProductID() : null)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(java.util.HashSet::new));
    }

    // ------------------------------------------------------------------ tạo

    @Transactional
    public Integer create(SupplierRequest request) {
        validateUnique(request, null);

        Supplier supplier = new Supplier();
        supplier.setName(request.getName().trim());
        supplier.setPhone(request.getPhone().trim());
        supplier.setEmail(trimToNull(request.getEmail()));
        supplier.setAddress(request.getAddress().trim());
        supplier.setTaxCode(request.getTaxCode().trim());

        return saveGuardingUniqueRace(supplier).getId();
    }

    // ------------------------------------------------------------------ sửa

    @Transactional
    public void update(Integer id, SupplierRequest request) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp"));

        validateUnique(request, id);

        supplier.setName(request.getName().trim());
        supplier.setPhone(request.getPhone().trim());
        supplier.setEmail(trimToNull(request.getEmail()));
        supplier.setAddress(request.getAddress().trim());
        supplier.setTaxCode(request.getTaxCode().trim());

        saveGuardingUniqueRace(supplier);
    }

    // ------------------------------------------------------------------ cũ

    @Transactional(readOnly = true)
    public List<SupplierResponse> getAll() {
        return supplierRepository.findAll()
                .stream()
                .map(SupplierResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------ hàm phụ trợ

    private boolean matchesKeyword(Supplier s, String kw) {
        if (kw.isBlank()) return true;
        return contains(s.getName(), kw)
                || contains(s.getPhone(), kw)
                || contains(s.getEmail(), kw);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /**
     * Ba trường định danh một NCC phải là DUY NHẤT. MST quan trọng nhất: nó là căn cứ đối chiếu hóa
     * đơn GTGT đầu vào với cơ quan thuế, hai NCC cùng MST thì không phân định được hóa đơn của ai.
     */
    private void validateUnique(SupplierRequest request, Integer excludeId) {
        if (isPhoneTaken(request.getPhone(), excludeId)) {
            throw new IllegalArgumentException("Số điện thoại đã được sử dụng bởi nhà cung cấp khác");
        }
        if (isEmailTaken(request.getEmail(), excludeId)) {
            throw new IllegalArgumentException("Email đã được sử dụng bởi nhà cung cấp khác");
        }
        if (isTaxCodeTaken(request.getTaxCode(), excludeId)) {
            throw new IllegalArgumentException("Mã số thuế đã được sử dụng bởi nhà cung cấp khác");
        }
    }

    /** Dùng chung cho cả kiểm tra lúc lưu và endpoint kiểm trùng của màn tạo/sửa. */
    @Transactional(readOnly = true)
    public boolean isPhoneTaken(String phone, Integer excludeId) {
        if (phone == null || phone.isBlank()) return false;
        String value = phone.trim();
        return excludeId == null
                ? supplierRepository.existsByPhone(value)
                : supplierRepository.existsByPhoneAndIdNot(value, excludeId);
    }

    @Transactional(readOnly = true)
    public boolean isEmailTaken(String email, Integer excludeId) {
        if (email == null || email.isBlank()) return false;
        String value = email.trim();
        return excludeId == null
                ? supplierRepository.existsByEmailIgnoreCase(value)
                : supplierRepository.existsByEmailIgnoreCaseAndIdNot(value, excludeId);
    }

    @Transactional(readOnly = true)
    public boolean isTaxCodeTaken(String taxCode, Integer excludeId) {
        if (taxCode == null || taxCode.isBlank()) return false;
        String value = taxCode.trim();
        return excludeId == null
                ? supplierRepository.existsByTaxCode(value)
                : supplierRepository.existsByTaxCodeAndIdNot(value, excludeId);
    }

    /**
     * Chặn trùng cho ca hai người nhập cùng lúc: {@link #validateUnique} hỏi-rồi-ghi nên luôn có khe
     * hở, chỉ UNIQUE index ở DB phân xử được. Dịch lỗi DB sang đúng câu {@code validateUnique} dùng.
     *
     * <p>Phải {@code saveAndFlush}: {@code save} chỉ đưa vào session, UPDATE thật chạy lúc commit —
     * tức là sau khi đã ra khỏi khối {@code try} này.
     */
    private Supplier saveGuardingUniqueRace(Supplier supplier) {
        try {
            return supplierRepository.saveAndFlush(supplier);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException(duplicateMessage(exception), exception);
        }
    }

    /**
     * Tên index bị vi phạm, cắt từ câu lỗi MySQL {@code Duplicate entry '<giá trị>' for key
     * '<bảng>.<index>'}. Phải cắt phần sau {@code for key}, KHÔNG dò cả câu: giá trị bị trùng cũng
     * nằm trong câu đó nên MST trùng của NCC có email {@code phone@...} sẽ khớp nhầm sang "số điện
     * thoại". Không nhận ra index thì trả câu chung.
     */
    private String duplicateMessage(DataIntegrityViolationException exception) {
        String detail = exception.getMostSpecificCause().getMessage();
        int marker = detail == null ? -1 : detail.lastIndexOf("for key");
        String key = (marker < 0 ? "" : detail.substring(marker + "for key".length()))
                .toLowerCase(Locale.ROOT);
        if (key.contains("phone")) {
            return "Số điện thoại đã được sử dụng bởi nhà cung cấp khác";
        }
        if (key.contains("email")) {
            return "Email đã được sử dụng bởi nhà cung cấp khác";
        }
        if (key.contains("taxcode")) {
            return "Mã số thuế đã được sử dụng bởi nhà cung cấp khác";
        }
        return "Thông tin nhà cung cấp bị trùng với một nhà cung cấp khác, vui lòng kiểm tra lại";
    }
}
