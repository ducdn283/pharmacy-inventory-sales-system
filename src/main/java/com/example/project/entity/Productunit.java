package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "product_unit")
public class Productunit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "productUnitID", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "productID")
    private Product productID;

    @Size(max = 20)
    @NotNull
    @Column(name = "unitName", nullable = false, length = 20)
    private String unitName;

    @NotNull
    @Column(name = "ratio", nullable = false, precision = 10, scale = 4)
    private BigDecimal ratio;

    @NotNull
    @Column(name = "sellPrice", nullable = false, precision = 15, scale = 2)
    private BigDecimal sellPrice;

    @ColumnDefault("0")
    @Column(name = "isDefault")
    private Boolean isDefault;

    @ColumnDefault("0")
    @Column(name = "isBaseUnit")
    private Boolean isBaseUnit;

    @ColumnDefault("1")
    @Column(name = "isActive")
    private Boolean isActive;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;


}
