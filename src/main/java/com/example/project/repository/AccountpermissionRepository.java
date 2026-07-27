package com.example.project.repository;

import com.example.project.entity.Accountpermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountpermissionRepository extends JpaRepository<Accountpermission, Integer> {

    @Query("""
           select ap
           from Accountpermission ap
           left join fetch ap.accountID
           where ap.accountID.id = :accountId
           order by ap.id
           """)
    List<Accountpermission> findByAccountId(@Param("accountId") Integer accountId);

    @Query("""
           select ap
           from Accountpermission ap
           left join fetch ap.accountID
           where ap.accountID.id = :accountId
           order by ap.id
           """)
    List<Accountpermission> findProfilePermissionsByAccountId(@Param("accountId") Integer accountId);

    @Query("""
           select ap
           from Accountpermission ap
           left join fetch ap.accountID
           order by ap.id
           """)
    List<Accountpermission> findAllWithAccount();

    @Query("select coalesce(max(ap.id), 0) from Accountpermission ap")
    Integer findMaxId();

    @Query("""
           select ap
           from Accountpermission ap
           left join fetch ap.accountID
           where upper(ap.role) = 'OWNER'
           order by ap.id
           """)
    List<Accountpermission> findOwnerAssignments();

    default Optional<Accountpermission> findFirstOwnerPermission() {
        return findOwnerAssignments().stream().findFirst();
    }

    @Query("""
           select count(ap) > 0
           from Accountpermission ap
           where ap.accountID.id = :accountId
           and upper(ap.role) = upper(:role)
           """)
    boolean existsByAccountIdAndRole(@Param("accountId") Integer accountId,
                                     @Param("role") String role);

    /**
     * Whether anyone currently holds this role on an <em>enabled</em> account. Used by the tax
     * period, where the BA's rule is that the Accountant closes a period and the Owner may only do
     * so when the pharmacy has no accountant — a permission that depends on live data rather than
     * on the signed-in user alone, so a disabled accountant account hands the job back to the Owner.
     */
    @Query("""
           select count(ap) > 0
           from Accountpermission ap
           where upper(ap.role) = upper(:role)
           and ap.accountID.status = true
           """)
    boolean existsActiveByRole(@Param("role") String role);
}