package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "type")
public class Type {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "typeID", nullable = false)
    private Integer id;

    @Size(max = 100)
    @NotNull
    @Column(name = "sortType", nullable = false, length = 100)
    private String sortType;

    @Size(max = 100)
    @NotNull
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @NotNull
    @Column(name = "defaultVATRate", nullable = false, precision = 5, scale = 2)
    private BigDecimal defaultVATRate;
}
