package com.example.project.service;

import com.example.project.dto.request.CustomerRequest;
import com.example.project.dto.response.CustomerInvoiceResponse;
import com.example.project.dto.response.CustomerResponse;
import com.example.project.entity.Customer;
import com.example.project.entity.Invoice;
import com.example.project.repository.CustomerRepository;
import com.example.project.repository.InvoiceRepository;
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

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;

    public CustomerService(CustomerRepository customerRepository,
                           InvoiceRepository invoiceRepository) {
        this.customerRepository = customerRepository;
        this.invoiceRepository = invoiceRepository;
    }

    // ------------------------------------------------------------------ danh sách

    @Transactional(readOnly = true)
    public Page<CustomerResponse> list(String keyword, String type, Pageable pageable) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String typeFilter = (type == null || type.isBlank()) ? null : type.trim();

        List<CustomerResponse> filtered = customerRepository.findAll().stream()
                .filter(c -> matchesKeyword(c, kw))
                .filter(c -> typeFilter == null || typeFilter.equals(c.getCustomerType()))
                // Sắp theo MÃ (= customerID) cho khớp cột mã người dùng đọc đầu tiên: sắp theo tên
                // thì mã nhảy lung tung và khách vừa tạo không biết nằm đâu.
                .sorted(Comparator.comparing(Customer::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(CustomerResponse::from)
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<CustomerResponse> content = start >= filtered.size() ? List.of() : filtered.subList(start, end);
        return new PageImpl<>(content, pageable, filtered.size());
    }

    // ------------------------------------------------------------------ thống kê

    public record CustomerStats(long total, long individual, long company, long withDebt) {
    }

    @Transactional(readOnly = true)
    public CustomerStats getStats() {
        List<Customer> all = customerRepository.findAll();
        long total = all.size();
        long individual = all.stream().filter(c -> "INDIVIDUAL".equals(c.getCustomerType())).count();
        long company = all.stream().filter(c -> "COMPANY".equals(c.getCustomerType())).count();
        long withDebt = invoiceRepository.findAll().stream()
                .filter(i -> i.getCustomerID() != null)
                .filter(i -> i.getDebtAmount() != null && i.getDebtAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(i -> i.getCustomerID().getId())
                .distinct()
                .count();
        return new CustomerStats(total, individual, company, withDebt);
    }

    // ------------------------------------------------------------------ lấy theo id

    @Transactional(readOnly = true)
    public CustomerResponse getById(Integer id) {
        return CustomerResponse.from(findOrThrow(id));
    }

    /** Số hóa đơn gần nhất luôn hiển thị, kể cả khi đã thanh toán xong. */
    private static final int RECENT_INVOICE_LIMIT = 5;

    /**
     * Lịch sử mua hàng của khách: {@value #RECENT_INVOICE_LIMIT} hóa đơn gần nhất, CỘNG THÊM mọi
     * hóa đơn còn nợ dù đã cũ.
     *
     * <p>Hóa đơn còn nợ không được rơi ra ngoài danh sách này: nếu chỉ hiện tổng công nợ thì người
     * dùng biết khách nợ bao nhiêu nhưng không biết nợ ở hóa đơn nào, phải sang màn Hóa đơn rà lại
     * từng phiếu — đúng việc mà màn này phải làm hộ.</p>
     */
    @Transactional(readOnly = true)
    public List<CustomerInvoiceResponse> getRecentInvoices(Integer customerId) {
        List<Invoice> ofCustomer = invoiceRepository.findAll().stream()
                .filter(i -> i.getCustomerID() != null && customerId.equals(i.getCustomerID().getId()))
                .sorted(Comparator.comparing(Invoice::getDate,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();

        List<Invoice> recent = ofCustomer.stream().limit(RECENT_INVOICE_LIMIT).toList();
        return ofCustomer.stream()
                .filter(i -> recent.contains(i) || hasDebt(i))
                .map(CustomerInvoiceResponse::from)
                .toList();
    }

    private boolean hasDebt(Invoice invoice) {
        return invoice.getDebtAmount() != null
                && invoice.getDebtAmount().compareTo(BigDecimal.ZERO) > 0;
    }

    /** Tổng công nợ hiện tại = tổng debtAmount trên các hóa đơn của khách hàng. */
    @Transactional(readOnly = true)
    public BigDecimal getTotalDebt(Integer customerId) {
        return invoiceRepository.findAll().stream()
                .filter(i -> i.getCustomerID() != null && customerId.equals(i.getCustomerID().getId()))
                .map(Invoice::getDebtAmount)
                .filter(d -> d != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------ tạo

    @Transactional
    public Integer create(CustomerRequest req) {
        validate(req, null);
        Customer c = new Customer();
        apply(c, req);
        return saveGuardingUniqueRace(c, req).getId();
    }

    // ------------------------------------------------------------------ sửa

    @Transactional
    public void update(Integer id, CustomerRequest req) {
        Customer c = findOrThrow(id);
        validate(req, id);
        apply(c, req);
        saveGuardingUniqueRace(c, req);
    }

    // ------------------------------------------------------------------ cũ

    @Transactional(readOnly = true)
    public List<CustomerResponse> getAll() {
        return customerRepository.findAll()
                .stream()
                .map(CustomerResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------ hàm phụ trợ

    private Customer findOrThrow(Integer id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khách hàng"));
    }

    /**
     * Chặn trùng cho ca hai người nhập cùng lúc: {@link #validate} hỏi-rồi-ghi nên luôn có khe hở,
     * chỉ UNIQUE index ở DB phân xử được. Dịch lỗi DB sang đúng câu mà {@code validate} vẫn dùng.
     *
     * <p>Phải {@code saveAndFlush}: {@code save} chỉ đưa vào session, UPDATE thật chạy lúc commit —
     * tức là sau khi đã ra khỏi khối {@code try} này.
     */
    private Customer saveGuardingUniqueRace(Customer c, CustomerRequest req) {
        try {
            return customerRepository.saveAndFlush(c);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException(duplicateMessage(exception,
                    "COMPANY".equals(req.getCustomerType())), exception);
        }
    }

    /** Không nhận ra index thì trả câu chung — thà chung chung còn hơn chỉ sai ô. */
    private String duplicateMessage(DataIntegrityViolationException exception, boolean company) {
        String key = violatedIndexName(exception);
        if (key.contains("phonenumber")) {
            return "Số điện thoại đã tồn tại trong hệ thống";
        }
        if (key.contains("taxcode")) {
            return company
                    ? "Mã số thuế đã tồn tại trong hệ thống"
                    : "Số CCCD/CMND đã tồn tại trong hệ thống";
        }
        return "Thông tin khách hàng bị trùng với một khách hàng khác, vui lòng kiểm tra lại";
    }

    /**
     * Tên index bị vi phạm, cắt từ câu lỗi MySQL {@code Duplicate entry '<giá trị>' for key
     * '<bảng>.<index>'}. Phải cắt phần sau {@code for key}, KHÔNG dò cả câu: giá trị bị trùng cũng
     * nằm trong câu đó nên dễ khớp nhầm tên cột khác (vd email {@code phone@...}).
     */
    private String violatedIndexName(DataIntegrityViolationException exception) {
        String detail = exception.getMostSpecificCause().getMessage();
        if (detail == null) {
            return "";
        }
        int marker = detail.lastIndexOf("for key");
        return (marker < 0 ? "" : detail.substring(marker + "for key".length())).toLowerCase(Locale.ROOT);
    }

    /**
     * Chạy TRƯỚC khi ghi vào entity, và kiểm định dạng trước rồi mới tới trùng lặp — để không báo
     * "CCCD đã tồn tại" cho một chuỗi vốn không phải CCCD.
     *
     * @param excludeId id bản ghi đang sửa (null khi tạo mới) — bản ghi luôn "trùng với chính nó"
     */
    private void validate(CustomerRequest req, Integer excludeId) {
        boolean company = "COMPANY".equals(req.getCustomerType());
        String phone = trimToNull(req.getPhoneNumber());
        String taxCode = trimToNull(req.getTaxCode());

        validateRequiredFields(company, taxCode, trimToNull(req.getAddress()),
                trimToNull(req.getBankAccountNumber()), trimToNull(req.getBankName()));
        validateTaxCode(company, taxCode);
        validatePhoneUnique(phone, excludeId);
        validateTaxCodeUnique(company, taxCode, excludeId);
    }

    /**
     * MỌI trường của khách hàng đều bắt buộc, trừ ghi chú.
     *
     * <ul>
     *   <li>Doanh nghiệp là mã số thuế, cá nhân là số CCCD/CMND.</li>
     *
     *   <li>Số tài khoản + tên ngân hàng: chỉ khách doanh nghiệp</li>
     * </ul>
     *
     * <p>Tên và số điện thoại đã do {@code @NotBlank} trên {@code CustomerRequest} chặn từ trước khi
     * vào service</p>
     */
    private void validateRequiredFields(boolean company, String taxCode, String address,
                                        String bankAccountNumber, String bankName) {
        if (taxCode == null) {
            throw new IllegalArgumentException(company
                    ? "Vui lòng nhập mã số thuế của khách doanh nghiệp"
                    : "Vui lòng nhập số CCCD/CMND của khách cá nhân");
        }
        if (address == null) {
            throw new IllegalArgumentException("Vui lòng nhập địa chỉ khách hàng");
        }
        if (!company) {
            return;
        }
        if (bankAccountNumber == null) {
            throw new IllegalArgumentException("Vui lòng nhập số tài khoản ngân hàng của khách doanh nghiệp");
        }
        if (bankName == null) {
            throw new IllegalArgumentException("Vui lòng nhập tên ngân hàng của khách doanh nghiệp");
        }
    }

    private void apply(Customer c, CustomerRequest req) {
        boolean company = "COMPANY".equals(req.getCustomerType());
        c.setCustomerType(company ? "COMPANY" : "INDIVIDUAL");
        c.setName(trimToNull(req.getName()));
        c.setPhoneNumber(trimToNull(req.getPhoneNumber()));
        c.setAddress(trimToNull(req.getAddress()));
        c.setNote(trimToNull(req.getNote()));
        // taxCode dùng cho CẢ 2 loại: doanh nghiệp = MST, cá nhân = CCCD/CMND.
        c.setTaxCode(trimToNull(req.getTaxCode()));
        // Thông tin ngân hàng chỉ áp dụng cho khách doanh nghiệp.
        c.setBankAccountNumber(company ? trimToNull(req.getBankAccountNumber()) : null);
        c.setBankName(company ? trimToNull(req.getBankName()) : null);
    }

    /** Doanh nghiệp = MST 10 số (kèm "-3 số" chi nhánh); cá nhân = CCCD 12 số hoặc CMND 9 số. */
    private void validateTaxCode(boolean company, String taxCode) {
        if (taxCode == null) {
            return;
        }
        if (company) {
            if (!taxCode.matches("^[0-9]{10}(-[0-9]{3})?$")) {
                throw new IllegalArgumentException(
                        "Mã số thuế phải gồm 10 chữ số (hoặc 10 chữ số - 3 chữ số chi nhánh)");
            }
        } else if (!taxCode.matches("^([0-9]{9}|[0-9]{12})$")) {
            throw new IllegalArgumentException("Số CCCD/CMND phải gồm 12 chữ số (CCCD) hoặc 9 chữ số (CMND)");
        }
    }

    private void validatePhoneUnique(String phone, Integer excludeId) {
        if (phone == null || phone.isBlank()) return;
        if (isPhoneTaken(phone.trim(), excludeId)) {
            throw new IllegalArgumentException("Số điện thoại đã tồn tại trong hệ thống");
        }
    }

    /**
     * CCCD/MST định danh duy nhất một pháp nhân nên không thể dùng chung. Nhánh "để trống thì bỏ qua"
     * chỉ còn là phòng — {@link #validateRequiredFields} đã chặn giá trị rỗng từ trước đó.
     */
    private void validateTaxCodeUnique(boolean company, String taxCode, Integer excludeId) {
        if (taxCode == null || taxCode.isBlank()) return;
        if (isTaxCodeTaken(taxCode.trim(), excludeId)) {
            throw new IllegalArgumentException(company
                    ? "Mã số thuế đã tồn tại trong hệ thống"
                    : "Số CCCD/CMND đã tồn tại trong hệ thống");
        }
    }

    /** Dùng chung cho cả kiểm tra lúc lưu và endpoint kiểm trùng của màn tạo/sửa. */
    @Transactional(readOnly = true)
    public boolean isPhoneTaken(String phone, Integer excludeId) {
        if (phone == null || phone.isBlank()) return false;
        String value = phone.trim();
        return excludeId == null
                ? customerRepository.existsByPhoneNumber(value)
                : customerRepository.existsByPhoneNumberAndIdNot(value, excludeId);
    }

    @Transactional(readOnly = true)
    public boolean isTaxCodeTaken(String taxCode, Integer excludeId) {
        if (taxCode == null || taxCode.isBlank()) return false;
        String value = taxCode.trim();
        return excludeId == null
                ? customerRepository.existsByTaxCode(value)
                : customerRepository.existsByTaxCodeAndIdNot(value, excludeId);
    }

    private boolean matchesKeyword(Customer c, String kw) {
        if (kw.isBlank()) return true;
        return contains(c.getName(), kw)
                || contains(c.getPhoneNumber(), kw)
                || contains(c.getTaxCode(), kw);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}