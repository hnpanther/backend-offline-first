package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("""
            SELECT u FROM User u
            WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(u.fullName, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<User> search(@Param("q") String q, Pageable pageable);
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    Optional<User> findByPersonnelCodeIgnoreCase(String personnelCode);
    Optional<User> findByNationalCode(String nationalCode);
    Optional<User> findByPhoneNumber(String phoneNumber);
    Optional<User> findByNfcTagIdIgnoreCase(String nfcTagId);
    java.util.List<User> findAllByOrderByIdDesc();

    /**
     * Whether <b>anything at all</b> still points at this user — the question the delete guard
     * has to answer before it touches a thing.
     *
     * <h2>Why one query with a written-out list</h2>
     *
     * <p>Every foreign key into {@code users} is {@code ON DELETE RESTRICT} except the two the
     * service clears itself ({@code api_sessions}, {@code user_roles}), which is the right
     * design and the reason this system can never do what the user feared: deleting a person
     * cannot take their work with it, because the database refuses the delete instead.
     *
     * <p>But the database refusing is the <b>last</b> line, not the first. By the time it fires
     * the service has already expired that user's web sessions through Spring Security's
     * in-memory registry, which <b>no transaction rolls back</b> — so a refused delete logged a
     * working operator out of the panel, and the message they got was the generic «خطای
     * یکپارچگی ارجاعی» rather than "this user has worked here; deactivate them instead". That is
     * what this closes.
     *
     * <p>Listed by hand rather than generated from the catalogue so it can be read and audited,
     * and <b>kept honest by a test</b>: {@code UserDeleteReferenceIntegrityTest} reads every
     * RESTRICT foreign key to {@code users} out of {@code information_schema} and fails if this
     * query does not mention one. Add a column that references a user and that test tells you
     * to come here — which is the only way a list like this stays true.
     *
     * <p>One statement rather than fourteen {@code existsBy…} calls: it is a page in the admin
     * panel, every arm short-circuits in PostgreSQL, and fourteen round trips to answer one
     * yes/no is a cost paid on every delete attempt.
     */
    @Query(value = """
            SELECT
                   EXISTS (SELECT 1 FROM log_sheets            WHERE assignee_user_id            = :userId)
                OR EXISTS (SELECT 1 FROM log_sheets            WHERE assigned_by_user_id         = :userId)
                OR EXISTS (SELECT 1 FROM log_sheets            WHERE completed_by_user_id        = :userId)
                OR EXISTS (SELECT 1 FROM log_sheets            WHERE draft_saved_by_user_id      = :userId)
                OR EXISTS (SELECT 1 FROM log_sheets            WHERE approved_by_user_id         = :userId)
                OR EXISTS (SELECT 1 FROM log_sheets            WHERE sheet_form_filled_by_user_id = :userId)
                OR EXISTS (SELECT 1 FROM log_sheet_entries     WHERE filled_by_user_id           = :userId)
                OR EXISTS (SELECT 1 FROM log_sheet_entry_revisions WHERE recorded_by_user_id     = :userId)
                OR EXISTS (SELECT 1 FROM log_sheet_entry_revisions WHERE superseded_by_user_id   = :userId)
                OR EXISTS (SELECT 1 FROM log_sheet_revisions   WHERE recorded_by_user_id         = :userId)
                OR EXISTS (SELECT 1 FROM log_sheet_revisions   WHERE superseded_by_user_id       = :userId)
                OR EXISTS (SELECT 1 FROM log_sheet_action_log  WHERE actor_user_id               = :userId)
                OR EXISTS (SELECT 1 FROM log_sheet_action_log  WHERE from_user_id                = :userId)
                OR EXISTS (SELECT 1 FROM log_sheet_action_log  WHERE to_user_id                  = :userId)
                OR EXISTS (SELECT 1 FROM log_sheet_void_submissions WHERE submitted_by_user_id   = :userId)
                OR EXISTS (SELECT 1 FROM attachments           WHERE created_by_user_id          = :userId)
                OR EXISTS (SELECT 1 FROM nfc_fault_reports     WHERE reported_by_user_id         = :userId)
                OR EXISTS (SELECT 1 FROM nfc_fault_reports     WHERE reviewed_by_user_id         = :userId)
                OR EXISTS (SELECT 1 FROM asset_status_history  WHERE actor_user_id               = :userId)
                OR EXISTS (SELECT 1 FROM asset_activation_history WHERE actor_user_id            = :userId)
                OR EXISTS (SELECT 1 FROM asset_status_change_requests WHERE requested_by_user_id = :userId)
                OR EXISTS (SELECT 1 FROM asset_status_change_requests WHERE decided_by_user_id   = :userId)
                OR EXISTS (SELECT 1 FROM log_sheet_template_guides WHERE created_by_user_id      = :userId)
                OR EXISTS (SELECT 1 FROM audit_log             WHERE actor_user_id               = :userId)
                OR EXISTS (SELECT 1 FROM import_jobs           WHERE submitted_by_user_id        = :userId)
                OR EXISTS (SELECT 1 FROM api_keys              WHERE created_by_user_id          = :userId)
                OR EXISTS (SELECT 1 FROM api_keys              WHERE revoked_by                  = :userId)
                OR EXISTS (SELECT 1 FROM devices               WHERE code_issued_by              = :userId)
                OR EXISTS (SELECT 1 FROM devices               WHERE approved_by                 = :userId)
                OR EXISTS (SELECT 1 FROM devices               WHERE revoked_by                  = :userId)
            """, nativeQuery = true)
    boolean isReferencedAnywhere(@Param("userId") Long userId);
}
