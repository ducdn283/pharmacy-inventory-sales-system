package com.example.project.dto.response;

import com.example.project.entity.Financialsetting;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FinancialsettingResponse {
    private Integer id;
    private Integer taxCalculationMethod;
    private BigDecimal returnProductOnInvoiceValueRate;
    private Boolean autoGenerateVATInvoice;
    private String vatInvoiceSeries;
    private BigDecimal openingCashDefault;
    private String taxCode;
    private String locationCode;
    private String locationName;
    private String address;
    private String phoneNumber;
    private String email;
    private String bankAccountNumber;
    private String bankName;
    private Integer revenueGroup;
    private BigDecimal annualRevenueThreshold1;
    private BigDecimal annualRevenueThreshold2;
    private Boolean autoOffsetDebtOnRefund;
    private Integer returnPolicyMaxDays;
    private BigDecimal bankAccountBalance;
    private BigDecimal cashSafeBalance;
    private LocalDateTime balanceUpdatedAt;


    public static FinancialsettingResponse from(Financialsetting financialsetting) {
        return new FinancialsettingResponse(
                financialsetting.getId(),
                financialsetting.getTaxCalculationMethod(),
                financialsetting.getReturnProductOnInvoiceValueRate(),
                financialsetting.getAutoGenerateVATInvoice(),
                financialsetting.getVatInvoiceSeries(),
                financialsetting.getOpeningCashDefault(),
                financialsetting.getTaxCode(),
                financialsetting.getLocationCode(),
                financialsetting.getLocationName(),
                financialsetting.getAddress(),
                financialsetting.getPhoneNumber(),
                financialsetting.getEmail(),
                financialsetting.getBankAccountNumber(),
                financialsetting.getBankName(),
                financialsetting.getRevenueGroup(),
                financialsetting.getAnnualRevenueThreshold1(),
                financialsetting.getAnnualRevenueThreshold2(),
                financialsetting.getAutoOffsetDebtOnRefund(),
                financialsetting.getReturnPolicyMaxDays(),
                financialsetting.getBankAccountBalance(),
                financialsetting.getCashSafeBalance(),
                financialsetting.getBalanceUpdatedAt()
        );
    }
}