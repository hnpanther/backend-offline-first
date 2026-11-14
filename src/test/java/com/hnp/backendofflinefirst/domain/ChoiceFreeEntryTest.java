package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.util.FieldValidationViewHelper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Free entry on a choice field (roadmap §31, phase 1): a permission the administrator gives one
 * field, and a boundary the server keeps whatever a client offers.
 */
class ChoiceFreeEntryTest {

    private static Map<String, Object> build(String dataType, String options, boolean allowCustom) {
        return FieldValidationSupport.build(dataType, options, null, null, null, null,
                null, null, null, true, null, new FieldValidationSupport.FieldBehaviour(allowCustom, null));
    }

    private static FieldDefinition field(String dataType, Map<String, Object> validation) {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey("reason");
        fd.setLabel("علت توقف");
        fd.setDataType(dataType);
        fd.setValidation(validation);
        return fd;
    }

    private static List<FormDataValidationSupport.ValidationIssue> issues(FieldDefinition fd, Object value) {
        return FormDataValidationSupport.validatePartialEntry(Map.of("reason", value), List.of(fd));
    }

    // ------------------------------------------------------------------ storing the permission

    @Test
    void itIsStoredOnlyWhenTrueAndOnlyOnAChoiceFieldWithOptions() {
        assertThat(build("select", "قطع برق\nخرابی", true)).containsEntry("allowCustom", true);
        assertThat(build("multiselect", "a\nb", true)).containsEntry("allowCustom", true);
        assertThat(build("select", "قطع برق\nخرابی", false)).doesNotContainKey("allowCustom");
        // A choice field with no options already accepts anything: the flag would say nothing.
        assertThat(build("select", "", true)).isNull();
        // Any other type ignores it — a hidden control still posts.
        assertThat(build("number", null, true)).isNull();
    }

    @Test
    void theShorterOverloadsWriteFalse_whichIsWhyEveryEditorMustPassIt() {
        Map<String, Object> v = FieldValidationSupport.build("select", "a\nb", null, null, null, null,
                null, null, null, true, null);
        assertThat(v).doesNotContainKey("allowCustom");
    }

    @Test
    void theFormMarkerHasItsOwnNameAndItsAbsenceNeverWidens() {
        assertThat(FieldValidationSupport.allowCustomFromForm(null, true)).isFalse();
        assertThat(FieldValidationSupport.allowCustomFromForm("on", null)).isFalse();
        assertThat(FieldValidationSupport.allowCustomFromForm("on", true)).isTrue();
    }

    // ------------------------------------------------------------------ the boundary

    @Test
    void withoutThePermissionATypedAnswerIsRefusedAsBefore() {
        FieldDefinition fd = field("select", build("select", "قطع برق\nخرابی", false));
        assertThat(issues(fd, "گیر کردن شیر برقی")).isNotEmpty();
        assertThat(issues(fd, "قطع برق")).isEmpty();
    }

    @Test
    void withItAShortSingleLineTypedAnswerIsAccepted() {
        FieldDefinition fd = field("select", build("select", "قطع برق\nخرابی", true));
        assertThat(issues(fd, "گیر کردن شیر برقی")).isEmpty();
        assertThat(issues(fd, "قطع برق")).isEmpty();
    }

    @Test
    void butNotALongOneACharacterOverTheLimitCountingCodePoints() {
        FieldDefinition fd = field("select", build("select", "a\nb", true));
        assertThat(issues(fd, "ب".repeat(200))).isEmpty();
        assertThat(issues(fd, "ب".repeat(201))).isNotEmpty();
    }

    @Test
    void norOneCarryingALineBreakOrAControlCharacter() {
        FieldDefinition fd = field("select", build("select", "a\nb", true));
        assertThat(issues(fd, "x\ny")).isNotEmpty();
        assertThat(issues(fd, "x\u0000y")).isNotEmpty();
    }

    @Test
    void norAnObjectWhereOneChoiceBelongs() {
        FieldDefinition fd = field("select", build("select", "a\nb", true));
        assertThat(issues(fd, Map.of("value", "x"))).isNotEmpty();
    }

    @Test
    void aMultiselectMayMixListedAndTypedAnswers() {
        FieldDefinition fd = field("multiselect", build("multiselect", "a\nb", true));
        assertThat(issues(fd, List.of("a", "نوشته‌شده"))).isEmpty();
        assertThat(issues(fd, List.of("a", "x".repeat(201)))).isNotEmpty();
    }

    @Test
    void aTypedAnswerIsNeverAWarningOrADanger() {
        Map<String, Object> v = FieldValidationSupport.build("select", "قطع برق\nخرابی", null, null, null, null,
                "قطع برق", "خرابی", null, true, null, new FieldValidationSupport.FieldBehaviour(true, null));
        assertThat(FieldValidationSupport.evaluateValue("select", "گیر کردن", v, Map.of()))
                .isEqualTo(FieldValidationSeverity.OK);
        assertThat(FieldValidationSupport.evaluateValue("select", "خرابی", v, Map.of()))
                .isEqualTo(FieldValidationSeverity.DANGER);
    }

    // ------------------------------------------------------------------ reading it back

    @Test
    void theFillPageRendersAStoredTypedAnswerAsAnOptionSoSavingCannotEraseIt() {
        FieldValidationViewHelper helper = new FieldValidationViewHelper();
        Map<String, Object> v = build("multiselect", "a\nb", true);
        assertThat(helper.customValues(v, List.of("a", "typed"))).containsExactly("typed");
        assertThat(helper.customValues(v, "a")).isEmpty();
        // Without the permission there is nothing legitimate to carry.
        assertThat(helper.customValues(build("multiselect", "a\nb", false), List.of("typed"))).isEmpty();
    }
}
