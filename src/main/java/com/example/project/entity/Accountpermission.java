package com.example.project.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "account_permission")
public class Accountpermission {
    @Id
    @Column(name = "accountPermissionID", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accountID")
    private Account accountID;

    @Size(max = 50)
    @Column(name = "role", length = 50)
    private String role;

}