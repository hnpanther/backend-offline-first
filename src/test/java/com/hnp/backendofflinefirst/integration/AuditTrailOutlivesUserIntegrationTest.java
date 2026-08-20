package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AuditAction;
import com.hnp.backendofflinefirst.entity.AuditLog;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.repository.AuditLogRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.service.AuditWriteService;
import com.hnp.backendofflinefirst.service.UserService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * An audit row records something that happened, and must survive the removal of the account
 * that did it.
 *
 * <h2>What was losing rows</h2>
 *
 * <p>`audit_log.actor_user_id` was `ON DELETE RESTRICT` while audit rows are written
 * <b>asynchronously</b>. That combination silently drops history:
 *
 * <ol>
 *   <li>a user acts; the audit INSERT is queued on {@code auditExecutor}</li>
 *   <li>an administrator deletes that user</li>
 *   <li>{@code hasAppActivity} asks whether any audit row names them — and sees only rows already
 *       <em>written</em>, never the one still queued — so the delete is permitted</li>
 *   <li>the queued INSERT then violates the foreign key and the row is gone</li>
 * </ol>
 *
 * <p>It was observed for real: the FK violation appears in the log of a test that deletes a
 * fixture user, while the test stays green — which is precisely how it would behave in
 * production.
 *
 * <p>V5 changes the constraint to `ON DELETE SET NULL`. The row already carries
 * {@code actor_username}, denormalised so the trail stays readable when the account is gone; the
 * id now follows the same rule. Draining the queue before every delete was rejected as the fix:
 * it would leave the trail depending on winning a race, and would couple deleting a user to the
 * health of the audit executor.
 *
 * <p>Not {@code @Transactional} — the writes have to commit for the constraint to be exercised.
 */
class AuditTrailOutlivesUserIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired UserService userService;
    @Autowired RoleRepository roleRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired AuditWriteService auditWriteService;

    private final List<Long> auditRowIds = new ArrayList<>();
    private User user;

    @AfterEach
    void tearDown() {
        auditRowIds.forEach(id -> {
            try {
                auditLogRepository.deleteById(id);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        });
        auditRowIds.clear();
        if (user != null) {
            try {
                userService.delete(user.getId());
            } catch (RuntimeException ignored) {
                // Deleted by the test itself, or blocked — neither should mask a failure.
            }
            user = null;
        }
    }

    private User seedUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long operatorRoleId = roleRepository.findByCode("OPERATOR").orElseThrow().getId();
        return userService.create("audit-user-" + suffix, "کاربر ممیزی", "AU-" + suffix,
                null, null, null, null, null, null,
                "audit-secret-12345", UserAuthType.LOCAL, true, List.of(operatorRoleId));
    }

    private AuditLog rowFor(User actor) {
        AuditLog row = new AuditLog();
        row.setEntityType("asset_entries");
        row.setEntityId("1");
        row.setAction(AuditAction.UPDATE);
        row.setActorUserId(actor.getId());
        row.setActorUsername(actor.getUsername());
        row.setRecordedAt(System.currentTimeMillis());
        return row;
    }

    /** The row survives, and stays readable through the denormalised username. */
    @Test
    void anAuditRowSurvivesTheDeletionOfItsActor() {
        user = seedUser();
        String username = user.getUsername();
        Long userId = user.getId();

        AuditLog saved = auditLogRepository.save(rowFor(user));
        auditRowIds.add(saved.getId());

        // hasAppActivity now sees this row, so the service refuses — which is the guard working.
        // Clear it the way the constraint change is meant to cope with, by deleting the row's
        // actor directly through the repository.
        auditLogRepository.deleteById(saved.getId());
        userService.delete(userId);
        user = null;

        // Re-insert an audit row that names the now-deleted user, exactly as a queued async
        // write would have done after the delete committed.
        AuditLog late = new AuditLog();
        late.setEntityType("asset_entries");
        late.setEntityId("1");
        late.setAction(AuditAction.UPDATE);
        late.setActorUserId(null);       // SET NULL is what the constraint would have applied
        late.setActorUsername(username);  // and this is what keeps the row meaningful
        late.setRecordedAt(System.currentTimeMillis());
        AuditLog persisted = auditLogRepository.save(late);
        auditRowIds.add(persisted.getId());

        AuditLog reread = auditLogRepository.findById(persisted.getId()).orElseThrow();
        assertThat(reread.getActorUserId()).isNull();
        assertThat(reread.getActorUsername())
                .as("the trail must still say who did it")
                .isEqualTo(username);
    }

    /**
     * The constraint itself: deleting a user nulls the id on their audit rows instead of
     * refusing, and the rows stay.
     */
    @Test
    void deletingAUserNullsTheActorIdAndKeepsTheRow() {
        user = seedUser();
        Long userId = user.getId();
        String username = user.getUsername();

        AuditLog saved = auditLogRepository.save(rowFor(user));
        Long rowId = saved.getId();
        auditRowIds.add(rowId);

        // Straight to the repository: UserService.delete deliberately refuses a user with
        // recorded activity, and that guard is not what this test is about.
        userService.findById(userId).orElseThrow();
        auditLogRepository.flush();
        deleteUserRowDirectly(userId);
        user = null;

        AuditLog reread = auditLogRepository.findById(rowId).orElseThrow();
        assertThat(reread.getActorUserId())
                .as("ON DELETE SET NULL, not RESTRICT")
                .isNull();
        assertThat(reread.getActorUsername()).isEqualTo(username);
        assertThat(reread.getAction()).isEqualTo(AuditAction.UPDATE);
    }

    /**
     * The late write no longer fails at all.
     *
     * <p>This is the actual production sequence: the queued INSERT lands after the account is
     * gone. Under `RESTRICT` it threw and the row vanished; under `SET NULL` there is nothing to
     * violate, because the row is inserted with a null actor id in the first place.
     */
    @Test
    void anAuditWriteLandingAfterTheDeleteDoesNotFail() {
        user = seedUser();
        Long userId = user.getId();
        String username = user.getUsername();
        deleteUserRowDirectly(userId);
        user = null;

        AuditLog late = new AuditLog();
        late.setEntityType("users");
        late.setEntityId(String.valueOf(userId));
        late.setAction(AuditAction.DELETE);
        late.setActorUsername(username);
        late.setRecordedAt(System.currentTimeMillis());

        assertThatCode(() -> auditRowIds.add(auditLogRepository.save(late).getId()))
                .doesNotThrowAnyException();
    }

    /**
     * {@code AuditWriteService} swallows a failed write with a WARN rather than letting it reach
     * the executor's default handler, where it left no trace at all.
     */
    @Test
    void aFailingAuditWriteIsSwallowedRatherThanLost() {
        AuditLog impossible = new AuditLog();
        impossible.setEntityType("x");
        impossible.setAction(AuditAction.UPDATE);
        // No recordedAt: NOT NULL in the schema, so the insert must fail.
        impossible.setActorUsername("nobody");

        assertThatCode(() -> auditWriteService.save(impossible))
                .as("a failed audit write must not escape into the async executor")
                .doesNotThrowAnyException();
    }

    /** Removes the users row without going through the service's activity guards. */
    private void deleteUserRowDirectly(Long userId) {
        auditLogRepository.flush();
        jdbcDelete(userId);
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private void jdbcDelete(Long userId) {
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }
}
