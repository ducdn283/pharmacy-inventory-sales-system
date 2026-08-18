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
@Table(name = "purchase_detail")
public class Purchasedetail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "purchaseDetailID", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchaseID")
    private Purchaseinvoice purchaseID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "productID")
    private Product productID;

    @NotNull
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @NotNull
    @Column(name = "importPrice", nullable = false, precision = 15, scale = 2)
    private BigDecimal importPrice;

    @Column(name = "productionDate")
    private LocalDate productionDate;

    @Column(name = "expirationDate")
    private LocalDate expirationDate;

    @Size(max = 50)
    @Column(name = "lotNumber", length = 50)
    private String lotNumber;

    @NotNull
    @Column(name = "returnQty", nullable = false)
    private Integer returnQty;

    @Column(name = "vatRate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Column(name = "preTaxAmount", precision = 15, scale = 2)
    private BigDecimal preTaxAmount;

    @Column(name = "vatAmount", precision = 15, scale = 2)
    private BigDecimal vatAmount;
}