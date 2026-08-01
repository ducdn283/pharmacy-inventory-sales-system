package com.example.project.service;

import com.example.project.dto.request.FinancialSettingUpdateRequest;
import com.example.project.dto.response.FinancialsettingResponse;
import com.example.project.entity.Financialsetting;
import com.example.project.repository.FinancialsettingRepository;
import com.example.project.repository.TaxperiodsnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
        entity.setPhoneNumber(request.getPhoneNumber());
        entity.setEmail(request.getEmail());
        entity.setBankAccountNumber(request.getBankAccountNumber());
        entity.setBankName(request.getBankName());
        entity.setAutoOffsetDebtOnRefund(Boolean.TRUE.equals(request.getAutoOffsetDebtOnRefund()));
        entity.setReturnPolicyMaxDays(request.getReturnPolicyMaxDays());

        return FinancialsettingResponse.from(financialsettingRepository.save(entity));
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