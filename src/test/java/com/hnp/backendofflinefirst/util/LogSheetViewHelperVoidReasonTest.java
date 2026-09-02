package com.hnp.backendofflinefirst.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every reason a submission can be voided for has a Persian rendering.
 *
 * <h2>The drift this exists to catch</h2>
 *
 * <p>The sentence lives in two places and cannot be reduced to one. {@code LogSheetService} writes
 * it into {@code log_sheet_void_submissions.reason} <em>and</em> hands the same string to the
 * tablet as the submission's error — so it is a stored record and part of the mobile contract, and
 * localising it at the source would change both. {@code LogSheetViewHelper.voidReasonLabel}
 * therefore maps it for display only.
 *
 * <p>Two copies of a string is a drift waiting to happen, and this one drifts <b>silently</b>:
 * reword the sentence in the service and the mapping simply stops matching, the {@code default}
 * branch returns the English, and the page goes back to showing an operator's supervisor a
 * sentence they cannot read. Nothing fails, nothing logs.
 *
 * <p>So the test reads the service's own source and requires a translation for every reason it
 * finds there — the same shape as {@code FormPatternAttributeTest} scanning templates and
 * {@code FieldDataTypesTest} parsing the PWA's union.
 */
class LogSheetViewHelperVoidReasonTest {

    private static final Path SERVICE = Path.of(
            "src/main/java/com/hnp/backendofflinefirst/service/LogSheetService.java");

    private final LogSheetViewHelper helper = new LogSheetViewHelper();

    @Test
    void everyReasonTheServiceCanWriteHasAPersianRendering() throws IOException {
        List<String> reasons = reasonsPassedToVoidSubmission();

        assertThat(reasons)
                .as("the scan found no reasons at all — the call shape in LogSheetService changed "
                        + "and this test is now guarding nothing, which is worse than failing")
                .isNotEmpty();

        for (String reason : reasons) {
            assertThat(helper.voidReasonLabel(reason))
                    .as("no Persian rendering for a reason the service writes: \"%s\". Add it to "
                            + "LogSheetViewHelper.voidReasonLabel — the page shows this to a "
                            + "supervisor, and untranslated it is English on a Persian screen.",
                            reason)
                    .isNotEqualTo(reason);
        }
    }

    @Test
    void aReasonNobodyHasTranslatedIsShownAsItStands() {
        // Deliberately not "unknown". A voided submission is the record of somebody's lost work,
        // and an untranslated reason is still the truth about why — swallowing it would leave the
        // reader with less than they had.
        assertThat(helper.voidReasonLabel("Something nobody has mapped yet."))
                .isEqualTo("Something nobody has mapped yet.");
        assertThat(helper.voidReasonWasTranslated("Something nobody has mapped yet.")).isFalse();
    }

    @Test
    void aTranslatedReasonReportsThatItWasTranslated() {
        // The page prints the original underneath only when this is true; without the check it
        // would print the same sentence twice on an untranslated one.
        String known = "This log sheet was cancelled.";
        assertThat(helper.voidReasonLabel(known)).isNotEqualTo(known);
        assertThat(helper.voidReasonWasTranslated(known)).isTrue();
    }

    @Test
    void anAbsentReasonIsADashRatherThanAnEmptyBanner() {
        assertThat(helper.voidReasonLabel(null)).isEqualTo("—");
        assertThat(helper.voidReasonLabel("   ")).isEqualTo("—");
        assertThat(helper.voidReasonWasTranslated(null)).isFalse();
        assertThat(helper.voidReasonWasTranslated("   ")).isFalse();
    }

    /**
     * The reason string of every {@code voidSubmission(...)} call in the service.
     *
     * <p>Read from the source rather than from a list kept here, because a list kept here is the
     * second copy this test exists to make unnecessary.
     *
     * <p>A call passes the reason and sometimes an outcome — {@code "CANCELLED"},
     * {@code "EXPIRED"} — so the literals are filtered to the ones that are actually sentences.
     * The service's other {@code "This log sheet …"} strings are exception messages and are
     * deliberately out of scope: they never reach this page.
     */
    private static List<String> reasonsPassedToVoidSubmission() throws IOException {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);
        List<String> reasons = new ArrayList<>();

        Matcher call = Pattern.compile("voidSubmission\\(").matcher(source);
        while (call.find()) {
            int open = call.end() - 1;
            int close = matchingParen(source, open);
            if (close < 0) continue;
            Matcher literal = Pattern.compile("\"([^\"\\\\]*)\"")
                    .matcher(source.substring(open, close));
            while (literal.find()) {
                String value = literal.group(1);
                // A sentence, not an outcome code.
                if (value.contains(" ") && value.endsWith(".") && !reasons.contains(value)) {
                    reasons.add(value);
                }
            }
        }
        return reasons;
    }

    /** Index of the parenthesis closing the one at {@code open}, or -1. */
    private static int matchingParen(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }
}
