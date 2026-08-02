package com.example.project.repository;

import com.example.project.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Integer> {

    @Query(
            value = """
                    select n
                    from Notification n
                    left join fetch n.accountID
                    where n.accountID.id = :accountId
                      and n.isActive = true
                      and (:keyword is null
                           or lower(n.title) like lower(concat('%', :keyword, '%'))
                           or lower(n.message) like lower(concat('%', :keyword, '%')))
                      and (:category is null or n.category = :category)
                      and (:severity is null or n.severity = :severity)
                      and (:status is null or n.status = :status)
                      and (:unreadOnly = false or n.isRead = false)
                    order by n.createdAt desc, n.id desc
                    """,
            countQuery = """
                    select count(n)
                    from Notification n
                    where n.accountID.id = :accountId
                      and n.isActive = true
                      and (:keyword is null
                           or lower(n.title) like lower(concat('%', :keyword, '%'))
                           or lower(n.message) like lower(concat('%', :keyword, '%')))
                      and (:category is null or n.category = :category)
                      and (:severity is null or n.severity = :severity)
                      and (:status is null or n.status = :status)
                      and (:unreadOnly = false or n.isRead = false)
                    """
    )
    Page<Notification> searchForAccount(
            @Param("accountId") Integer accountId,
            @Param("keyword") String keyword,
            @Param("category") String category,
            @Param("severity") String severity,
            @Param("status") String status,
            @Param("unreadOnly") boolean unreadOnly,
            Pageable pageable
    );

    @Query("""
           select n
           from Notification n
           left join fetch n.accountID
           where n.accountID.id = :accountId
             and n.isActive = true
           order by n.createdAt desc, n.id desc
           """)
    List<Notification> findActiveForAccount(
            @Param("accountId") Integer accountId
    );

    @Query("""
           select n
           from Notification n
           left join fetch n.accountID
           where n.accountID.id = :accountId
             and n.isActive = true
           order by n.createdAt desc, n.id desc
           limit 5
           """)
    List<Notification> findTop5ActiveForAccount(
            @Param("accountId") Integer accountId
    );

    @Query("""
           select n
           from Notification n
           left join fetch n.accountID
           where n.id = :notificationId
             and n.accountID.id = :accountId
           """)
    Optional<Notification> findByIdAndAccountId(
            @Param("notificationId") Integer notificationId,
            @Param("accountId") Integer accountId
    );

    long countByAccountID_IdAndIsActiveTrueAndIsReadFalse(Integer accountId);

    boolean existsByDedupeKeyAndIsActiveTrue(String dedupeKey);

    Optional<Notification> findFirstByDedupeKeyAndIsActiveTrue(String dedupeKey);

    @Query("""
           select n
           from Notification n
           where n.referenceType = :referenceType
             and n.referenceId = :referenceId
             and n.isActive = true
           """)
    List<Notification> findActiveByReference(
            @Param("referenceType") String referenceType,
            @Param("referenceId") Integer referenceId
    );

    @Query("""
           select n
           from Notification n
           where n.notificationType = :notificationType
             and n.referenceType = :referenceType
             and n.referenceId = :referenceId
             and n.isActive = true
           """)
    List<Notification> findActiveByTypeAndReference(
            @Param("notificationType") String notificationType,
            @Param("referenceType") String referenceType,
            @Param("referenceId") Integer referenceId
    );
}