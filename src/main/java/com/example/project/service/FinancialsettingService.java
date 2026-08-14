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
     * Owner đã hoàn tất thiết lập ban đầu chưa. Khi false, toàn bộ hệ thống bị chặn (xem
     * SetupConfirmedInterceptor). Khi chuyển thành true, khoá vĩnh viễn 2 quỹ tiền
     * (cashSafeBalance/bankAccountBalance) — không khoá nhóm doanh thu.
     */
    @Transactional(readOnly = true)
    public boolean isSetupConfirmed() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(entity -> Boolean.TRUE.equals(entity.getSetupConfirmed()))
                .orElse(false);
    }

    /** Quỹ tiền mặt bị khoá khi đã chốt thiết lập, hoặc khi đã có số dư (khác null) từ trước. */
    @Transactional(readOnly = true)
    public boolean isCashSafeBalanceLocked() {
        return isSetupConfirmed() || financialsettingRepository.findFirstByOrderByIdAsc()
                .map(entity -> entity.getCashSafeBalance() != null)
                .orElse(false);
    }

    /** Tương tự isCashSafeBalanceLocked() nhưng cho quỹ ngân hàng. */
    @Transactional(readOnly = true)
    public boolean isBankAccountBalanceLocked() {
        return isSetupConfirmed() || financialsettingRepository.findFirstByOrderByIdAsc()
                .map(entity -> entity.getBankAccountBalance() != null)
                .orElse(false);
    }

    // Lấy cấu hình tài chính hiện tại; nếu chưa có bản ghi nào thì trả về giá trị mặc định.
    @Transactional(readOnly = true)
    public FinancialsettingResponse getSettings() {
        return financialsettingRepository.findFirstByOrderByIdAsc()
                .map(FinancialsettingResponse::from)
                .orElseGet(FinancialsettingService::defaultSettings);
    }

    // Lưu form thiết lập tài chính: cập nhật thông tin chung, khoá 2 quỹ tiền khi chốt thiết lập lần đầu.
    @Transactional
    public FinancialsettingResponse saveSettings(FinancialSettingUpdateRequest request) {
        Financialsetting entity = financialsettingRepository.findFirstByOrderByIdAsc()
                .orElseGet(Financialsetting::new);

        // Lưu lại trạng thái trước khi sửa để biết đây có phải lần lưu chốt thiết lập ban đầu không.
        boolean wasSetupConfirmed = Boolean.TRUE.equals(entity.getSetupConfirmed());
        // Nhóm doanh thu không bị khoá (khác với 2 quỹ tiền) — chuyển Nhóm 2 lên 3 cần cơ quan thuế
        // chấp thuận trước nên luôn phải sửa tay được. Việc tự chuyển Nhóm 1 → 2 vẫn chạy riêng ở
        // TaxperiodsnapshotService, không liên quan tới field này.
        Integer revenueGroup = request.getRevenueGroup();

        // Nhóm 3 (>ngưỡng 2) bắt buộc tính theo lợi nhuận — client JS đã khoá UI, nhưng chốt lại ở
        // server để không phụ thuộc vào JS phía client.
        Integer taxCalculationMethod = request.getTaxCalculationMethod();
        if (Integer.valueOf(3).equals(revenueGroup)) {
            taxCalculationMethod = 2;
        }

        entity.setTaxCalculationMethod(taxCalculationMethod);
        entity.setRevenueGroup(revenueGroup);
        // annualRevenueThreshold1/2: không còn đọc từ request — đã ẩn khỏi UI, giữ nguyên giá trị cũ.
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

        // Số dư khởi tạo chỉ nhập một lần: nếu cột đã có giá trị (đã khoá), giá trị gửi lên bị bỏ
        // qua hoàn toàn — kể từ lúc đó chỉ ExpenseService được phép đổi số này (qua adjustFundBalances).
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

        // Chốt thiết lập ban đầu: chừng nào chưa chốt, lần lưu này phải có đủ cả 2 số dư quỹ, thiếu
        // một trong hai thì từ chối toàn bộ (rollback). Đủ cả hai thì đánh dấu setupConfirmed = true,
        // khoá 2 quỹ vĩnh viễn từ lần lưu kế tiếp (nhóm doanh thu không bị khoá theo).
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
     * Dùng riêng cho Expense: cộng/trừ số dư quỹ khi phiếu chi giải ngân hoặc bị hủy sau khi đã giải
     * ngân. Chỉ tác động lên quỹ đã có số dư (đã khoá); quỹ "Chưa thiết lập" thì bỏ qua lặng lẽ.
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

    // Quy null về 0 để cộng/trừ số dư quỹ an toàn.
    private static BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    // Giá trị mặc định dùng khi hệ thống chưa từng lưu cấu hình tài chính nào.
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
