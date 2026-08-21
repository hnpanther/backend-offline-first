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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An audit row records something that happened, and must survive the removal of the account
 * that did it.
 *
 * <h2>What was losing rows</h2>
 *
 * <p>{@code audit_log.actor_user_id} was {@code ON DELETE RESTRICT} while audit rows are written
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
 * <p>Measured, not theorised: a full suite run logged 42 {@code Audit row lost} warnings, all of
 * them {@code violates foreign key constraint "fk_audit_log_actor_user"}, while every test stayed
 * green — {@code AuditWriteService} catches the failure and warns. That is exactly how it behaves
 * in production.
 *
 * <h2>Why the constraint is gone rather than {@code ON DELETE SET NULL}</h2>
 *
 * <p>SET NULL was the first attempt and it does not work. A referential action fires when the
 * parent row is deleted and rewrites the children that exist <em>at that moment</em>. The rows
 * this is about do not exist yet — they arrive afterwards, naming an id that no longer resolves,
 * so there is nothing for SET NULL to act on and the late INSERT fails exactly as under RESTRICT.
 * SET NULL only helps when the audit row was already written, which is the one case the delete
 * guard already refuses.
 *
 * <p>So V3 drops the constraint. {@code actor_user_id} is a statement about the past — "this
 * account did this, then" — which stays true after the account is gone, the same reason
 * {@code actor_username} is denormalised beside it.
 *
 * <p>Not {@code @Transactional}: the writes have to commit for the constraint to be exercised at
 * all. Each test cleans up after itself in {@link #tearDown()}.
 */
class AuditTrailOutlivesUserIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired UserService userService;
    @Autowired RoleRepository roleRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired AuditWriteService auditWriteService;
    @Autowired JdbcTemplate jdbcTemplate;

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
            deleteUserRowDirectly(user.getId());
            user = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The production sequence, through the real async path
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The test that would have caught the original bug.
     *
     * <p>It drives {@link AuditWriteService#save} — the actual {@code @Async} +
     * {@code REQUIRES_NEW} path — with a row naming a user who has already been deleted, which is
     * precisely what a queued write does when the delete beats it. Under the foreign key the
     * insert failed, {@code AuditWriteService} warned, and the row was lost; the assertion below
     * is that it is now simply written.
     *
     * <p>Polling rather than asserting immediately: the write is on another thread by design.
     */
    @Test
    void aQueuedAuditWriteLandingAfterTheDeleteIsPersisted() throws Exception {
        user = seedUser();
        Long deletedUserId = user.getId();
        String username = user.getUsername();
        deleteUserRowDirectly(deletedUserId);
        user = null;

        String entityId = "late-" + UUID.randomUUID();
        AuditLog queued = rowNaming(deletedUserId, username);
        queued.setEntityId(entityId);

        auditWriteService.save(queued);

        AuditLog written = pollForRow(entityId);
        assertThat(written)
                .as("the queued row must be written, not warned about and dropped")
                .isNotNull();
        auditRowIds.add(written.getId());

        assertThat(written.getActorUserId())
                .as("the id is a historical fact and is kept, not nulled")
                .isEqualTo(deletedUserId);
        assertThat(written.getActorUsername())
                .as("and the trail still says who did it")
                .isEqualTo(username);
    }

    /**
     * The same insert at the persistence layer, so that a failure points at the constraint rather
     * than at the executor.
     *
     * <p>This is the assertion that fails the moment somebody re-adds the foreign key — whether
     * as RESTRICT or as SET NULL, since neither permits an INSERT naming a row that is gone.
     */
    @Test
    void anAuditRowMayNameAUserWhoNoLongerExists() {
        user = seedUser();
        Long deletedUserId = user.getId();
        String username = user.getUsername();
        deleteUserRowDirectly(deletedUserId);
        user = null;

        assertThatCode(() -> {
            AuditLog late = rowNaming(deletedUserId, username);
            auditRowIds.add(auditLogRepository.saveAndFlush(late).getId());
        }).as("no foreign key may stand between a deleted account and its audit trail")
          .doesNotThrowAnyException();

        AuditLog reread = auditLogRepository.findById(auditRowIds.getLast()).orElseThrow();
        assertThat(reread.getActorUserId()).isEqualTo(deletedUserId);
        assertThat(reread.getActorUsername()).isEqualTo(username);
    }

    /**
     * Rows already written are untouched by the delete — the id survives it.
     *
     * <p>Under the superseded SET NULL form this assertion read {@code isNull()}. Keeping the id
     * is the better answer: it is what the row observed, and losing it would discard the only
     * link back to the account for no gain, since nothing dereferences it.
     */
    @Test
    void deletingAUserLeavesTheirExistingAuditRowsIntact() {
        user = seedUser();
        Long userId = user.getId();
        String username = user.getUsername();

        AuditLog saved = auditLogRepository.saveAndFlush(rowNaming(userId, username));
        Long rowId = saved.getId();
        auditRowIds.add(rowId);

        // Straight to the table: UserService.delete deliberately refuses a user with recorded
        // activity, and that guard is the subject of its own test below.
        deleteUserRowDirectly(userId);
        user = null;

        AuditLog reread = auditLogRepository.findById(rowId).orElseThrow();
        assertThat(reread.getActorUserId())
                .as("no referential action rewrote it, because there is no constraint to act")
                .isEqualTo(userId);
        assertThat(reread.getActorUsername()).isEqualTo(username);
        assertThat(reread.getAction()).isEqualTo(AuditAction.UPDATE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // What dropping the constraint must NOT have weakened
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The delete guard is unaffected.
     *
     * <p>The foreign key was never what stopped a user with history from being deleted —
     * {@code UserService.hasAppActivity} is, via {@code existsByActorUserId}, and that is an
     * ordinary query that does not need a constraint behind it. Dropping the key must not turn a
     * refusal into a silent deletion.
     */
    @Test
    void aUserWithAuditHistoryStillCannotBeDeleted() {
        user = seedUser();
        Long userId = user.getId();

        auditRowIds.add(
                auditLogRepository.saveAndFlush(rowNaming(userId, user.getUsername())).getId());

        assertThat(auditLogRepository.existsByActorUserId(userId))
                .as("the guard's query still finds the row without a foreign key")
                .isTrue();
        assertThatThrownBy(() -> userService.delete(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be deleted");

        assertThat(userService.findById(userId)).as("still there").isPresent();
    }

    /** A background job has no user behind it, so the column stays nullable. */
    @Test
    void anAuditRowWithNoActorAtAllIsStillValid() {
        AuditLog systemRow = rowNaming(null, null);
        assertThatCode(() -> auditRowIds.add(auditLogRepository.saveAndFlush(systemRow).getId()))
                .doesNotThrowAnyException();

        assertThat(auditLogRepository.findById(auditRowIds.getLast()).orElseThrow().getActorUserId())
                .isNull();
    }

    /**
     * The constraint is gone from the live schema, stated directly.
     *
     * <p>The behavioural tests above cover the consequences; this one names the cause, so that a
     * later migration re-adding the key fails with a message that says what it broke instead of
     * leaving somebody to work backwards from a lost row.
     */
    @Test
    void theForeignKeyOnTheActorIsAbsentFromTheSchema() {
        List<String> constraints = jdbcTemplate.queryForList("""
                SELECT con.conname
                  FROM pg_constraint con
                  JOIN pg_class rel ON rel.oid = con.conrelid
                 WHERE rel.relname = 'audit_log'
                   AND con.contype = 'f'
                """, String.class);

        assertThat(constraints)
                .as("""
                    audit_log.actor_user_id must have NO foreign key. Audit rows are written \
                    asynchronously and can land after the account is deleted; any foreign key — \
                    RESTRICT or SET NULL — makes that INSERT fail, and AuditWriteService swallows \
                    the failure, so the row disappears with only a WARN. See V3 section 5.""")
                .isEmpty();
    }

    /**
     * {@code AuditWriteService} still swallows a genuinely failed write with a WARN rather than
     * letting it reach the executor's default handler, where it left no trace at all.
     *
     * <p>Worth keeping precisely because the fix above removes the failure that used to exercise
     * this path: the containment must stay proven by something.
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

    // ─────────────────────────────────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────────────────────────────────

    private User seedUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long operatorRoleId = roleRepository.findByCode("OPERATOR").orElseThrow().getId();
        return userService.create("audit-user-" + suffix, "کاربر ممیزی", "AU-" + suffix,
                null, null, null, null, null, null,
                "audit-secret-12345", UserAuthType.LOCAL, true, List.of(operatorRoleId));
    }

    private AuditLog rowNaming(Long actorId, String actorUsername) {
        AuditLog row = new AuditLog();
        row.setEntityType("asset_entries");
        row.setEntityId("1");
        row.setAction(AuditAction.UPDATE);
        row.setActorUserId(actorId);
        row.setActorUsername(actorUsername);
        row.setRecordedAt(System.currentTimeMillis());
        return row;
    }

    /** Removes the users row without going through the service's activity guards. */
    private void deleteUserRowDirectly(Long userId) {
        auditLogRepository.flush();
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    private AuditLog pollForRow(String entityId) throws Exception {
        return pollUntil(10_000, () -> auditLogRepository
                .findByEntityTypeAndEntityIdOrderByRecordedAtDesc("asset_entries", entityId)
                .stream().findFirst().orElse(null));
    }

    private static <T> T pollUntil(long timeoutMs, Callable<T> probe) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            T value = probe.call();
            if (value != null) {
                return value;
            }
            Thread.sleep(50);
        }
        return probe.call();
    }
}
