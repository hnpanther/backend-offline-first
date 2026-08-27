package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.entity.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
    List<UserRole> findByUserId(Long userId);

    /** Every assignment for a page of users in one query, rather than one query per row. */
    List<UserRole> findByUserIdIn(Collection<Long> userIds);
    List<UserRole> findByRoleId(Long roleId);
    void deleteByUserId(Long userId);

    @Query("""
            SELECT r.code FROM Role r
            JOIN UserRole ur ON ur.roleId = r.id
            WHERE ur.userId = :userId
            """)
    List<String> findRoleCodesByUserId(Long userId);

    /**
     * Ids of <b>active</b> users holding {@code roleCode}, ignoring {@code excludedUserId}.
     *
     * <p>Used to answer "would this change leave nobody able to administer the system?" before
     * a delete, a deactivation, or a role removal. The exclusion is what makes it answerable
     * for the user being edited: the question is about everyone <em>else</em>.
     */
    @Query("""
            SELECT ur.userId FROM UserRole ur
            JOIN Role r ON r.id = ur.roleId
            JOIN User u ON u.id = ur.userId
            WHERE r.code = :roleCode AND u.active = true AND ur.userId <> :excludedUserId
            """)
    List<Long> findOtherActiveUserIdsWithRole(String roleCode, Long excludedUserId);

    /**
     * Everyone holding {@code roleCode}, active or not. The batch counterpart of
     * {@link #findRoleCodesByUserId}: asking "does this user hold the role" for a whole page.
     */
    @Query("""
            SELECT ur.userId FROM UserRole ur
            JOIN Role r ON r.id = ur.roleId
            WHERE r.code = :roleCode
            """)
    List<Long> findUserIdsWithRole(String roleCode);

    /**
     * <b>Active</b> holders of {@code roleCode}, with nobody excluded — the batch counterpart of
     * {@link #findOtherActiveUserIdsWithRole}, which answers the same question one user at a time.
     * Subtracting the user under test from this set gives that method's result without a query
     * per row.
     */
    @Query("""
            SELECT ur.userId FROM UserRole ur
            JOIN Role r ON r.id = ur.roleId
            JOIN User u ON u.id = ur.userId
            WHERE r.code = :roleCode AND u.active = true
            """)
    List<Long> findActiveUserIdsWithRole(String roleCode);
}
