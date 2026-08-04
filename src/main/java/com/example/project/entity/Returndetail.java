package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "returndetail")
public class Returndetail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "returnDetailID", nullable = false)
    private Integer id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "returnID", nullable = false)
    private Return returnID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoiceDetailID")
    private Invoicedetail invoiceDetailID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchaseDetailID")
    private Purchasedetail purchaseDetailID;

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

    @NotNull
    @Column(name = "returnQty", nullable = false)
    private Integer returnQty;

    @NotNull
    @Column(name = "baseQtyRestored", nullable = false)
    private Integer baseQtyRestored;

    @NotNull
    @Column(name = "unitSellPrice", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitSellPrice;

    @NotNull
    @Column(name = "lineRefund", nullable = false, precision = 15, scale = 2)
    private BigDecimal lineRefund;

    @ColumnDefault("1")
    @Column(name = "restockable")
    private Boolean restockable;

    @NotNull
    @Column(name = "originalLineValue", nullable = false, precision = 15, scale = 2)
    private BigDecimal originalLineValue;

}