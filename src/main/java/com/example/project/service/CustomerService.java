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

    // ------------------------------------------------------------------ list

    @Transactional(readOnly = true)
    public Page<CustomerResponse> list(String keyword, String type, Pageable pageable) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String typeFilter = (type == null || type.isBlank()) ? null : type.trim();

        List<CustomerResponse> filtered = customerRepository.findAll().stream()
                .filter(c -> matchesKeyword(c, kw))
                .filter(c -> typeFilter == null || typeFilter.equals(c.getCustomerType()))
                // Sắp theo MÃ (= customerID) cho khớp cột mã người dùng đọc đầu tiên, giống màn NCC.
                // Sắp theo tên thì mã nhảy lung tung giữa danh sách, và khách vừa tạo không biết ở đâu.
                .sorted(Comparator.comparing(Customer::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(CustomerResponse::from)
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<CustomerResponse> content = start >= filtered.size() ? List.of() : filtered.subList(start, end);
        return new PageImpl<>(content, pageable, filtered.size());
    }

    // ------------------------------------------------------------------ stats

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

    // ------------------------------------------------------------------ getById

    @Transactional(readOnly = true)
    public CustomerResponse getById(Integer id) {
        return CustomerResponse.from(findOrThrow(id));
    }

    /** 5 hóa đơn gần nhất của khách hàng (suy ra từ FK Invoice.customerID). */
    @Transactional(readOnly = true)
    public List<CustomerInvoiceResponse> getRecentInvoices(Integer customerId) {
        return invoiceRepository.findAll().stream()
                .filter(i -> i.getCustomerID() != null && customerId.equals(i.getCustomerID().getId()))
                .sorted(Comparator.comparing(Invoice::getDate,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(5)
                .map(CustomerInvoiceResponse::from)
                .toList();
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

    // ------------------------------------------------------------------ create

    @Transactional
    public Integer create(CustomerRequest req) {
        validate(req, null);
        Customer c = new Customer();
        apply(c, req);
        return saveGuardingUniqueRace(c, req).getId();
    }

    // ------------------------------------------------------------------ update

    @Transactional
    public void update(Integer id, CustomerRequest req) {
        Customer c = findOrThrow(id);
        validate(req, id);
        apply(c, req);
        saveGuardingUniqueRace(c, req);
    }

    // ------------------------------------------------------------------ legacy

    @Transactional(readOnly = true)
    public List<CustomerResponse> getAll() {
        return customerRepository.findAll()
                .stream()
                .map(CustomerResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private Customer findOrThrow(Integer id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khách hàng"));
    }

    /**
     * Lưới an toàn cuối cùng cho tính duy nhất: bảng {@code customer} nay có UNIQUE index trên
     * {@code phoneNumber} và {@code taxCode}, và <strong>chỉ DB mới phân xử được</strong> khi hai người
     * nhập cùng lúc — kiểu "hỏi rồi mới ghi" của {@link #validate} luôn có khe hở giữa lúc hỏi và lúc
     * ghi, người thứ hai đọc thấy "chưa ai dùng" rồi cả hai cùng ghi.
     *
     * <p>{@link #validate} vẫn giữ nguyên và vẫn là nơi lo 99% ca thường (nó phân biệt được lỗi định
     * dạng với lỗi trùng, và cho câu thông báo gắn đúng ô nhập). Hàm này chỉ dịch lỗi DB của ca đua
     * hiếm sang <em>đúng câu thông báo đó</em>, để người dùng không bao giờ thấy trang 500 thô.
     *
     * <p>{@code saveAndFlush} chứ không phải {@code save}: khi sửa (UPDATE) thì {@code save} chỉ đưa vào
     * session, câu lệnh thật chạy lúc commit — tức là sau khi ra khỏi khối {@code try} này.
     *
     * <p>MySQL cho phép NHIỀU dòng NULL trong UNIQUE index, nên khách lẻ không khai CCCD vẫn tạo được
     * bao nhiêu bản ghi cũng được — đúng như nghiệp vụ cần.
     */
    private Customer saveGuardingUniqueRace(Customer c, CustomerRequest req) {
        try {
            return customerRepository.saveAndFlush(c);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException(duplicateMessage(exception,
                    "COMPANY".equals(req.getCustomerType())), exception);
        }
    }

    /**
     * Suy ra ô nào bị trùng từ tên UNIQUE index trong thông báo lỗi của MySQL. Tên index trùng tên cột
     * nên đọc thẳng được. Không nhận ra thì trả câu chung — thà chung chung còn hơn chỉ sai ô.
     */
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
     * Tên UNIQUE index bị vi phạm, lấy từ câu lỗi MySQL
     * {@code Duplicate entry '<giá trị>' for key '<bảng>.<index>'}.
     *
     * <p>Phải cắt lấy đúng phần sau {@code for key}, KHÔNG được dò cả câu: chính
     * <em>giá trị</em> bị trùng cũng nằm trong câu đó, nên một CCCD trùng của khách tên
     * {@code "phoneNumber"}, hay email {@code phone@...} bên NCC, sẽ khớp nhầm tên cột khác và chỉ sai ô.
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
     * Toàn bộ kiểm tra chạy TRƯỚC khi ghi bất cứ thứ gì vào entity — định dạng rồi mới tới trùng lặp,
     * để người dùng thấy lỗi định dạng ("CCCD phải 12 số") thay vì lỗi trùng của một giá trị vốn đã sai.
     *
     * <p><strong>Vì sao phải tự kiểm ở đây dù DB đã có UNIQUE:</strong> tầng này phân biệt được lỗi
     * định dạng với lỗi trùng và cho câu thông báo gắn đúng ô nhập, còn DB chỉ trả một câu lỗi kỹ thuật
     * chung. Thiếu một dòng ở đây là người dùng nhận thông báo khó hiểu — đúng như ca đã gặp: số điện
     * thoại được chặn nhưng CCCD thì không, nên cứ đổi số điện thoại là tạo được khách trùng CCCD.
     * Ca hai người nhập cùng lúc thì chỉ DB phân xử được, xem
     * {@link #saveGuardingUniqueRace(Customer, CustomerRequest)}.</p>
     *
     * @param excludeId id của bản ghi đang sửa (null khi tạo mới) — bản ghi luôn "trùng với chính nó"
     */
    private void validate(CustomerRequest req, Integer excludeId) {
        boolean company = "COMPANY".equals(req.getCustomerType());
        String phone = trimToNull(req.getPhoneNumber());
        String taxCode = trimToNull(req.getTaxCode());

        validateTaxCode(company, taxCode);
        validatePhoneUnique(phone, excludeId);
        validateTaxCodeUnique(company, taxCode, excludeId);
    }

    private void apply(Customer c, CustomerRequest req) {
        boolean company = "COMPANY".equals(req.getCustomerType());
        c.setCustomerType(company ? "COMPANY" : "INDIVIDUAL");
        c.setName(trimToNull(req.getName()));
        c.setPhoneNumber(trimToNull(req.getPhoneNumber()));
        c.setAddress(trimToNull(req.getAddress()));
        c.setNote(trimToNull(req.getNote()));
        // taxCode dùng cho CẢ 2 loại doanh nghiệp = Mã số thuế (MST),
        // cá nhân = số CCCD/CMND — cần để xuất hóa đơn/hóa đơn điều chỉnh cho khách.
        String taxCode = trimToNull(req.getTaxCode());
        c.setTaxCode(taxCode);
        // Thông tin ngân hàng chỉ áp dụng cho khách doanh nghiệp.
        c.setBankAccountNumber(company ? trimToNull(req.getBankAccountNumber()) : null);
        c.setBankName(company ? trimToNull(req.getBankName()) : null);
    }

    /**
     * Định dạng {@code taxCode} theo loại khách (bỏ qua khi để trống — không bắt buộc):
     * doanh nghiệp = Mã số thuế {@code 10} chữ số (tùy chọn {@code -3} chữ số chi nhánh, mirror Supplier);
     * cá nhân = số CCCD {@code 12} chữ số hoặc CMND {@code 9} chữ số.
     */
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
     * CCCD/CMND (khách cá nhân) và MST (khách doanh nghiệp) đều định danh duy nhất một pháp nhân —
     * hai khách hàng không thể dùng chung. Bỏ qua khi để trống: trường này KHÔNG bắt buộc, khách lẻ
     * mua thuốc thường không cần xuất hóa đơn nên không phải khai.
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