package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.entity.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
    List<UserRole> findByUserId(String userId);
    List<UserRole> findByRoleId(String roleId);
    void deleteByUserId(String userId);

    @Query("""
            SELECT r.code FROM Role r
            JOIN UserRole ur ON ur.roleId = r.id
            WHERE ur.userId = :userId
            """)
    List<String> findRoleCodesByUserId(String userId);
}
