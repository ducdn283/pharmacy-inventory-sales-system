package com.example.project.repository;

import com.example.project.entity.Stockreview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockreviewRepository extends JpaRepository<Stockreview, Integer> {

    @Query("""
           select sc
           from Stockreview sc
           left join fetch sc.createdBy
           left join fetch sc.approvedBy
           order by sc.reviewDate desc
           """)
    List<Stockreview> findAllWithRelations();

    @Query("""
           select sc
           from Stockreview sc
           left join fetch sc.createdBy
           left join fetch sc.approvedBy
           where sc.id = :id
           """)
    Optional<Stockreview> findByIdWithRelations(@Param("id") Integer id);
}