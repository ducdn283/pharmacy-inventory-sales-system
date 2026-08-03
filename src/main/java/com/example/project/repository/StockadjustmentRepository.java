package com.example.project.repository;

import com.example.project.entity.Stockadjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.List;

public interface StockadjustmentRepository extends JpaRepository<Stockadjustment, Integer> {

    @Query("""
           select s
           from Stockadjustment s
           left join fetch s.stockReviewID sc
           left join fetch sc.createdBy
           order by s.date desc
           """)
    List<Stockadjustment> findAllWithRelations();

    @Query("""
           select s
           from Stockadjustment s
           left join fetch s.stockReviewID sc
           left join fetch sc.createdBy
           where s.id = :id
           """)
    Optional<Stockadjustment> findByIdWithRelations(Integer id);
}