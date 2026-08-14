package com.example.project.repository;

import com.example.project.entity.Accountpermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.example.project.entity.Account;
import java.util.List;
import java.util.Optional;

public interface AccountpermissionRepository extends JpaRepository<Accountpermission, Integer> {

    // Lấy các bản ghi phân quyền của 1 tài khoản (kèm thông tin tài khoản).
    @Query("""
           select ap
           from Accountpermission ap
           left join fetch ap.accountID
           where ap.accountID.id = :accountId
           order by ap.id
           """)
    List<Accountpermission> findByAccountId(@Param("accountId") Integer accountId);

    // Giống findByAccountId, dùng riêng cho màn hình hồ sơ cá nhân (profile).
    @Query("""
           select ap
           from Accountpermission ap
           left join fetch ap.accountID
           where ap.accountID.id = :accountId
           order by ap.id
           """)
    List<Accountpermission> findProfilePermissionsByAccountId(@Param("accountId") Integer accountId);

    // Lấy toàn bộ bản ghi phân quyền kèm tài khoản, dùng để dựng Bảng phân quyền.
    @Query("""
           select ap
           from Accountpermission ap
           left join fetch ap.accountID
           order by ap.id
           """)
    List<Accountpermission> findAllWithAccount();

    // Id lớn nhất hiện có, dùng để tự sinh id mới (findMaxId()+1) khi tạo bản ghi phân quyền.
    @Query("select coalesce(max(ap.id), 0) from Accountpermission ap")
    Integer findMaxId();

    // Lấy các bản ghi đang giữ vai trò OWNER.
    @Query("""
           select ap
           from Accountpermission ap
           left join fetch ap.accountID
           where upper(ap.role) = 'OWNER'
           order by ap.id
           """)
    List<Accountpermission> findOwnerAssignments();

    // Bản ghi phân quyền của Owner đầu tiên tìm được, nếu có.
    default Optional<Accountpermission> findFirstOwnerPermission() {
        return findOwnerAssignments().stream().findFirst();
    }

    // True nếu tài khoản này đang giữ đúng vai trò được truyền vào.
    @Query("""
           select count(ap) > 0
           from Accountpermission ap
           where ap.accountID.id = :accountId
           and upper(ap.role) = upper(:role)
           """)
    boolean existsByAccountIdAndRole(@Param("accountId") Integer accountId,
                                     @Param("role") String role);

    /** True nếu có tài khoản đang bật (enabled) nào đang giữ vai trò này. */
    @Query("""
           select count(ap) > 0
           from Accountpermission ap
           where upper(ap.role) = upper(:role)
           and ap.accountID.status = true
           """)
    boolean existsActiveByRole(@Param("role") String role);

    // Danh sách tài khoản đang bật (status = true) đang giữ vai trò này.
    @Query("""
       select distinct a
       from Accountpermission ap
       join ap.accountID a
       where upper(ap.role) = upper(:role)
       and a.status = true
       order by a.id
       """)
    List<Account> findActiveAccountsByRole(@Param("role") String role);
}