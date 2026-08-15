package com.hnp.backendofflinefirst.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authorization must not be decided from a role's code.
 *
 * <h2>Why a source scan rather than a behavioural test</h2>
 * Every behavioural test here passes just as happily whether a rule reads a capability or a
 * role name — the seeded roles hold both. What a behavioural test cannot catch is the *next*
 * person adding {@code if (SecurityUtils.hasRole("SUPERVISOR"))} to a new service, which
 * compiles, passes, ships, and quietly makes one more thing un-copyable. The defect only
 * surfaces for someone who duplicated a role, months later.
 *
 * <p>So this scans the source. It is blunt on purpose: any reintroduction of a role-code check
 * fails the build with the file and line, and the fix is to add a capability instead.
 *
 * @see Capabilities
 */
class NoRoleCodeAuthorizationTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java/com/hnp/backendofflinefirst");

    /**
     * Files allowed to mention role codes at all.
     * <ul>
     *   <li>{@code SystemRoleCapabilities} — maps role → seeded capabilities, by definition.</li>
     *   <li>{@code AdminBootstrapRunner} — finds the ADMIN role to create the first user. That
     *       is provisioning, not authorization: it decides who to <em>create</em>, never what a
     *       request may do.</li>
     *   <li>{@code ExcelImportService} — parses "SUPERVISOR"/"OPERATOR" from the unit-staff
     *       import sheet into a {@code StaffRole}. Same words, different concept: a unit
     *       membership, not an application role.</li>
     * </ul>
     */
    private static final List<String> ROLE_CODE_ALLOWED = List.of(
            "SystemRoleCapabilities.java",
            "AdminBootstrapRunner.java",
            "ExcelImportService.java");

    /** Patterns that mean "this code is branching on who the user *is*, not what they may do". */
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\bisAdmin\\s*\\("),
            Pattern.compile("\\bhasRole\\s*\\("),
            Pattern.compile("\\bgetRoleCodes\\s*\\(\\)\\s*\\.contains\\s*\\("));

    @Test
    void noProductionCodeBranchesOnARoleCode() throws IOException {
        List<String> offences = scan(FORBIDDEN, false);

        assertThat(offences)
                .as("Authorization must read a capability, not a role code. Add one to "
                        + "Capabilities + the V3-style migration + SystemRoleCapabilities, then "
                        + "check it with SecurityUtils.hasCapability(). See docs/security.md.")
                .isEmpty();
    }

    @Test
    void noProductionCodeComparesAgainstASystemRoleName() throws IOException {
        List<Pattern> literals = SystemRoleCapabilities.systemRoles().stream()
                .map(role -> Pattern.compile("\"" + role + "\""))
                .toList();

        List<String> offences = scan(literals, true);

        assertThat(offences)
                .as("A hard-coded role name in an access decision is the defect this model "
                        + "removed: the duplicate of a role has a different name and would not match.")
                .isEmpty();
    }

    @Test
    void everyCapabilityConstantIsActuallyUsed() throws IOException {
        String allSources = readAll();
        List<String> unused = new ArrayList<>();
        for (String capability : Capabilities.ALL) {
            String constant = constantNameOf(capability);
            // Two references are the declaration and the ALL list; a third means a real call site.
            long references = allSources.lines()
                    .filter(line -> line.contains("Capabilities." + constant)).count();
            if (references == 0) {
                unused.add(capability);
            }
        }

        assertThat(unused)
                .as("A capability nothing reads is a permission an administrator can grant that "
                        + "does nothing — worse than no capability, because it looks like it works.")
                .isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<String> scan(List<Pattern> patterns, boolean roleNameScan) throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (roleNameScan && ROLE_CODE_ALLOWED.contains(name)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (isCommentOrJavadoc(line)) {
                        continue;
                    }
                    for (Pattern pattern : patterns) {
                        if (pattern.matcher(line).find()) {
                            offences.add(name + ":" + (i + 1) + "  " + line.trim());
                        }
                    }
                }
            }
        }
        return offences;
    }

    /**
     * Doc comments discuss the old model deliberately — {@code Capabilities} explains what
     * {@code isAdmin()} used to mean, and that explanation is the reason the class exists.
     * Only executable lines are scanned.
     */
    private static boolean isCommentOrJavadoc(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    private static String readAll() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return all.toString();
    }

    /** {@code CAP:SCOPE_PLANT_WIDE} → {@code SCOPE_PLANT_WIDE}. */
    private static String constantNameOf(String capabilityCode) {
        return capabilityCode.substring("CAP:".length());
    }
}
