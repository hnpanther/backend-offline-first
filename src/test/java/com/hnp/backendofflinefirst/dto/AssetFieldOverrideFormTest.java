package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.domain.AssetFieldResolver;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the parameter screen's POST becomes an override map.
 *
 * <p>The cases that matter are the ones where a field says <em>nothing</em>: a blank band is not
 * zero, an untouched row is not an override, and a row the screen rendered read-only must not
 * come back as an instruction to erase what it was displaying.
 */
class AssetFieldOverrideFormTest {

    @Test
    @DisplayName("an untouched row contributes no override")
    void anUntouchedRowIsNotAnOverride() {
        AssetFieldOverrideForm form = form(FieldSource.RANGES_ONLY, row("ampere", null, null, null, null));

        assertThat(form.toOverrides()).isEmpty();
    }

    @Test
    @DisplayName("blank is 'no bound', not zero")
    void blankIsNotZero() {
        AssetFieldOverrideForm form = form(FieldSource.RANGES_ONLY, row("ampere", "", "42", null, null));

        Map<String, Object> ampere = nested(form.toOverrides(), "ampere");
        @SuppressWarnings("unchecked")
        Map<String, Object> warning =
                (Map<String, Object>) ampere.get(FieldValidationSupport.KEY_WARNING);
        assertThat(warning).containsOnlyKeys(FieldValidationSupport.KEY_MAX);
    }

    @Test
    @DisplayName("a band the operator CLEARED is stored as an explicit null, not omitted")
    void aClearedBandIsExplicit() {
        // The page renders both inputs for a numeric row, so clearing them posts "" — not null.
        AssetFieldOverrideForm form = form(FieldSource.RANGES_ONLY, row("ampere", "", "", "", ""));

        Map<String, Object> ampere = nested(form.toOverrides(), "ampere");

        assertThat(ampere).containsKeys(
                FieldValidationSupport.KEY_WARNING, FieldValidationSupport.KEY_DANGER);
        assertThat(ampere.get(FieldValidationSupport.KEY_WARNING))
                .as("omitting the key would fall back to the class and put its number straight "
                        + "back in the box the operator just emptied")
                .isNull();
        assertThat(ampere.get(FieldValidationSupport.KEY_DANGER)).isNull();
    }

    @Test
    @DisplayName("a row that rendered NO band inputs says nothing about bands")
    void aRowWithoutBandInputsIsSilent() {
        // A select or text row: the inputs are not on the page, so Spring binds null.
        AssetFieldOverrideForm.Row text = row("note", null, null, null, null);
        text.setCustom(true);
        text.setLabel("توضیح");
        text.setDataType("text");

        Map<String, Object> note = nested(form(FieldSource.FULL, text).toOverrides(), "note");

        assertThat(note)
                .as("a band on a non-numeric field is refused by the service; never invent one")
                .doesNotContainKeys(FieldValidationSupport.KEY_WARNING,
                                    FieldValidationSupport.KEY_DANGER);
    }

    @Test
    @DisplayName("one bound cleared and the other kept is still a band")
    void clearingOneBoundKeepsTheOther() {
        AssetFieldOverrideForm form = form(FieldSource.RANGES_ONLY, row("ampere", "", "42", "", ""));

        Map<String, Object> ampere = nested(form.toOverrides(), "ampere");
        @SuppressWarnings("unchecked")
        Map<String, Object> warning =
                (Map<String, Object>) ampere.get(FieldValidationSupport.KEY_WARNING);

        assertThat(warning).containsOnlyKeys(FieldValidationSupport.KEY_MAX);
        assertThat(ampere.get(FieldValidationSupport.KEY_DANGER)).isNull();
    }

    @Test
    @DisplayName("half a pair never throws, even from a hand-built POST")
    void aHalfRenderedPairIsNullSafe() {
        AssetFieldOverrideForm form = form(FieldSource.RANGES_ONLY, row("ampere", null, "42", null, null));

        assertThat(nested(form.toOverrides(), "ampere"))
                .containsKey(FieldValidationSupport.KEY_WARNING);
    }

    @Test
    void inheritProducesNothingAtAll() {
        AssetFieldOverrideForm form = form(FieldSource.INHERIT, row("ampere", "1", "2", null, null));

        assertThat(form.toOverrides()).isEmpty();
    }

    @Test
    @DisplayName("a removal is only a removal in FULL")
    void removalNeedsFull() {
        AssetFieldOverrideForm.Row removed = row("ampere", null, null, null, null);
        removed.setRemoved(true);

        assertThat(form(FieldSource.FULL, removed).toOverrides())
                .extractingByKey("ampere")
                .isEqualTo(Map.of(AssetFieldResolver.OVERRIDE_REMOVED, true));
        assertThat(form(FieldSource.RANGES_ONLY, removed).toOverrides())
                .as("the class owns the field list under RANGES_ONLY")
                .isEmpty();
    }

    @Test
    @DisplayName("switching a row off keeps its band, so unticking the box restores it")
    void switchingOffKeepsTheBand() {
        AssetFieldOverrideForm.Row off = row("ampere", "18", "42", null, null);
        off.setRemoved(true);

        Map<String, Object> ampere = nested(form(FieldSource.FULL, off).toOverrides(), "ampere");

        assertThat(ampere).containsEntry(AssetFieldResolver.OVERRIDE_REMOVED, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> warning =
                (Map<String, Object>) ampere.get(FieldValidationSupport.KEY_WARNING);
        assertThat(warning)
                .as("discarding it made disabling a one-way door: the band was gone on the tick")
                .containsEntry(FieldValidationSupport.KEY_MIN, "18")
                .containsEntry(FieldValidationSupport.KEY_MAX, "42");
    }

    @Test
    @DisplayName("switching an ADDED parameter off keeps its whole definition")
    void switchingOffKeepsAnAddedFieldsDefinition() {
        AssetFieldOverrideForm.Row off = row("vibration", "0", "7", null, null);
        off.setCustom(true);
        off.setRemoved(true);
        off.setLabel("\u0644\u0631\u0632\u0634");
        off.setUnit("mm/s");
        off.setDataType("number");

        assertThat(nested(form(FieldSource.FULL, off).toOverrides(), "vibration"))
                .as("without these the parameter cannot be switched back on \u2014 there is no "
                        + "class row to fall back to, so it would return nameless")
                .containsEntry(AssetFieldResolver.OVERRIDE_REMOVED, true)
                .containsEntry("label", "\u0644\u0631\u0632\u0634")
                .containsEntry("unit", "mm/s")
                .containsEntry("dataType", "number");
    }

    @Test
    @DisplayName("an added CHOICE parameter keeps its vocabulary while switched off")
    void switchingOffKeepsAnAddedChoiceVocabulary() {
        AssetFieldOverrideForm.Row off = row("valve", null, null, null, null);
        off.setCustom(true);
        off.setRemoved(true);
        off.setDataType("select");
        off.setSelectOptions("open\nclosed\nhalf");
        off.setDangerOptions("closed");

        assertThat(nested(form(FieldSource.FULL, off).toOverrides(), "valve"))
                .containsEntry(AssetFieldResolver.OVERRIDE_REMOVED, true)
                .containsEntry(FieldValidationSupport.KEY_OPTIONS,
                        List.of("open", "closed", "half"))
                .containsEntry(FieldValidationSupport.KEY_DANGER_OPTIONS, List.of("closed"));
    }

    @Test
    @DisplayName("re-saving must not strip an added field's own definition")
    void anAddedFieldKeepsItsDefinitionAcrossASave() {
        // Exactly what the screen posts back for a field this asset added: the row is rendered
        // again, so it returns with custom=true and whatever the inputs carry.
        AssetFieldOverrideForm.Row added = row("vibration", "0", "7", null, null);
        added.setCustom(true);
        added.setLabel("لرزش");
        added.setUnit("mm/s");
        added.setDataType("number");

        Map<String, Object> vibration = nested(form(FieldSource.FULL, added).toOverrides(), "vibration");

        assertThat(vibration)
                .as("losing these on every save would quietly rename the field to its key")
                .containsEntry("label", "لرزش")
                .containsEntry("unit", "mm/s")
                .containsEntry("dataType", "number");
    }

    @Test
    @DisplayName("a class field re-banded in FULL does NOT claim its own definition")
    void aRebandedClassFieldStaysFollowingTheClass() {
        AssetFieldOverrideForm.Row reBanded = row("ampere", "18", "42", null, null);
        reBanded.setCustom(false);
        reBanded.setLabel("something the form should ignore");

        assertThat(nested(form(FieldSource.FULL, reBanded).toOverrides(), "ampere"))
                .as("only a field the asset ADDED carries its own label")
                .doesNotContainKey("label");
    }

    // -----------------------------------------------------------------------------------

    private static AssetFieldOverrideForm form(FieldSource source, AssetFieldOverrideForm.Row... rows) {
        AssetFieldOverrideForm form = new AssetFieldOverrideForm();
        form.setFieldSource(source.name());
        form.setRows(List.of(rows));
        return form;
    }

    // -----------------------------------------------------------------------------------
    // Option severities — and the trap of asking the DOM instead of the type
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("a class choice row carries its marking, and an empty answer means 'cleared'")
    void aChoiceRowCarriesItsMarking() {
        AssetFieldOverrideForm.Row marked = row("state", null, null, null, null);
        marked.setChoiceRow(true);
        marked.setWarningOptions("Idle");
        marked.setDangerOptions("OF");

        Map<String, Object> state = nested(form(FieldSource.RANGES_ONLY, marked).toOverrides(), "state");
        assertThat(state).containsEntry(FieldValidationSupport.KEY_WARNING_OPTIONS, List.of("Idle"));
        assertThat(state).containsEntry(FieldValidationSupport.KEY_DANGER_OPTIONS, List.of("OF"));

        AssetFieldOverrideForm.Row cleared = row("state", null, null, null, null);
        cleared.setChoiceRow(true);

        Map<String, Object> wiped = nested(form(FieldSource.RANGES_ONLY, cleared).toOverrides(), "state");
        assertThat(wiped)
                .as("the screen showed the control and the admin unticked everything — that is "
                        + "an answer, and skipping it would read as 'inherit the class's'")
                .containsEntry(FieldValidationSupport.KEY_WARNING_OPTIONS, List.of())
                .containsEntry(FieldValidationSupport.KEY_DANGER_OPTIONS, List.of());
    }

    @Test
    @DisplayName("a numeric row never claims an option severity, even with the box in the DOM")
    void aNumericRowPostsNoOptionSeverity() {
        // The reported shape of the bug: the vocabulary box is HIDDEN for a numeric type, not
        // removed, so the browser still posts it as "". Deciding from that ("was a box
        // submitted?") stored an empty severity list on a number — which the sanitiser refuses,
        // so adding a numeric parameter failed outright. The declared TYPE is the authority.
        AssetFieldOverrideForm.Row added = row("vibration", "0", "7", null, null);
        added.setCustom(true);
        added.setDataType("number");
        added.setSelectOptions("");
        added.setWarningOptions("");
        added.setDangerOptions("");

        Map<String, Object> vibration =
                nested(form(FieldSource.FULL, added).toOverrides(), "vibration");

        assertThat(vibration).doesNotContainKeys(
                FieldValidationSupport.KEY_WARNING_OPTIONS,
                FieldValidationSupport.KEY_DANGER_OPTIONS,
                FieldValidationSupport.KEY_OPTIONS);
        assertThat(vibration).containsKey(FieldValidationSupport.KEY_WARNING);
    }

    @Test
    @DisplayName("an added choice row carries its own vocabulary and marking")
    void anAddedChoiceRowCarriesBoth() {
        AssetFieldOverrideForm.Row added = row("valve", null, null, null, null);
        added.setCustom(true);
        added.setDataType("select");
        added.setLabel("Valve");
        added.setSelectOptions("open, closed, half");
        added.setDangerOptions("closed");

        Map<String, Object> valve = nested(form(FieldSource.FULL, added).toOverrides(), "valve");

        assertThat(valve).containsEntry(FieldValidationSupport.KEY_OPTIONS,
                List.of("open", "closed", "half"));
        assertThat(valve).containsEntry(FieldValidationSupport.KEY_DANGER_OPTIONS,
                List.of("closed"));
        assertThat(valve).containsEntry("dataType", "select");
    }

    @Test
    @DisplayName("a vocabulary is trimmed and de-duplicated, keeping the admin's order")
    void aVocabularyIsNormalised() {
        AssetFieldOverrideForm.Row added = row("valve", null, null, null, null);
        added.setCustom(true);
        added.setDataType("multiselect");
        added.setSelectOptions("  open , closed ,open,  , half ");

        assertThat(nested(form(FieldSource.FULL, added).toOverrides(), "valve"))
                .containsEntry(FieldValidationSupport.KEY_OPTIONS,
                        List.of("open", "closed", "half"));
    }

    private static AssetFieldOverrideForm.Row row(String key, String wMin, String wMax,
                                                   String dMin, String dMax) {
        AssetFieldOverrideForm.Row row = new AssetFieldOverrideForm.Row();
        row.setFieldKey(key);
        row.setWarningMin(wMin);
        row.setWarningMax(wMax);
        row.setDangerMin(dMin);
        row.setDangerMax(dMax);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> overrides, String key) {
        return (Map<String, Object>) overrides.get(key);
    }
}
