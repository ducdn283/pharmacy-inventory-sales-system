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
@Table(name = "invoice")
public class Invoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invoiceID", nullable = false)
    private Integer id;

    @Size(max = 7)
    @NotNull
    @Column(name = "invoicePattern", nullable = false, length = 7)
    private String invoicePattern;

    @Size(max = 50)
    @NotNull
    @Column(name = "invoiceNumber", nullable = false, length = 50)
    private String invoiceNumber;

    @NotNull
    @Column(name = "date", nullable = false)
    private LocalDateTime date;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employeeID")
    private Account employeeID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customerID")
    private Customer customerID;

    @NotNull
    @Column(name = "subtotal", nullable = false, precision = 15, scale = 2)
    private BigDecimal subtotal;

    @ColumnDefault("0.00")
    @Column(name = "discount", precision = 15, scale = 2)
    private BigDecimal discount;

    @NotNull
    @Column(name = "total", nullable = false, precision = 15, scale = 2)
    private BigDecimal total;

    @NotNull
    @Column(name = "paidByCash", nullable = false, precision = 15, scale = 2)
    private BigDecimal paidByCash;

    @NotNull
    @Column(name = "paidByBanking", nullable = false, precision = 15, scale = 2)
    private BigDecimal paidByBanking;

    @ColumnDefault("0.00")
    @Column(name = "debtAmount", precision = 15, scale = 2)
    private BigDecimal debtAmount;

    @ColumnDefault("0")
    @Column(name = "prescriptionRequired")
    private Boolean prescriptionRequired;

    @Size(max = 50)
    @NotNull
    @Column(name = "invoiceType", nullable = false, length = 50)
    private String invoiceType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "originalInvoiceID")
    private Invoice originalInvoiceID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "returnID")
    private Return returnID;

    @Size(max = 50)
    @NotNull
    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Size(max = 50)
    @Column(name = "returnStatus", length = 50)
    private String returnStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shiftReportID")
    private Shiftreport shiftReportID;

    @Column(name = "prescriptionCode", columnDefinition = "TEXT")
    private String prescriptionCode;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
