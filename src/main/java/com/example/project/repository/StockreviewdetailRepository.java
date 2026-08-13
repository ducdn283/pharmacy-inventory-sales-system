package com.example.project.repository;

import com.example.project.entity.Stockreviewdetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StockreviewdetailRepository
        extends JpaRepository<Stockreviewdetail, Integer> {

    @Query("""
           select d
           from Stockreviewdetail d
           left join fetch d.stockReviewID
           left join fetch d.productID
           left join fetch d.batchID
           where d.stockReviewID.id = :stockReviewId
           order by d.id asc
           """)
    List<Stockreviewdetail> findByStockReviewIdWithRelations(
            @Param("stockReviewId") Integer stockReviewId
    );

    @Query("""
           select d
           from Stockreviewdetail d
           left join fetch d.stockReviewID
           left join fetch d.productID
           left join fetch d.batchID
           order by d.id asc
           """)
    List<Stockreviewdetail> findAllWithRelations();
}