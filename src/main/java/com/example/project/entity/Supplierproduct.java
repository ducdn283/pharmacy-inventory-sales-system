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
@Table(name = "supplier_product")
public class Supplierproduct {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "supplierProductID", nullable = false)
    private Integer id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplierID", nullable = false)
    private Supplier supplierID;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "productID", nullable = false)
    private Product productID;

    @Column(name = "costPrice", precision = 15, scale = 2)
    private BigDecimal costPrice;

    @ColumnDefault("0")
    @Column(name = "isPreferred")
    private Boolean isPreferred;

    @ColumnDefault("1")
    @Column(name = "isActive")
    private Boolean isActive;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;


}
