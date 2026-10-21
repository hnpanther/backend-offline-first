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
