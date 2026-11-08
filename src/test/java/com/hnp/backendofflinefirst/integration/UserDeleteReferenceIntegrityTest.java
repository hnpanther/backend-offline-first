package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting a person must never delete their work — and the guard that says so must know about
 * <strong>every</strong> place their work is recorded.
 *
 * <h2>The failure this exists to prevent</h2>
 *
 * <p>In another system, deleting a user deleted everything that user had entered. That cannot
 * happen here, and it is worth being precise about why: every foreign key into {@code users} is
 * {@code ON DELETE RESTRICT}, except two join rows the service clears itself
 * ({@code api_sessions}, {@code user_roles}) which are membership, not authorship. So the
 * database refuses the delete rather than following it. {@link #everyForeignKeyToUsersRefusesTheDelete}
 * asserts exactly that, from the catalogue, so a future migration cannot quietly add a
 * {@code CASCADE} and turn a deletion into a data loss.
 *
 * <h2>And the failure that was actually present</h2>
 *
 * <p>The database refusing is the last line, not the first. {@code UserService.delete} checks
 * for activity itself so it can say something useful — but it checked nine of the referencing
 * columns and there are twenty-seven. An operator who had only ever filled readings passed that
 * guard; the method then expired their web sessions through Spring Security's in-memory
 * registry, which <b>no transaction rolls back</b>, and the delete failed on the FK a moment
 * later. The operator was logged out of the panel by an action that was refused, and the
 * message blamed referential integrity instead of saying "deactivate them instead".
 *
 * <p>{@link #theDeleteGuardKnowsAboutEveryReferencingColumn} is what stops that list drifting
 * again: it reads the columns out of {@code information_schema} rather than from a copy, so
 * adding a column that references a user fails this test until the guard learns about it.
 */
@Transactional
class UserDeleteReferenceIntegrityTest extends AbstractPostgresIntegrationTest {

    @Autowired EntityManager entityManager;
    @Autowired UserRepository userRepository;

    /**
     * Join rows that are membership rather than authorship, and are deleted deliberately.
     *
     * <p>{@code api_sessions} cascades: a session is meaningless once its user is gone, and
     * nobody will ever ask who was logged in on a deleted account. {@code user_roles} is
     * RESTRICT and {@code UserService.delete} clears it itself, just before the row goes.
     */
    private static final Set<String> DELETED_WITH_THE_USER = Set.of(
            "api_sessions.user_id",
            "user_roles.user_id");

    /**
     * Columns the delete guard reaches through a <b>different</b> check, with a better message.
     *
     * <p>Unit membership is refused one step earlier — «این کاربر به واحدهای عملیاتی منتسب است»
     * — because the fix is different: an administrator removes them from the unit, which is not
     * the same advice as "deactivate instead". Folding these into the activity query would
     * replace a precise message with a vaguer one.
     */
    private static final Set<String> CHECKED_BY_ITS_OWN_GUARD = Set.of(
            "unit_supervisors.user_id",
            "unit_operators.user_id");

    @Test
    void everyForeignKeyToUsersRefusesTheDelete() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT src.relname || '.' || att.attname, con.confdeltype
                  FROM pg_constraint con
                  JOIN pg_class src  ON src.oid = con.conrelid
                  JOIN pg_class dst  ON dst.oid = con.confrelid
                  JOIN unnest(con.conkey) AS k(attnum) ON TRUE
                  JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
                 WHERE con.contype = 'f'
                   AND dst.relname = 'users'
                """).getResultList();

        assertThat(rows).as("the catalogue must know about the user foreign keys").isNotEmpty();

        // 'a' = NO ACTION, 'r' = RESTRICT. Both refuse. 'c' = CASCADE, 'n' = SET NULL,
        // 'd' = SET DEFAULT — each of those would let a delete reach through to the work.
        Set<String> followTheDelete = rows.stream()
                .filter(r -> !"a".equals(String.valueOf(r[1])) && !"r".equals(String.valueOf(r[1])))
                .map(r -> String.valueOf(r[0]))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(followTheDelete)
                .as("a foreign key to users that does NOT refuse the delete — deleting a person "
                        + "would take this with them. If that is genuinely intended, add it to "
                        + "DELETED_WITH_THE_USER with the reason.")
                .isSubsetOf(DELETED_WITH_THE_USER);
    }

    @Test
    void theDeleteGuardKnowsAboutEveryReferencingColumn() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT src.relname, att.attname
                  FROM pg_constraint con
                  JOIN pg_class src  ON src.oid = con.conrelid
                  JOIN pg_class dst  ON dst.oid = con.confrelid
                  JOIN unnest(con.conkey) AS k(attnum) ON TRUE
                  JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
                 WHERE con.contype = 'f'
                   AND dst.relname = 'users'
                   AND con.confdeltype IN ('a', 'r')
                """).getResultList();

        // The guard's own text, read as data. Asking the query what it covers — rather than
        // keeping a second list here — is what makes this test impossible to satisfy by
        // editing the wrong file.
        String guard = guardQuerySource();

        Set<String> missing = new TreeSet<>();
        for (Object[] row : rows) {
            String table = String.valueOf(row[0]);
            String column = String.valueOf(row[1]);
            String ref = table + "." + column;
            if (DELETED_WITH_THE_USER.contains(ref) || CHECKED_BY_ITS_OWN_GUARD.contains(ref)) {
                continue;
            }
            if (!mentions(guard, table, column)) {
                missing.add(table + "." + column);
            }
        }

        assertThat(missing)
                .as("UserRepository.isReferencedAnywhere does not look at these, so a user whose "
                        + "only work is recorded there passes the delete guard, gets logged out "
                        + "of the panel by a change that is NOT rolled back, and then sees the "
                        + "generic referential-integrity error instead of «deactivate instead»")
                .isEmpty();
    }

    @Test
    void aUserWhoOnlyAnsweredTheRoundsOwnQuestionsCountsAsHavingWorkedHere() {
        // The column V9 added, and the one the old nine-check list would have missed: a round's
        // own answers carry their own authorship precisely because `draft_saved_by_user_id`
        // moves on every per-asset save.
        User user = new User();
        user.setUsername("ref-check-" + System.nanoTime());
        user.setPersonnelCode("RC-" + System.nanoTime());
        user.setFullName("Reference Check");
        user.setPasswordHash("x");
        user.setActive(true);
        user.setCreatedAt(System.currentTimeMillis());
        user.setUpdatedAt(System.currentTimeMillis());
        user = userRepository.saveAndFlush(user);

        assertThat(userRepository.isReferencedAnywhere(user.getId()))
                .as("a brand-new user has done nothing")
                .isFalse();

        entityManager.createNativeQuery("""
                INSERT INTO log_sheets (template_name, status, origin, scan_mode,
                                        sheet_form_filled_by_user_id, created_at, updated_at)
                VALUES ('ref check', 'PENDING', 'MANUAL', 'NFC', :userId, :now, :now)
                """)
                .setParameter("userId", user.getId())
                .setParameter("now", System.currentTimeMillis())
                .executeUpdate();

        assertThat(userRepository.isReferencedAnywhere(user.getId()))
                .as("they answered a round's questions — that is work, and the delete must refuse")
                .isTrue();
    }

    /**
     * The body of {@code isReferencedAnywhere}, read off the compiled class's source.
     *
     * <p>Read from the file rather than mirrored here on purpose: a copy in the test is a second
     * list to keep in step, which is the exact failure mode being tested for.
     */
    private static String guardQuerySource() {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/hnp/backendofflinefirst/repository/UserRepository.java"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("UserRepository.java must be readable from the module root", e);
        }
    }

    /** Whether the guard queries that table on that column, allowing for the aligned spacing. */
    private static boolean mentions(String guard, String table, String column) {
        int from = 0;
        while (true) {
            int at = guard.indexOf("FROM " + table + " ", from);
            if (at < 0) {
                return false;
            }
            int lineEnd = guard.indexOf('\n', at);
            String line = guard.substring(at, lineEnd < 0 ? guard.length() : lineEnd);
            if (line.contains(column + " ") || line.contains(column + "=")) {
                return true;
            }
            from = at + 1;
        }
    }
}
