package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "taxperiodsnapshot")
public class Taxperiodsnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "taxPeriodSnapshotID", nullable = false)
    private Integer id;

    @Size(max = 10)
    @Column(name = "periodLabel", length = 10)
    private String periodLabel;

    @Column(name = "nextPeriodTaxType")
    private Integer nextPeriodTaxType;

    @Column(name = "startDate")
    private LocalDate startDate;

    @Column(name = "endDate")
    private LocalDate endDate;

    @Column(name = "incomeTax", precision = 15, scale = 2)
    private BigDecimal incomeTax;

    @Column(name = "vatOutput", precision = 15, scale = 2)
    private BigDecimal vatOutput;

    @Column(name = "vatInput", precision = 15, scale = 2)
    private BigDecimal vatInput;

    @Column(name = "vatCarryforwardIn", precision = 15, scale = 2)
    private BigDecimal vatCarryforwardIn;

    @Column(name = "vatCarryforwardOut", precision = 15, scale = 2)
    private BigDecimal vatCarryforwardOut;

    @Column(name = "cashBalanceAtPeriodEnd", precision = 15, scale = 2)
    private BigDecimal cashBalanceAtPeriodEnd;

    @Column(name = "recordedAt")
    private LocalDateTime recordedAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;
}
