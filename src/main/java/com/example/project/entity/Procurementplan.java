package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "procurement_plan")
public class Procurementplan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "procurementID", nullable = false)
    private Integer id;

    @Size(max = 50)
    @NotNull
    @Column(name = "procurementCode", nullable = false, length = 50)
    private String procurementCode;

    @NotNull
    @Column(name = "date", nullable = false)
    private LocalDateTime date;

    @Size(max = 50)
    @NotNull
    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @NotNull
    @Column(name = "createdAt", nullable = false)
    private LocalDateTime createdAt;

}
