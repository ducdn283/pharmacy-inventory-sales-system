package com.example.project.repository;

import com.example.project.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Integer> {

    @Query("""
       select i
       from Invoice i
       left join fetch i.employeeID
       left join fetch i.customerID
       """)
    List<Invoice> findAllWithRelations();

    @Query("""
       select i
       from Invoice i
       left join fetch i.employeeID
       left join fetch i.customerID
       left join fetch i.originalInvoiceID
       left join fetch i.rootInvoiceID
       where i.id = :invoiceId
       """)
    Optional<Invoice> findByIdWithRelations(@Param("invoiceId") Integer invoiceId);
}
