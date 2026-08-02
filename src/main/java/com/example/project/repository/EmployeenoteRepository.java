package com.example.project.repository;

import com.example.project.entity.Employeenote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeenoteRepository
        extends JpaRepository<Employeenote, Integer> {

    List<Employeenote>
    findByAccountID_IdOrderByDateDescIdDesc(Integer accountId);
}