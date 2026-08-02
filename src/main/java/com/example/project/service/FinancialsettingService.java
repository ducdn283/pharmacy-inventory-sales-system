package com.example.project.service;

import com.example.project.dto.request.FinancialSettingUpdateRequest;
import com.example.project.dto.response.FinancialsettingResponse;
import com.example.project.entity.Financialsetting;
import com.example.project.repository.FinancialsettingRepository;
import com.example.project.repository.TaxperiodsnapshotRepository;
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
     * Once the first tax period has ever been closed, {@code revenueGroup} stops being something a
     * human sets — {@code TaxperiodsnapshotService} takes over and keeps it synced to the snapshot
     * chain's {@code nextPeriodTaxType} (see {@code applyAutomaticGroupTransition}/{@code
     * closePeriod}). Before that, there is no chain yet, so this is still the one seed a human must
     * provide.
     */
    @Transactional(readOnly = true)
    public boolean isRevenueGroupLocked() {
        return taxperiodsnapshotRepository.count() > 0;
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

        // Locked once a tax period exists — see isRevenueGroupLocked(). Whatever the form posted
        // (even a tampered value bypassing the disabled control) is ignored in favour of the group
        // TaxperiodsnapshotService already maintains.
        Integer revenueGroup = isRevenueGroupLocked() ? entity.getRevenueGroup() : request.getRevenueGroup();

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

        // Bỏ trống nghĩa là "giữ nguyên số dư hiện tại". Form luôn hiển thị sẵn giá trị đang lưu
        // (giống mọi trường khác), nên chỉ cập nhật mốc thời gian khi số vừa gửi lên THỰC SỰ khác số
        // đang lưu — nếu không, mỗi lần lưu một thiết lập không liên quan (ví dụ đổi số điện thoại)
        // cũng vô tình "chạm" vào mốc cập nhật quỹ. Ngoài chỉnh tay tại đây, số dư còn được cộng/trừ
        // tự động khi phát sinh giao dịch (xem {@link #applyFundDelta}).
        boolean balanceChanged = false;
        if (request.getCashSafeBalance() != null
                && (entity.getCashSafeBalance() == null || entity.getCashSafeBalance().compareTo(request.getCashSafeBalance()) != 0)) {
            entity.setCashSafeBalance(request.getCashSafeBalance());
            balanceChanged = true;
        }
        if (request.getBankAccountBalance() != null
                && (entity.getBankAccountBalance() == null || entity.getBankAccountBalance().compareTo(request.getBankAccountBalance()) != 0)) {
            entity.setBankAccountBalance(request.getBankAccountBalance());
            balanceChanged = true;
        }
        if (balanceChanged) {
            entity.setBalanceUpdatedAt(LocalDateTime.now());
        }

        return FinancialsettingResponse.from(financialsettingRepository.save(entity));
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