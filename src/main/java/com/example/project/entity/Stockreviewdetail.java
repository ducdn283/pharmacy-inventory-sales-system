package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "stock_review_detail")
public class Stockreviewdetail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stockReviewDetailID", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stockReviewID")
    private Stockreview stockReviewID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "productID")
    private Product productID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batchID")
    private Batch batchID;

    @NotNull
    @Column(name = "systemQty", nullable = false)
    private Integer systemQty;

    @NotNull
    @Column(name = "actualQty", nullable = false)
    private Integer actualQty;

    @Column(name = "discrepancy")
    private Integer discrepancy;

    @Column(name = "recordedExpirationDate")
    private LocalDate recordedExpirationDate;

    @Column(name = "actualExpirationDate")
    private LocalDate actualExpirationDate;

    @Size(max = 50)
    @Column(name = "conditionStatus", length = 50)
    private String conditionStatus;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;
}
