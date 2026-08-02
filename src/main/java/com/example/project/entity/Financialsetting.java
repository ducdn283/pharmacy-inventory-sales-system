package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "financialsetting")
public class Financialsetting {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "financialSettingID", nullable = false)
    private Integer id;

    @ColumnDefault("1")
    @Column(name = "taxCalculationMethod")
    private Integer taxCalculationMethod;

    @ColumnDefault("0.00")
    @Column(name = "returnProductOnInvoiceValueRate", precision = 5, scale = 2)
    private BigDecimal returnProductOnInvoiceValueRate;

    @ColumnDefault("0")
    @Column(name = "autoGenerateVATInvoice")
    private Boolean autoGenerateVATInvoice;

    @Size(max = 10)
    @NotNull
    @Column(name = "vatInvoiceSeries", nullable = false, length = 10)
    private String vatInvoiceSeries;

    @ColumnDefault("0.00")
    @Column(name = "openingCashDefault", precision = 15, scale = 2)
    private BigDecimal openingCashDefault;

    @Size(max = 100)
    @NotNull
    @Column(name = "taxCode", nullable = false, length = 100)
    private String taxCode;

    @Size(max = 20)
    @NotNull
    @Column(name = "locationCode", nullable = false, length = 20)
    private String locationCode;

    @Size(max = 100)
    @NotNull
    @Column(name = "locationName", nullable = false, length = 100)
    private String locationName;

    @Size(max = 100)
    @NotNull
    @Column(name = "address", nullable = false, length = 100)
    private String address;

    @Size(max = 10)
    @NotNull
    @Column(name = "phoneNumber", nullable = false, length = 10)
    private String phoneNumber;

    @Size(max = 100)
    @NotNull
    @Column(name = "email", nullable = false, length = 100)
    private String email;

    @Size(max = 20)
    @NotNull
    @Column(name = "bankAccountNumber", nullable = false, length = 20)
    private String bankAccountNumber;

    @Size(max = 100)
    @NotNull
    @Column(name = "bankName", nullable = false, length = 100)
    private String bankName;

    @ColumnDefault("1")
    @Column(name = "revenueGroup")
    private Integer revenueGroup;

    @ColumnDefault("1000000000.00")
    @Column(name = "annualRevenueThreshold1", precision = 15, scale = 2)
    private BigDecimal annualRevenueThreshold1;

    @ColumnDefault("3000000000.00")
    @Column(name = "annualRevenueThreshold2", precision = 15, scale = 2)
    private BigDecimal annualRevenueThreshold2;

    @ColumnDefault("1")
    @Column(name = "autoOffsetDebtOnRefund")
    private Boolean autoOffsetDebtOnRefund;

    @Column(name = "returnPolicyMaxDays")
    private Integer returnPolicyMaxDays;

    @Column(name = "bankAccountBalance", precision = 15, scale = 2)
    private BigDecimal bankAccountBalance;

    @Column(name = "cashSafeBalance", precision = 15, scale = 2)
    private BigDecimal cashSafeBalance;

    @Column(name = "balanceUpdatedAt")
    private LocalDateTime balanceUpdatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}