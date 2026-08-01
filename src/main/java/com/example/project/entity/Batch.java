package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "batch")
public class Batch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "batchID", nullable = false)
    private Integer id;

    @Size(max = 50)
    @NotNull
    @Column(name = "batchCode", nullable = false, length = 50)
    private String batchCode;

    @Size(max = 50)
    @NotNull
    @Column(name = "batchName", nullable = false, length = 50)
    private String batchName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "productID")
    private Product productID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchaseDetailID")
    private Purchasedetail purchaseDetailID;

    @NotNull
    @Column(name = "storageQuantity", nullable = false)
    private Integer storageQuantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "importUnitID")
    private Productunit importUnitID;

    @Column(name = "importQtyInUnit")
    private Integer importQtyInUnit;

    @NotNull
    @Column(name = "importPrice", nullable = false, precision = 15, scale = 2)
    private BigDecimal importPrice;

    @NotNull
    @Column(name = "importPricePerBase", nullable = false, precision = 15, scale = 2)
    private BigDecimal importPricePerBase;

    @NotNull
    @Column(name = "importDate", nullable = false)
    private Instant importDate;

    @Column(name = "productionDate")
    private LocalDate productionDate;

    @Column(name = "expirationDate")
    private LocalDate expirationDate;

    @Size(max = 50)
    @Column(name = "lotNumber", length = 50)
    private String lotNumber;

    @ColumnDefault("1")
    @Column(name = "status")
    private Boolean status;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
