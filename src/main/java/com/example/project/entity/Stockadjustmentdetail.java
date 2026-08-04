package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "stockadjustmentdetail")
public class Stockadjustmentdetail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stockAdjustmentDetailID", nullable = false)
    private Integer id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stockAdjustmentID", nullable = false)
    private Stockadjustment stockAdjustmentID;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "productID", nullable = false)
    private Product productID;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "productUnitID", nullable = false)
    private Productunit productUnitID;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batchID", nullable = false)
    private Batch batchID;

    @Size(max = 50)
    @NotNull
    @Column(name = "direction", nullable = false, length = 50)
    private String direction;

    @NotNull
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @NotNull
    @Column(name = "baseQtyDeducted", nullable = false)
    private Integer baseQtyDeducted;

    @NotNull
    @Column(name = "unitCostPrice", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitCostPrice;

    @NotNull
    @Column(name = "lineCost", nullable = false, precision = 15, scale = 2)
    private BigDecimal lineCost;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "refSellPrice", precision = 15, scale = 2)
    private BigDecimal refSellPrice;

    @Column(name = "oldExpirationDate")
    private LocalDate oldExpirationDate;

    @Column(name = "newExpirationDate")
    private LocalDate newExpirationDate;

}
