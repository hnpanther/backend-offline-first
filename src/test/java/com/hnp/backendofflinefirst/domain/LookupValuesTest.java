package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shape of a lookup answer (roadmap §31 Phase 2) — what a client may send, what is refused as
 * malformed, and what is no answer at all. The resolution against the list is
 * {@code LookupIntegrationTest}'s business; this pins the part that never touches a database.
 */
class LookupValuesTest {

    @Test
    @DisplayName("a bare code, a number, an object and a list are all choices")
    void acceptedShapes() {
        assertThat(LookupValues.parseIncoming("1234"))
                .containsExactly(new LookupValues.Choice("1234", null));
        assertThat(LookupValues.parseIncoming(1234))
                .as("a personnel code typed on a numeric keypad")
                .containsExactly(new LookupValues.Choice("1234", null));
        assertThat(LookupValues.parseIncoming(Map.of("code", "A7", "label", "علی")))
                .containsExactly(new LookupValues.Choice("A7", "علی"));
        assertThat(LookupValues.parseIncoming(List.of("A7", Map.of("code", "B9"))))
                .extracting(LookupValues.Choice::code).containsExactly("A7", "B9");
    }

    @Test
    @DisplayName("Persian digits become Latin, so «۱۲۳۴» finds item 1234")
    void persianDigits() {
        assertThat(LookupValues.parseIncoming("۱۲۳۴")).extracting(LookupValues.Choice::code).containsExactly("1234");
        assertThat(LookupValues.parseIncoming("  12  ")).extracting(LookupValues.Choice::code).containsExactly("12");
    }

    @Test
    @DisplayName("the same code twice is one choice, case-insensitively, the first kept")
    void duplicates() {
        assertThat(LookupValues.parseIncoming(List.of("ab", "AB", Map.of("code", "ab"))))
                .extracting(LookupValues.Choice::code).containsExactly("ab");
    }

    @Test
    @DisplayName("nothing sent is no answer, not a malformed one")
    void blanks() {
        assertThat(LookupValues.parseIncoming(null)).isEmpty();
        assertThat(LookupValues.parseIncoming("   ")).isEmpty();
        assertThat(LookupValues.parseIncoming(List.of())).isEmpty();
        assertThat(LookupValues.parseIncoming(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("what no client following the contract can send is unreadable — refused, never guessed")
    void malformed() {
        assertThat(LookupValues.parseIncoming(Map.of("label", "free text"))).as("no code").isNull();
        assertThat(LookupValues.parseIncoming("two words")).as("a space cannot be in a code").isNull();
        assertThat(LookupValues.parseIncoming("x".repeat(101))).as("longer than any code").isNull();
        assertThat(LookupValues.parseIncoming(true)).isNull();
        assertThat(LookupValues.parseIncoming(Map.of("code", "A", "label", "a\nb"))).as("control char").isNull();
        assertThat(LookupValues.parseIncoming(List.of("ok", List.of("nested")))).isNull();
    }

    @Test
    @DisplayName("the server's verdict is shown beside the label, never instead of it")
    void display() {
        assertThat(LookupValues.displayOf(LookupValues.element("12", "علی", LookupValues.State.ACTIVE)))
                .isEqualTo("علی (12)");
        assertThat(LookupValues.displayOf(LookupValues.element("12", "علی", LookupValues.State.INACTIVE)))
                .isEqualTo("علی (12) · غیرفعال");
        assertThat(LookupValues.displayOf(LookupValues.element("99", null, LookupValues.State.UNKNOWN)))
                .isEqualTo("99 · خارج از فهرست");
        assertThat(LookupValues.displayOfValue(List.of(
                LookupValues.element("1", "الف", LookupValues.State.ACTIVE),
                LookupValues.element("2", "ب", LookupValues.State.ACTIVE)))).isEqualTo("الف (1)، ب (2)");
        assertThat(LookupValues.displayOfValue(null)).isNull();
    }

    @Test
    @DisplayName("validation refuses only the shape: two choices on a single field, a list over fifty")
    void validation() {
        FieldDefinition single = field("person", LookupValues.TYPE_SINGLE);
        FieldDefinition multi = field("crew", LookupValues.TYPE_MULTI);

        assertThat(messages(Map.of("person", List.of("1", "2")), single)).containsExactly("takes one choice only");
        assertThat(messages(Map.of("person", Map.of("label", "x")), single))
                .containsExactly("is not a valid choice from its list");
        assertThat(messages(Map.of("person", "anything-not-in-the-list"), single))
                .as("an unknown code is flagged when stored, never refused").isEmpty();
        assertThat(messages(Map.of("crew", java.util.stream.IntStream.range(0, 51).mapToObj(String::valueOf).toList()), multi))
                .containsExactly("has too many choices");
        assertThat(messages(Map.of("crew", List.of("1", "2")), multi)).isEmpty();
    }

    @Test
    @DisplayName("a required lookup is satisfied by a code and not by an empty list")
    void required() {
        FieldDefinition person = field("person", LookupValues.TYPE_SINGLE);
        person.setRequired(true);
        assertThat(messages(Map.of("person", List.of()), person)).containsExactly("required field is missing");
        assertThat(messages(Map.of("person", Map.of("code", "1")), person)).isEmpty();
    }

    @Test
    @DisplayName("the field editor stores the list, and refuses a lookup with none")
    void build() {
        Map<String, Object> v = FieldValidationSupport.build(LookupValues.TYPE_SINGLE, null, 1.0, 2.0, null, null,
                null, null, null, true, null,
                new FieldValidationSupport.FieldBehaviour(false, null, null, "personnel"));
        assertThat(v).containsOnlyKeys(FieldValidationSupport.KEY_LOOKUP_LIST);
        assertThat(FieldValidationSupport.lookupList(v)).isEqualTo("personnel");

        assertThatThrownBy(() -> FieldValidationSupport.build(LookupValues.TYPE_MULTI, null, null, null, null, null,
                null, null, null, true, null, FieldValidationSupport.FieldBehaviour.DEFAULT))
                .hasMessage("A lookup field needs a reference list.");
        // Any other type ignores a list that a hidden control still posted.
        Map<String, Object> text = FieldValidationSupport.build("text", null, null, null, null, null,
                null, null, null, true, null,
                new FieldValidationSupport.FieldBehaviour(false, null, null, "personnel"));
        assertThat(text).isNull();
    }

    @Test
    @DisplayName("«use this code» is stored only when on, only on a lookup, and only with its marker")
    void acceptsUnlistedCode() {
        Map<String, Object> on = FieldValidationSupport.build(LookupValues.TYPE_SINGLE, null, null, null, null, null,
                null, null, null, true, null,
                FieldValidationSupport.FieldBehaviour.fromForm(null, null, null, null, "STAFF", "on", true));
        assertThat(on).containsEntry(FieldValidationSupport.KEY_LOOKUP_ACCEPTS_UNLISTED_CODE, true);
        assertThat(FieldValidationSupport.lookupAcceptsUnlistedCode(on)).isTrue();

        Map<String, Object> unticked = FieldValidationSupport.build(LookupValues.TYPE_MULTI, null, null, null, null, null,
                null, null, null, true, null,
                FieldValidationSupport.FieldBehaviour.fromForm(null, null, null, null, "STAFF", "on", null));
        assertThat(unticked).containsOnlyKeys(FieldValidationSupport.KEY_LOOKUP_LIST);

        Map<String, Object> noMarker = FieldValidationSupport.build(LookupValues.TYPE_SINGLE, null, null, null, null, null,
                null, null, null, true, null,
                FieldValidationSupport.FieldBehaviour.fromForm(null, null, null, null, "STAFF", null, true));
        assertThat(noMarker).as("a request that never showed the box widens nothing")
                .doesNotContainKey(FieldValidationSupport.KEY_LOOKUP_ACCEPTS_UNLISTED_CODE);

        Map<String, Object> text = FieldValidationSupport.build("text", null, null, null, null, null,
                null, null, null, true, null,
                FieldValidationSupport.FieldBehaviour.fromForm(null, null, null, null, null, "on", true));
        assertThat(text).as("a hidden checkbox still posts; any other type ignores it").isNull();

        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put(FieldValidationSupport.KEY_LOOKUP_ACCEPTS_UNLISTED_CODE, "true");
        assertThat(FieldValidationSupport.lookupAcceptsUnlistedCode(stored)).as("only a literal true").isFalse();
    }

    @Test
    @DisplayName("a lookup is never a warning or a danger")
    void noSeverity() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put(FieldValidationSupport.KEY_LOOKUP_LIST, "personnel");
        assertThat(FieldValidationSupport.evaluateValue(LookupValues.TYPE_SINGLE,
                LookupValues.element("1", "x", LookupValues.State.UNKNOWN), v))
                .isEqualTo(FieldValidationSeverity.OK);
    }

    private static List<String> messages(Map<String, Object> formData, FieldDefinition def) {
        return FormDataValidationSupport.validate(formData, List.of(def)).stream()
                .map(FormDataValidationSupport.ValidationIssue::message).toList();
    }

    private static FieldDefinition field(String key, String type) {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey(key);
        fd.setLabel(key);
        fd.setDataType(type);
        fd.setValidation(Map.of(FieldValidationSupport.KEY_LOOKUP_LIST, "personnel"));
        return fd;
    }
}
