package com.example.project.service;

import com.example.project.dto.request.SupplierRequest;
import com.example.project.dto.response.SupplierAvailableProductResponse;
import com.example.project.dto.response.SupplierResponse;
import com.example.project.dto.response.SupplierproductResponse;
import com.example.project.entity.Product;
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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final SupplierproductRepository supplierproductRepository;
    private final ProductRepository productRepository;

    public SupplierService(SupplierRepository supplierRepository,
                           SupplierproductRepository supplierproductRepository,
                           ProductRepository productRepository) {
        this.supplierRepository = supplierRepository;
        this.supplierproductRepository = supplierproductRepository;
        this.productRepository = productRepository;
    }

    // ------------------------------------------------------------------ list

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

    // ------------------------------------------------------------------ stats

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

    // ------------------------------------------------------------------ getById

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

    // ------------------------------------------------ available products picker

    /**
     * Products that are NOT yet supplied by this supplier, feeding the
     * "Thêm sản phẩm cung ứng" picker modal. Sorted by name (search / filter /
     * paging are handled client-side inside the modal).
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
     * Links the given products to the supplier. Ignores blank ids, products
     * already linked, and ids that no longer exist. Returns how many were added.
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

    private Set<Integer> linkedProductIds(Integer supplierId) {
        return supplierproductRepository.findBySupplierID_Id(supplierId)
                .stream()
                .map(sp -> sp.getProductID() != null ? sp.getProductID().getProductID() : null)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(java.util.HashSet::new));
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public Integer create(SupplierRequest request) {
        validateUnique(request, null);

        Supplier supplier = new Supplier();
        supplier.setName(request.getName().trim());
        supplier.setPhone(request.getPhone().trim());
        supplier.setEmail(request.getEmail().trim());
        supplier.setAddress(request.getAddress().trim());
        supplier.setTaxCode(request.getTaxCode().trim());

        return saveGuardingUniqueRace(supplier).getId();
    }

    // ------------------------------------------------------------------ update

    @Transactional
    public void update(Integer id, SupplierRequest request) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp"));

        validateUnique(request, id);

        supplier.setName(request.getName().trim());
        supplier.setPhone(request.getPhone().trim());
        supplier.setEmail(request.getEmail().trim());
        supplier.setAddress(request.getAddress().trim());
        supplier.setTaxCode(request.getTaxCode().trim());

        saveGuardingUniqueRace(supplier);
    }

    // ------------------------------------------------------------------ legacy

    @Transactional(readOnly = true)
    public List<SupplierResponse> getAll() {
        return supplierRepository.findAll()
                .stream()
                .map(SupplierResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------ helpers

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
