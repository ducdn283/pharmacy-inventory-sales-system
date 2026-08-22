package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "stock_adjustment")
public class Stockadjustment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stockAdjustmentID", nullable = false)
    private Integer id;

    @Size(max = 50)
    @NotNull
    @Column(name = "stockAdjustmentCode", nullable = false, length = 50)
    private String stockAdjustmentCode;

    @Size(max = 50)
    @NotNull
    @Column(name = "adjustmentType", nullable = false, length = 50)
    private String adjustmentType;

    @NotNull
    @Column(name = "date", nullable = false)
    private Instant date;

    @NotNull
    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stockReviewID")
    private Stockreview stockReviewID;

    @Size(max = 50)
    @NotNull
    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;


}
