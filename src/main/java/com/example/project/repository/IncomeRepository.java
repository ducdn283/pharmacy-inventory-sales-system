package com.example.project.repository;

import com.example.project.entity.Income;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface IncomeRepository extends JpaRepository<Income, Integer> {

    @Query("""
           select i
           from Income i
           left join fetch i.applicantID
           left join fetch i.invoiceID
           left join fetch i.returnID
           left join fetch i.shiftReportID
           left join fetch i.supplierID
           left join fetch i.customerID
           left join fetch i.accountID
           left join fetch i.stockAdjustmentID
           where i.id = :id
           """)
    Optional<Income> findByIdWithRelations(Integer id);

    @Query("""
           select i
           from Income i
           left join fetch i.applicantID
           left join fetch i.invoiceID
           left join fetch i.returnID
           left join fetch i.shiftReportID
           left join fetch i.supplierID
           left join fetch i.customerID
           left join fetch i.accountID
           left join fetch i.stockAdjustmentID
           """)
    List<Income> findAllWithRelations();
}
