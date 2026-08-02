package com.example.project.service;

import com.example.project.dto.request.FinancialSettingUpdateRequest;
import com.example.project.dto.response.FinancialsettingResponse;
import com.example.project.entity.Financialsetting;
import com.example.project.repository.FinancialsettingRepository;
import com.example.project.repository.TaxperiodsnapshotRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class FinancialsettingService {
    private final FinancialsettingRepository financialsettingRepository;
    private final TaxperiodsnapshotRepository taxperiodsnapshotRepository;

    public FinancialsettingService(FinancialsettingRepository financialsettingRepository,
                                   TaxperiodsnapshotRepository taxperiodsnapshotRepository) {
        this.financialsettingRepository = financialsettingRepository;
        this.taxperiodsnapshotRepository = taxperiodsnapshotRepository;
    }

    /**
     * Khoá vì MỘT TRONG HAI lý do độc lập:
     * <ul>
     *   <li>đã có kỳ thuế nào đóng — từ đó {@code TaxperiodsnapshotService} tự giữ đồng bộ
     *   {@code revenueGroup} theo chuỗi kỳ ({@code applyAutomaticGroupTransition}/{@code
     *   closePeriod}), con người không còn quyền sửa qua form nữa; hoặc</li>
     *   <li>người dùng đã tự xác nhận khoá qua form (cột {@code revenueGroupConfirmed}) — nhóm doanh
     *   thu, giống quỹ tiền mặt/ngân hàng, chỉ sửa được qua form MỘT LẦN rồi khoá vĩnh viễn, kể cả
     *   khi chưa có kỳ thuế nào đóng.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public boolean isRevenueGroupLocked() {
        if (taxperiodsnapshotRepository.count() > 0) {
            return true;
        }
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(entity -> Boolean.TRUE.equals(entity.getRevenueGroupConfirmed()))
                .orElse(false);
    }

    /**
     * Số dư quỹ tiền mặt là số khởi tạo nhập MỘT LẦN DUY NHẤT — không giống {@code revenueGroup},
     * việc khoá ở đây không phụ thuộc một bảng khác mà chỉ dựa vào chính cột này: hễ đã có giá trị
     * (khác {@code null}) là coi như người dùng đã xác nhận khoá, vì từ lúc đó
     * {@code ExpenseService} bắt đầu cộng trừ số dư này theo thời gian thực — cho sửa tay đè lên sẽ
     * làm sai lệch dữ liệu đang được theo dõi tự động.
     */
    @Transactional(readOnly = true)
    public boolean isCashSafeBalanceLocked() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(entity -> entity.getCashSafeBalance() != null)
                .orElse(false);
    }

    /** Tương tự {@link #isCashSafeBalanceLocked()} nhưng cho quỹ ngân hàng — khoá độc lập với quỹ tiền mặt. */
    @Transactional(readOnly = true)
    public boolean isBankAccountBalanceLocked() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(entity -> entity.getBankAccountBalance() != null)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<FinancialsettingResponse> getAll() {
        return financialsettingRepository.findAll()
                .stream()
                .map(FinancialsettingResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public FinancialsettingResponse getSettings() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(FinancialsettingResponse::from)
                .orElseGet(FinancialsettingService::defaultSettings);
    }

    @Transactional
    public FinancialsettingResponse saveSettings(FinancialSettingUpdateRequest request) {
        Financialsetting entity = financialsettingRepository.findFirstByOrderByIdAsc()
                .orElseGet(Financialsetting::new);

        // Khoá vì kỳ thuế đã đóng HOẶC vì người dùng đã tự xác nhận khoá trước đó — xem
        // isRevenueGroupLocked(). Tính trực tiếp trên entity đã load thay vì gọi lại
        // isRevenueGroupLocked() để khỏi fetch hai lần trong cùng một transaction. Nếu chưa khoá,
        // lần lưu NÀY chính là "một lần sửa" được phép — sau đó revenueGroupConfirmed bật lên, khoá
        // vĩnh viễn từ lần lưu kế tiếp, kể cả khi vẫn chưa có kỳ thuế nào đóng.
        boolean revenueGroupLocked = taxperiodsnapshotRepository.count() > 0
                || Boolean.TRUE.equals(entity.getRevenueGroupConfirmed());
        Integer revenueGroup = revenueGroupLocked ? entity.getRevenueGroup() : request.getRevenueGroup();
        if (!revenueGroupLocked) {
            entity.setRevenueGroupConfirmed(true);
        }

        // Nhóm 3 (>ngưỡng 2) bắt buộc tính theo lợi nhuận — client JS đã khoá UI, nhưng chốt lại ở
        // server để không phụ thuộc vào JS phía client.
        Integer taxCalculationMethod = request.getTaxCalculationMethod();
        if (Integer.valueOf(3).equals(revenueGroup)) {
            taxCalculationMethod = 2;
        }

        entity.setTaxCalculationMethod(taxCalculationMethod);
        entity.setRevenueGroup(revenueGroup);
        entity.setAnnualRevenueThreshold1(request.getAnnualRevenueThreshold1());
        entity.setAnnualRevenueThreshold2(request.getAnnualRevenueThreshold2());
        entity.setReturnProductOnInvoiceValueRate(request.getReturnProductOnInvoiceValueRate());
        entity.setAutoGenerateVATInvoice(Boolean.TRUE.equals(request.getAutoGenerateVATInvoice()));
        entity.setVatInvoiceSeries(request.getVatInvoiceSeries());
        entity.setOpeningCashDefault(request.getOpeningCashDefault());
        entity.setTaxCode(request.getTaxCode());
        entity.setLocationCode(request.getLocationCode());
        entity.setLocationName(request.getLocationName());
        entity.setAddress(request.getAddress());
        entity.setPhoneNumber(request.getPhoneNumber());
        entity.setEmail(request.getEmail());
        entity.setBankAccountNumber(request.getBankAccountNumber());
        entity.setBankName(request.getBankName());
        entity.setAutoOffsetDebtOnRefund(Boolean.TRUE.equals(request.getAutoOffsetDebtOnRefund()));
        entity.setReturnPolicyMaxDays(request.getReturnPolicyMaxDays());

        // Số dư khởi tạo chỉ được NHẬP MỘT LẦN. Một khi cột đã khác null (xem isCashSafeBalanceLocked/
        // isBankAccountBalanceLocked), giá trị đăng lên bị bỏ qua hoàn toàn — kể cả khi ai đó lách
        // control đã disable ở client để cố gửi lên một số khác — vì từ lúc khoá, ExpenseService là
        // nơi DUY NHẤT còn được phép đổi số này (cộng trừ theo thời gian thực mỗi khi phiếu chi giải
        // ngân/bị hủy). Hai quỹ khoá độc lập nhau.
        boolean balanceChanged = false;
        if (entity.getCashSafeBalance() == null && request.getCashSafeBalance() != null) {
            entity.setCashSafeBalance(request.getCashSafeBalance());
            balanceChanged = true;
        }
        if (entity.getBankAccountBalance() == null && request.getBankAccountBalance() != null) {
            entity.setBankAccountBalance(request.getBankAccountBalance());
            balanceChanged = true;
        }
        if (balanceChanged) {
            entity.setBalanceUpdatedAt(LocalDateTime.now());
        }

        return FinancialsettingResponse.from(saveGuardingConcurrentEdit(entity));
    }

    /**
     * Cộng dồn {@code cashDelta}/{@code bankDelta} (âm = trừ, dương = cộng lại) vào số dư quỹ —
     * gọi bởi {@code ExpenseService} mỗi khi một phiếu chi thực sự giải ngân (trừ) hoặc bị hủy sau
     * khi đã giải ngân (cộng lại). Chỉ quỹ ĐÃ ĐƯỢC THIẾT LẬP (khác null, tức đã khoá — xem
     * {@link #isCashSafeBalanceLocked}) mới bị/được cộng trừ; quỹ còn "Chưa thiết lập" thì chưa có
     * mốc nào để theo dõi real-time nên delta của quỹ đó bị bỏ qua lặng lẽ, không phải lỗi.
     */
    @Transactional
    public void adjustFundBalances(BigDecimal cashDelta, BigDecimal bankDelta) {
        boolean cashRequested = cashDelta != null && cashDelta.compareTo(BigDecimal.ZERO) != 0;
        boolean bankRequested = bankDelta != null && bankDelta.compareTo(BigDecimal.ZERO) != 0;
        if (!cashRequested && !bankRequested) {
            return;
        }

        Financialsetting entity = financialsettingRepository.findFirstByOrderByIdAsc().orElse(null);
        if (entity == null) {
            return;
        }

        boolean touched = false;
        if (cashRequested && entity.getCashSafeBalance() != null) {
            entity.setCashSafeBalance(entity.getCashSafeBalance().add(cashDelta));
            touched = true;
        }
        if (bankRequested && entity.getBankAccountBalance() != null) {
            entity.setBankAccountBalance(entity.getBankAccountBalance().add(bankDelta));
            touched = true;
        }
        if (!touched) {
            return;
        }

        entity.setBalanceUpdatedAt(LocalDateTime.now());
        saveGuardingConcurrentEdit(entity);
    }

    /**
     * Từ khi quỹ được Expense cập nhật real-time, có thể có nhiều nơi ghi vào cùng một dòng
     * {@code Financialsetting} gần như đồng thời (Owner sửa thiết lập tay + một phiếu chi vừa được
     * duyệt) — {@code saveAndFlush} buộc kiểm tra {@code @Version} ngay trong transaction hiện tại
     * thay vì im lặng đến lúc commit, cùng cơ chế {@code PurchaseinvoiceService}/{@code
     * InvoiceService} đã dùng.
     */
    private Financialsetting saveGuardingConcurrentEdit(Financialsetting entity) {
        try {
            return financialsettingRepository.saveAndFlush(entity);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new IllegalArgumentException(
                    "Thiết lập tài chính vừa được cập nhật ở nơi khác (có thể do một phiếu chi vừa"
                            + " giải ngân). Vui lòng tải lại trang rồi thử lại.", exception);
        }
    }

    /**
     * Cộng/trừ số dư quỹ tiền mặt và quỹ ngân hàng theo delta. Delta dương = thu (hóa đơn bán),
     * delta âm = chi (phiếu chi đã duyệt, v.v.).
     */
    @Transactional
    public void applyFundDelta(BigDecimal cashDelta, BigDecimal bankingDelta) {
        BigDecimal cash = cashDelta != null ? cashDelta : BigDecimal.ZERO;
        BigDecimal banking = bankingDelta != null ? bankingDelta : BigDecimal.ZERO;
        if (cash.compareTo(BigDecimal.ZERO) == 0 && banking.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        Financialsetting entity = financialsettingRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException("Chưa cấu hình thiết lập tài chính"));
        entity.setCashSafeBalance(nullToZero(entity.getCashSafeBalance()).add(cash));
        entity.setBankAccountBalance(nullToZero(entity.getBankAccountBalance()).add(banking));
        entity.setBalanceUpdatedAt(LocalDateTime.now());
        financialsettingRepository.save(entity);
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static FinancialsettingResponse defaultSettings() {
        return new FinancialsettingResponse(
                null,
                1,
                BigDecimal.ZERO,
                false,
                "",
                BigDecimal.ZERO,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                1,
                BigDecimal.valueOf(1_000_000_000L),
                BigDecimal.valueOf(3_000_000_000L),
                true,
                null,
                null,
                null,
                null
        );
    }
}