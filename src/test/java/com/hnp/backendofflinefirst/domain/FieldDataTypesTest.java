package com.hnp.backendofflinefirst.domain;

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
 * The one list of field data types, and the copies of it that must not drift.
 *
 * <h2>Why a test and not just a constant</h2>
 *
 * <p>A constant stops the two <em>server</em> dropdowns drifting, and that is what caused the
 * reported bug. But the same list also exists in the PWA, as the {@code FieldDataType} union it
 * decodes bundles into — a separate repository, in a different language, that no compiler checks
 * against this one. That copy had drifted too: it stopped at {@code textarea} while real bundles
 * carried {@code image}, {@code audio}, {@code video} and {@code location}.
 *
 * <p>So this reads the PWA's union and compares. It is a soft link — the PWA lives at a path this
 * build does not own — so a missing checkout skips rather than fails, which keeps CI honest
 * without making the server build depend on the client being present.
 */
class FieldDataTypesTest {

    /** The companion PWA, as `CLAUDE.md` documents its location. */
    private static final Path PWA_SYNC_TYPES =
            Path.of("..", "..", "FrontEnd", "offline-first-pwa", "src", "types", "sync.ts");

    @Test
    void listsEveryTypeInDisplayOrder() {
        assertThat(FieldDataTypes.values()).containsExactly(
                "number", "text", "select", "multiselect", "checkbox",
                "textarea", "image", "audio", "video", "location");
    }

    @Test
    void labelsKeepTheSameOrderAsTheValues() {
        // `labels()` feeds `th:each` directly, so an unordered map would shuffle the dropdown on
        // every render. `Map.copyOf` does exactly that, which is why it is not used.
        assertThat(FieldDataTypes.labels().keySet()).containsExactlyElementsOf(FieldDataTypes.values());
    }

    @Test
    void everyTypeHasAPersianLabel() {
        assertThat(FieldDataTypes.labels().values()).noneMatch(l -> l == null || l.isBlank());
    }

    @Test
    void labelFallsBackToTheRawValueForATypeThisBuildDoesNotKnow() {
        // A row written by an older build must still render as something, not as "null".
        assertThat(FieldDataTypes.label("gauge")).isEqualTo("gauge");
        assertThat(FieldDataTypes.label("image")).isEqualTo(FieldDataTypes.labels().get("image"));
    }

    @Test
    void onlyKnownTypesMayBeWritten() {
        assertThat(FieldDataTypes.values()).allMatch(FieldDataTypes::isValid);
        assertThat(FieldDataTypes.isValid("hologram")).isFalse();
        assertThat(FieldDataTypes.isValid("")).isFalse();
        assertThat(FieldDataTypes.isValid(null)).isFalse();
        assertThat(FieldDataTypes.isValid("Image")).as("values are exact, not case-folded").isFalse();
    }

    // ────────────────────────────────────────────────────────────────────────
    // A type this build does not offer, on a field that already has it
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void aLegacyTypeIsAppendedToItsOwnFieldsDropdown() {
        // `schema.md` documented `boolean` and `date`, which were never offered by the editor.
        // Leaving such a field's type out of the list is exactly what caused the bug: the select
        // would submit its first option and the save would retype the field.
        assertThat(FieldDataTypes.labelsIncluding("date").keySet())
                .containsExactlyElementsOf(appended("date"));
        assertThat(FieldDataTypes.labelsIncluding("date").get("date"))
                .as("no Persian label exists for it, so it is labelled as itself")
                .isEqualTo("date");
    }

    @Test
    void aStandardTypeAddsNothingAndReusesTheSharedList() {
        assertThat(FieldDataTypes.labelsIncluding("image")).isSameAs(FieldDataTypes.labels());
        assertThat(FieldDataTypes.labelsIncluding(null)).isSameAs(FieldDataTypes.labels());
        assertThat(FieldDataTypes.labelsIncluding("  ")).isSameAs(FieldDataTypes.labels());
    }

    @Test
    void aLegacyTypeMayBeKeptButNeverIntroduced() {
        // Editing a `date` field must not be blocked …
        assertThat(FieldDataTypes.isValidFor("date", "date")).isTrue();
        // … but nothing may turn a normal field into one, or invent a type outright.
        assertThat(FieldDataTypes.isValidFor("date", "number")).isFalse();
        assertThat(FieldDataTypes.isValidFor("hologram", "number")).isFalse();
        assertThat(FieldDataTypes.isValidFor(null, null)).isFalse();
    }

    @Test
    void theCreateFormNeverOffersALegacyType() {
        // `labels()` is what the create modal iterates; it must stay the standard set.
        assertThat(FieldDataTypes.labels()).doesNotContainKey("date");
    }

    private static List<String> appended(String legacy) {
        List<String> expected = new ArrayList<>(FieldDataTypes.values());
        expected.add(legacy);
        return expected;
    }

    @Test
    void theMobileClientDecodesTheSameSet() throws IOException {
        if (!Files.exists(PWA_SYNC_TYPES)) {
            return; // PWA not checked out beside this repo — see the class comment.
        }
        String ts = Files.readString(PWA_SYNC_TYPES, StandardCharsets.UTF_8);

        assertThat(unionMembers(ts, "FieldDataType"))
                .as("the PWA's FieldDataType union must match this list exactly")
                .containsExactlyInAnyOrderElementsOf(FieldDataTypes.values());
    }

    /** The quoted members of {@code export type <name> = 'a' | 'b' | …}. */
    private static List<String> unionMembers(String ts, String name) {
        Matcher decl = Pattern.compile("export type " + name + "\\s*=(.*?)(?=\\n\\s*\\n)",
                Pattern.DOTALL).matcher(ts);
        assertThat(decl.find()).as("could not find `export type %s` in the PWA", name).isTrue();

        List<String> members = new ArrayList<>();
        Matcher m = Pattern.compile("'([^']+)'").matcher(decl.group(1));
        while (m.find()) {
            members.add(m.group(1));
        }
        assertThat(members).as("parsed no members out of %s", name).isNotEmpty();
        return members;
    }
}
