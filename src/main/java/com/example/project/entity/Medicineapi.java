package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "medicine_api")
public class Medicineapi {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "medicineAPIID", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "productID")
    private Product productID;

    @Size(max = 100)
    @NotNull
    @Column(name = "apiName", nullable = false, length = 100)
    private String apiName;

    @Size(max = 50)
    @Column(name = "strength", length = 50)
    private String strength;


}