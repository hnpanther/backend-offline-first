package com.hnp.backendofflinefirst;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compiled migrations match the source migrations.
 *
 * <p>Flyway reads from the classpath — {@code target/classes/db/migration} — not from
 * {@code src}. A Maven build copies resources in but never removes ones that were deleted, so a
 * migration that has been renamed or folded into an earlier version **stays on the classpath**
 * until something clears it. The application then runs a file that no longer exists in the
 * repository.
 *
 * <p>This has now happened three times on this project, and the symptoms differed each time,
 * which is why it kept being rediscovered rather than recognised:
 *
 * <ul>
 *   <li>V4/V5 folded into V3 — the stale V4 re-ran and failed with
 *       {@code column "org_unit" of relation "users" already exists}.</li>
 *   <li>A later V4 folded into V3 — the stale V4 re-applied <b>silently</b> and put its row back
 *       into {@code flyway_schema_history} after it had been deleted by hand. Nothing failed.</li>
 *   <li>V3 renamed — both names sat side by side and Flyway refused to start at all:
 *       {@code Found more than one migration with version 3}.</li>
 * </ul>
 *
 * <p>The second one is the reason this test exists. A build that fails is a bad afternoon; a
 * migration that quietly re-applies itself is a database nobody can reason about.
 */
class MigrationFilesTest {

    private static final File SOURCE = new File("src/main/resources/db/migration");
    private static final File COMPILED = new File("target/classes/db/migration");
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Test
    void noStaleMigrationSurvivesOnTheClasspath() {
        Set<String> source = names(SOURCE);
        Set<String> compiled = names(COMPILED);

        // Guards the guard: if the compiled directory is missing the comparison proves nothing.
        assertThat(source).as("migrations under src").isNotEmpty();
        assertThat(compiled).as("migrations under target/classes — build first").isNotEmpty();

        Set<String> stale = new LinkedHashSet<>(compiled);
        stale.removeAll(source);
        assertThat(stale)
                .as("migrations on the classpath that no longer exist in src — delete them from "
                        + COMPILED + ", or Flyway will run a file the repository does not have")
                .isEmpty();
    }

    @Test
    void noTwoMigrationsShareAVersion() {
        // What "Found more than one migration with version 3" looks like before Flyway says it.
        Set<String> versions = new TreeSet<>();
        Set<String> duplicated = new TreeSet<>();
        for (String name : names(COMPILED)) {
            Matcher matcher = VERSIONED.matcher(name);
            if (matcher.matches() && !versions.add(matcher.group(1))) {
                duplicated.add(matcher.group(1));
            }
        }

        assertThat(duplicated).as("versions claimed by more than one migration file").isEmpty();
    }

    private Set<String> names(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".sql"));
        return files == null ? Set.of() : new TreeSet<>(Arrays.stream(files).map(File::getName).toList());
    }
}
