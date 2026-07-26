package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "expense")
public class Expense {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "expenseID", nullable = false)
    private Integer id;

    @Size(max = 50)
    @NotNull
    @Column(name = "expenseCode", nullable = false, length = 50)
    private String expenseCode;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicantID", nullable = false)
    private Account applicantID;

    @Size(max = 50)
    @NotNull
    @Column(name = "expenseType", nullable = false, length = 50)
    private String expenseType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "returnID")
    private Return returnID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchaseID")
    private Purchaseinvoice purchaseID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shiftReportID")
    private Shiftreport shiftReportID;

    @NotNull
    @Column(name = "date", nullable = false)
    private Instant date;

    @NotNull
    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @NotNull
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @NotNull
    @Column(name = "paid", nullable = false, precision = 15, scale = 2)
    private BigDecimal paid;

    @ColumnDefault("0.00")
    @Column(name = "paidByCash", precision = 15, scale = 2)
    private BigDecimal paidByCash;

    @ColumnDefault("0.00")
    @Column(name = "paidByBanking", precision = 15, scale = 2)
    private BigDecimal paidByBanking;

    @ColumnDefault("0.00")
    @Column(name = "paidByCredit", precision = 15, scale = 2)
    private BigDecimal paidByCredit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplierID")
    private Supplier supplierID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customerID")
    private Customer customerID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accountID")
    private Account accountID;

    @Size(max = 50)
    @NotNull
    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "approvedAt")
    private Instant approvedAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;


}
