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
@Table(name = "`return`")
public class Return {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "returnID", nullable = false)
    private Integer id;

    @Size(max = 50)
    @NotNull
    @Column(name = "returnCode", nullable = false, length = 50)
    private String returnCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoiceID")
    private Invoice invoiceID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchaseID")
    private Purchaseinvoice purchaseID;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "returnedBy", nullable = false)
    private Account returnedBy;

    @NotNull
    @Column(name = "returnDate", nullable = false)
    private Instant returnDate;

    @Size(max = 50)
    @NotNull
    @Column(name = "returnType", nullable = false, length = 50)
    private String returnType;

    @NotNull
    @Column(name = "totalRefund", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalRefund;

    @ColumnDefault("0.00")
    @Column(name = "offsetDebtAmount", precision = 15, scale = 2)
    private BigDecimal offsetDebtAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shiftReportID")
    private Shiftreport shiftReportID;

    @NotNull
    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Size(max = 50)
    @NotNull
    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "approvedAt")
    private Instant approvedAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "appliedRefundRate", precision = 5, scale = 2)
    private BigDecimal appliedRefundRate;
}
