package com.hnp.backendofflinefirst;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No production code may ask the operating system what time zone it is in.
 *
 * <p>This exists because one line did, and nothing caught it: «داشبورد مدیریتی»'s twelve-month
 * trend called {@code managementReportService.monthlyTrend(ZoneId.systemDefault())} while every
 * other part of the system pinned {@code Asia/Tehran}. On a UTC host — the default for a container
 * and for most Linux installs — that moved every month boundary three and a half hours earlier:
 * a round raised at 02:00 Tehran on the first of a month was counted in the previous month, and
 * for the first three and a half hours of each month the trend did not show the current month at
 * all. The page rendered, no error was logged, and the numbers quietly disagreed with the
 * compliance report beside them.
 *
 * <p>A reviewer cannot catch that in a diff — {@code ZoneId.systemDefault()} reads like a
 * reasonable default — so the build catches it instead. The same applies to the second test: the
 * zone is named in exactly one place, because four constants that happen to agree are three
 * chances to disagree later.
 *
 * <p><b>What this test deliberately does not ban:</b> a bare {@code LocalDate.now()}, which also
 * reads the host's zone. There is one, in {@code AttachmentStorageService}, and it names a storage
 * directory rather than a number anybody reads: a photo landing in yesterday's folder is
 * invisible to every caller, because the row in the database is what resolves the file. Banning it
 * would mean changing a path shape for no benefit. If a {@code now()} without a zone ever decides
 * something a person sees, add it here.
 *
 * <p>Gotcha #116 applies: this guard is only as wide as the files it opens, so it walks the whole
 * of {@code src/main/java} rather than a list of the files that happen to handle dates today.
 */
class PlantZoneIsPinnedTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** Both spellings of "whatever zone this machine is set to". */
    private static final List<String> HOST_CLOCK =
            List.of("ZoneId.systemDefault()", "TimeZone.getDefault()");

    private static final String ZONE_LITERAL = "ZoneId.of(\"Asia/Tehran\")";

    private static final Path THE_ONE_PLACE =
            MAIN.resolve("com/hnp/backendofflinefirst/util/DateUtils.java");

    @Test
    void noProductionCodeAsksTheHostWhichZoneItIsIn() throws IOException {
        List<String> offences = new ArrayList<>();

        for (Path file : javaFiles()) {
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            for (String banned : HOST_CLOCK) {
                if (code.contains(banned)) {
                    offences.add(file + " → " + banned);
                }
            }
        }

        assertThat(offences)
                .as("""
                    Production code read the host's time zone. Use DateUtils.TEHRAN — the plant's \
                    zone is a property of the plant, not of the machine the service happens to run \
                    on, and a container defaults to UTC. If a caller genuinely chooses the zone, \
                    take it as a parameter (see app.integration.default-zone).""")
                .isEmpty();
    }

    @Test
    void theZoneIsNamedInExactlyOnePlace() throws IOException {
        List<String> declarations = new ArrayList<>();

        for (Path file : javaFiles()) {
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (code.contains(ZONE_LITERAL)) {
                declarations.add(file.toString());
            }
        }

        assertThat(declarations)
                .as("Asia/Tehran belongs to DateUtils.TEHRAN and nowhere else; read that constant "
                        + "instead of repeating the literal")
                .containsExactly(THE_ONE_PLACE.toString());
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }

    /**
     * Drops block and line comments, so the javadoc that explains this rule — which has to name
     * the thing it bans — does not trip it.
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }
}
