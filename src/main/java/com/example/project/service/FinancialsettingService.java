package com.example.project.service;

import com.example.project.dto.request.FinancialSettingUpdateRequest;
import com.example.project.dto.response.FinancialsettingResponse;
import com.example.project.entity.Financialsetting;
import com.example.project.repository.FinancialsettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class FinancialsettingService {
    private final FinancialsettingRepository financialsettingRepository;

    public FinancialsettingService(FinancialsettingRepository financialsettingRepository) {
        this.financialsettingRepository = financialsettingRepository;
    }

    /**
     * Đã "Chốt thiết lập ban đầu" chưa — cột {@code setupConfirmed}. Đây là nguồn khoá cho hai quỹ
     * {@code cashSafeBalance}/{@code bankAccountBalance} MỘT LƯỢT (không còn khoá {@code
     * revenueGroup} — xem {@code saveSettings}), và cũng là điều kiện
     * {@link com.example.project.config.SetupConfirmedInterceptor} dùng để chặn mọi màn hình khác
     * cho tới khi Owner hoàn tất thiết lập lần đầu.
     */
    @Transactional(readOnly = true)
    public boolean isSetupConfirmed() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(entity -> Boolean.TRUE.equals(entity.getSetupConfirmed()))
                .orElse(false);
    }

    /**
     * Số dư quỹ tiền mặt khoá khi ĐÃ chốt thiết lập ban đầu, hoặc (để tương thích với dữ liệu cũ từ
     * trước khi có {@code setupConfirmed}) khi cột này đã khác {@code null} — từ lúc đó
     * {@code ExpenseService} bắt đầu cộng trừ số dư này theo thời gian thực, cho sửa tay đè lên sẽ
     * làm sai lệch dữ liệu đang được theo dõi tự động.
     */
    @Transactional(readOnly = true)
    public boolean isCashSafeBalanceLocked() {
        return isSetupConfirmed() || financialsettingRepository.findFirstByOrderByIdAsc()
                .map(entity -> entity.getCashSafeBalance() != null)
                .orElse(false);
    }

    /** Tương tự {@link #isCashSafeBalanceLocked()} nhưng cho quỹ ngân hàng. */
    @Transactional(readOnly = true)
    public boolean isBankAccountBalanceLocked() {
        return isSetupConfirmed() || financialsettingRepository.findFirstByOrderByIdAsc()
                .map(entity -> entity.getBankAccountBalance() != null)
                .orElse(false);
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

        // Chốt sẵn trạng thái TRƯỚC khi sửa entity — cần để biết đây có phải chính là lần lưu hoàn
        // tất thiết lập ban đầu hay không (xem khối kiểm tra cuối hàm).
        boolean wasSetupConfirmed = Boolean.TRUE.equals(entity.getSetupConfirmed());
        // Nhóm doanh thu KHÔNG bị khoá lại sau khi chốt thiết lập / sau khi đã có kỳ thuế đóng —
        // khác với hai quỹ tiền. Chuyển từ Nhóm 2 lên Nhóm 3 cần cơ quan thuế chấp thuận trước, nên
        // form phải luôn cho sửa tay được để phản ánh đúng thời điểm được chấp thuận, thay vì chỉ
        // đổi được một lần lúc thiết lập ban đầu. Việc tự động đồng bộ theo chuỗi kỳ thuế cho chiều
        // 1 → 2 (xem TaxperiodsnapshotService.applyAutomaticGroupTransition/closePeriod) vẫn chạy
        // song song không đổi — đây chỉ là bỏ khoá trên form, không đụng tới cơ chế tự động đó.
        Integer revenueGroup = request.getRevenueGroup();

        // Nhóm 3 (>ngưỡng 2) bắt buộc tính theo lợi nhuận — client JS đã khoá UI, nhưng chốt lại ở
        // server để không phụ thuộc vào JS phía client.
        Integer taxCalculationMethod = request.getTaxCalculationMethod();
        if (Integer.valueOf(3).equals(revenueGroup)) {
            taxCalculationMethod = 2;
        }

        entity.setTaxCalculationMethod(taxCalculationMethod);
        entity.setRevenueGroup(revenueGroup);
        // annualRevenueThreshold1/2: KHÔNG còn đọc từ request nữa — đã ẩn khỏi UI, cố định vĩnh viễn
        // theo giá trị đã seed (V13). Không set lại ở đây để giữ nguyên giá trị đang lưu.
        BigDecimal returnRate = request.getReturnProductOnInvoiceValueRate();
        if (returnRate != null && returnRate.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Tỷ lệ phần trăm hàng trả phải là số nguyên");
        }
        entity.setReturnProductOnInvoiceValueRate(returnRate == null ? null : returnRate.setScale(0));
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
        // ngân/bị hủy). Hai quỹ khoá độc lập nhau ở CƠ CHẾ, nhưng cùng bị khoá một lượt bởi
        // setupConfirmed bên dưới.
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

        // Chốt thiết lập ban đầu: chừng nào chưa chốt, lần lưu NÀY phải để lại đủ nhóm doanh thu (đã
        // được @NotNull chặn từ DTO) VÀ cả hai số dư quỹ — thiếu một trong hai thì từ chối toàn bộ
        // (rollback nhờ @Transactional) thay vì chốt dở dang rồi không còn cách nào sửa lại. Đủ cả ba
        // thì đánh dấu setupConfirmed = true, khoá luôn cả ba trường từ lần lưu kế tiếp.
        if (!wasSetupConfirmed) {
            if (entity.getCashSafeBalance() == null) {
                throw new IllegalArgumentException(
                        "Vui lòng nhập số dư quỹ tiền mặt trước khi hoàn tất thiết lập ban đầu");
            }
            if (entity.getBankAccountBalance() == null) {
                throw new IllegalArgumentException(
                        "Vui lòng nhập số dư quỹ ngân hàng trước khi hoàn tất thiết lập ban đầu");
            }
            entity.setSetupConfirmed(true);
        }

        return FinancialsettingResponse.from(financialsettingRepository.save(entity));
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
        financialsettingRepository.save(entity);
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
                true,
                null,
                null,
                null,
                null
        );
    }
}
