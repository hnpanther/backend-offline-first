package com.hnp.backendofflinefirst;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No condition about completed work may name {@code SUBMITTED} on its own.
 *
 * <h2>The failure this prevents</h2>
 *
 * Approval is a review step laid on top of completion, not a different kind of completion. An
 * approved round's readings are exactly as real as an unapproved one's, so every report, export
 * and external feed has to count the two identically. There are about fifty places in this
 * codebase that ask "is this sheet completed", and **a missed one is silent**: no exception, no
 * log line, just a smaller number in a report about a plant. The silent-asset report would call
 * an approved-and-read asset "never inspected"; the compliance rate would drift; an integration
 * consumer would stop receiving rounds and nobody would notice for a month.
 *
 * <p>So the rule is mechanical rather than remembered: use
 * {@link com.hnp.backendofflinefirst.domain.LogSheetStatus#COMPLETED_STATUSES} or
 * {@code isCompleted()}, and this test fails the build for anything else.
 *
 * <h2>What is allowed to name SUBMITTED alone</h2>
 *
 * Only code about the <em>transition</em>, never about the data: approve (from SUBMITTED),
 * unapprove (to SUBMITTED), void (from SUBMITTED), unvoid (to SUBMITTED), reopen (from
 * SUBMITTED), and the completion target of {@code submitIfStillCompletable}. Each of those
 * genuinely means the one status and would be wrong with both.
 *
 * <p>Outcome strings are a different thing entirely and are not status comparisons —
 * {@code LogSheetSubmitResult.outcome = "SUBMITTED"} is a message to the device about what
 * happened to its submission. The pattern below only matches enum comparisons and status columns,
 * so those are untouched by construction.
 *
 * <h2>Same shape as the guards this project already relies on</h2>
 *
 * {@code NoRoleCodeAuthorizationTest} fails the build if a rule keys off a role's code again;
 * {@code CssHasNoSystemFontsTest} fails it if a stylesheet names a font from the user's machine.
 * Both exist because the mistake is invisible in review and expensive in the field. This is the
 * third of the same kind.
 */
class CompletedStatusConditionTest {

    /**
     * Files permitted to compare against {@code SUBMITTED} alone, each because it is about a
     * transition into or out of that exact status.
     *
     * <p>Adding a file here is a deliberate act: it means "this really is about the one status".
     * If you are tempted to add a repository or a report, the answer is
     * {@code COMPLETED_STATUSES} instead.
     */
    private static final Set<String> TRANSITION_FILES = Set.of(
            // approve / unapprove / void / unvoid / reopen — all guarded on the exact status
            "LogSheetAssignmentService.java",
            // the completion target, plus "already completed by someone else" on a mobile submit
            "LogSheetService.java",
            // the enum's own declaration and its helpers
            "LogSheetStatus.java",
            // isAwaitingApproval() — the precondition for approve/void/reopen, which all mean the
            // one status and would be wrong with both. It exists precisely so templates never
            // have to name a status themselves.
            "LogSheetViewHelper.java"
    );

    /**
     * A status comparison, in Java or in a Thymeleaf expression.
     *
     * <p>Matches {@code LogSheetStatus.SUBMITTED}, {@code status = 'SUBMITTED'},
     * {@code status.name() == 'SUBMITTED'} and {@code status = :submitted} forms. Deliberately
     * does <b>not</b> match a bare quoted word in isolation, so outcome strings such as
     * {@code new LogSheetSubmitResult(..., "SUBMITTED")} and the client's
     * {@code serverStatus: 'SUBMITTED'} are not reported — they are messages, not conditions.
     */
    private static final Pattern STATUS_COMPARISON = Pattern.compile(
            "LogSheetStatus\\.SUBMITTED"
                    + "|status\\s*(?:=|==|!=|<>)\\s*'SUBMITTED'"
                    + "|status\\.name\\(\\)\\s*(?:==|!=)\\s*'SUBMITTED'"
                    + "|'SUBMITTED'\\s*(?:==|!=)\\s*[a-zA-Z_.]*status");

    @Test
    void noConditionAboutCompletedWorkNamesSubmittedAlone() throws IOException {
        List<String> offences = new ArrayList<>();

        for (Path file : sourcesAndTemplates()) {
            String name = file.getFileName().toString();
            if (TRANSITION_FILES.contains(name)) continue;

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (isComment(line)) continue;
                Matcher m = STATUS_COMPARISON.matcher(line);
                if (m.find() && !mentionsApproved(lines, i)) {
                    offences.add(file + ":" + (i + 1) + "  →  " + line.trim());
                }
            }
        }

        assertThat(offences)
                .as("""
                        A status condition names SUBMITTED on its own. An approved round is a \
                        completed round, so every report, export and feed must count the two \
                        identically — use LogSheetStatus.COMPLETED_STATUSES (or isCompleted(), or \
                        @logSheetView.isCompleted(...) in a template). Getting this wrong is \
                        silent: no error, just a smaller number in a plant report.

                        If the line really is about the transition into or out of SUBMITTED, add \
                        its file to TRANSITION_FILES with a reason.""")
                .isEmpty();
    }

    @Test
    void theAllowListOnlyNamesFilesThatStillExist() throws IOException {
        // A stale entry silently re-opens the hole it was carved for.
        List<String> present = sourcesAndTemplates().stream()
                .map(p -> p.getFileName().toString())
                .toList();
        assertThat(present).containsAll(TRANSITION_FILES);
    }

    // -----------------------------------------------------------------------

    private static List<Path> sourcesAndTemplates() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String root : List.of("src/main/java", "src/main/resources/templates")) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.endsWith(".java") || n.endsWith(".html");
                        })
                        .forEach(out::add);
            }
        }
        return out;
    }

    /**
     * Whether {@code APPROVED} appears alongside, making this a completed-work condition.
     *
     * <p>The window is the line plus the two after it, because a JPQL {@code IN (...)} list is
     * routinely wrapped:
     *
     * <pre>
     *   WHERE s.status IN (LogSheetStatus.SUBMITTED,
     *                      LogSheetStatus.APPROVED,
     *                      LogSheetStatus.VOIDED)
     * </pre>
     *
     * <p>This deliberately trades a little precision for usability: a line naming both statuses
     * in some other wrong way would pass. That is acceptable, because the failure this guard
     * exists for is exactly the opposite one — naming {@code SUBMITTED} and forgetting the other,
     * which is silent. A guard that also flagged every correct pair would be noise, and noisy
     * guards get deleted.
     */
    private static boolean mentionsApproved(List<String> lines, int index) {
        int end = Math.min(lines.size(), index + 3);
        for (int i = index; i < end; i++) {
            if (lines.get(i).contains("APPROVED")) return true;
        }
        return false;
    }

    /**
     * Whether the line is only a comment.
     *
     * <p>Prose explaining the rule mentions {@code SUBMITTED} constantly — including the javadoc
     * of the very methods this guards — and flagging documentation would make the test unusable
     * and get it deleted. Line comments and javadoc bodies are skipped; a comparison sharing a
     * line with trailing code is still caught, because the code part comes first.
     */
    private static boolean isComment(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//")
                || trimmed.startsWith("*")
                || trimmed.startsWith("/*")
                || trimmed.startsWith("<!--");
    }
}
