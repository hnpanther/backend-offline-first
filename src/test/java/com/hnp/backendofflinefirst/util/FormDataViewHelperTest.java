package com.hnp.backendofflinefirst.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FormDataViewHelperTest {

    FormDataViewHelper helper;

    @BeforeEach
    void setUp() {
        helper = new FormDataViewHelper(new ObjectMapper());
    }

    @Test
    void rowsIncludeValidationMessageForOutOfRangeNumber() {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey("temp");
        fd.setLabel("دما");
        fd.setDataType("number");
        fd.setUnit("°C");
        fd.setValidation(FieldValidationSupport.build("number", null, 20.0, 80.0, 10.0, 90.0));

        Map<String, Object> formData = Map.of("temp", 95);
        FormDataViewHelper.FormFieldRow row = helper.rows(formData, List.of(fd)).getFirst();

        assertThat(row.value()).isEqualTo("95");
        assertThat(row.validationMessage()).isEqualTo("خارج از بازه خطر است.");
        assertThat(row.validationAlertClass()).isEqualTo("text-danger");
    }

    @Test
    void rowsOmitValidationWhenValueIsInRange() {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey("temp");
        fd.setLabel("دما");
        fd.setDataType("number");
        fd.setValidation(FieldValidationSupport.build("number", null, 20.0, 80.0, 10.0, 90.0));

        FormDataViewHelper.FormFieldRow row = helper.rows(Map.of("temp", 50), List.of(fd)).getFirst();

        assertThat(row.validationMessage()).isNull();
    }

    @Test
    void rendersAMultiselectAsAReadableListNotJavasArrayToString() {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey("Status");
        fd.setLabel("وضعیت ها");
        fd.setDataType("multiselect");

        FormDataViewHelper.FormFieldRow row =
                helper.rows(Map.of("Status", List.of("on", "IDLE")), List.of(fd)).getFirst();

        // "[on, IDLE]" is Java talking to itself, not something to show an operator.
        assertThat(row.value()).isEqualTo("on، IDLE");
        assertThat(row.isEmpty()).isFalse();
    }

    @Test
    void treatsAMultiselectWithNothingSelectedAsAnUnfilledRow() {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey("Status");
        fd.setLabel("وضعیت ها");
        fd.setDataType("multiselect");

        FormDataViewHelper.FormFieldRow row =
                helper.rows(Map.of("Status", List.of()), List.of(fd)).getFirst();

        // Otherwise it would render as "[]", which reads like a value rather than a gap.
        assertThat(row.isEmpty()).isTrue();
    }

    // -----------------------------------------------------------------------
    // allRows — every parameter the class defines, answered or not
    //
    // `form_data` holds only the fields somebody actually answered (V3), which is what makes
    // `max_severity IS NOT NULL` an exact has-a-reading test and what stops one supervisor save
    // writing blanks onto forty entries. The cost is that a display built on its keys can only
    // show what was filled: an asset with three of seven parameters recorded rendered three rows,
    // and the four that were skipped looked exactly like parameters the class does not have.
    // -----------------------------------------------------------------------

    private static FieldDefinition def(String key, String label, String dataType, int order) {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey(key);
        fd.setLabel(label);
        fd.setDataType(dataType);
        fd.setOrder(order);
        return fd;
    }

    @Test
    void allRowsListsEveryParameterIncludingTheOnesNobodyAnswered() {
        List<FieldDefinition> defs = List.of(
                def("temp", "دما", "number", 1),
                def("bar", "فشار", "number", 2),
                def("note", "توضیح", "text", 3));

        List<FormDataViewHelper.FormFieldRow> rows =
                helper.allRows(Map.of("temp", 42), defs, Map.of());

        assertThat(rows).extracting(FormDataViewHelper.FormFieldRow::label)
                .containsExactly("دما", "فشار", "توضیح");
        assertThat(rows.get(0).isEmpty()).isFalse();
        // These two are the whole point: present, labelled, and visibly unanswered.
        assertThat(rows.get(1).isEmpty()).isTrue();
        assertThat(rows.get(2).isEmpty()).isTrue();
    }

    @Test
    void allRowsFollowsTheSchemaOrderRatherThanTheJsonKeyOrder() {
        // The class defines the order an operator fills them in; a JSONB map does not preserve
        // anything meaningful. Reading a 7-parameter asset is much harder if the rows move.
        List<FieldDefinition> defs = List.of(
                def("temp", "دما", "number", 1),
                def("bar", "فشار", "number", 2));

        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("bar", 3);
        formData.put("temp", 42);

        assertThat(helper.allRows(formData, defs, Map.of()))
                .extracting(FormDataViewHelper.FormFieldRow::label)
                .containsExactly("دما", "فشار");
    }

    @Test
    void allRowsStillShowsAReadingWhoseFieldTheClassNoLongerDefines() {
        // A sheet generated before `retainKnownKeys`, or one whose class lost a field afterwards
        // (roadmap.md §5), can hold a reading with no definition. Dropping it because the schema
        // moved on would quietly delete a measurement from the record.
        List<FieldDefinition> defs = List.of(def("temp", "دما", "number", 1));

        List<FormDataViewHelper.FormFieldRow> rows =
                helper.allRows(Map.of("temp", 42, "retired", "7"), defs, Map.of());

        assertThat(rows).extracting(FormDataViewHelper.FormFieldRow::label)
                .containsExactly("دما", "retired");
        assertThat(rows.get(1).value()).isEqualTo("7");
    }

    @Test
    void allRowsRendersAnEntirelyUnfilledAssetRatherThanNothingAtAll() {
        // `rows` returns an empty list for empty form data and the page shows a bare dash, so an
        // untouched asset gave no clue what it was supposed to carry.
        List<FieldDefinition> defs = List.of(
                def("temp", "دما", "number", 1),
                def("bar", "فشار", "number", 2));

        assertThat(helper.rows(Map.of(), defs, Map.of())).isEmpty();
        assertThat(helper.allRows(Map.of(), defs, Map.of()))
                .hasSize(2)
                .allMatch(FormDataViewHelper.FormFieldRow::isEmpty);
    }

    @Test
    void allRowsDoesNotColourAnUnansweredNumberAsOutOfRange() {
        // A band cannot be breached by an absent reading. Evaluating null would have painted
        // every unfilled row red on a class whose minimum is above zero.
        FieldDefinition fd = def("temp", "دما", "number", 1);
        fd.setValidation(FieldValidationSupport.build("number", null, 20.0, 80.0, 10.0, 90.0));

        FormDataViewHelper.FormFieldRow row = helper.allRows(Map.of(), List.of(fd), Map.of()).getFirst();

        assertThat(row.isEmpty()).isTrue();
        assertThat(row.validationMessage()).isNull();
        assertThat(row.validationAlertClass()).isNull();
    }

    @Test
    void allRowsKeepsTheValidationVerdictForAnAnsweredNumber() {
        FieldDefinition fd = def("temp", "دما", "number", 1);
        fd.setUnit("°C");
        fd.setValidation(FieldValidationSupport.build("number", null, 20.0, 80.0, 10.0, 90.0));

        FormDataViewHelper.FormFieldRow row =
                helper.allRows(Map.of("temp", 95), List.of(fd), Map.of()).getFirst();

        assertThat(row.value()).isEqualTo("95");
        assertThat(row.validationAlertClass()).isEqualTo("text-danger");
    }

    @Test
    void allRowsShowsAnUnansweredMediaFieldAsEmptyRatherThanAsAnAttachment() {
        FieldDefinition fd = def("photo", "عکس", "image", 1);

        FormDataViewHelper.FormFieldRow row =
                helper.allRows(Map.of(), List.of(fd), Map.of()).getFirst();

        assertThat(row.hasAttachments()).isFalse();
        assertThat(row.isEmpty()).isTrue();
    }

    @Test
    void allRowsFallsBackToTheDataWhenTheSheetCarriesNoSchema() {
        // A sheet with no field definitions resolvable at all — there is nothing to enumerate,
        // so the honest thing is what `rows` already does rather than an empty table.
        assertThat(helper.allRows(Map.of("temp", 42), List.of(), Map.of()))
                .extracting(FormDataViewHelper.FormFieldRow::value)
                .containsExactly("42");
        assertThat(helper.allRows(Map.of("temp", 42), null, Map.of()))
                .hasSize(1);
    }

    @Test
    void rowsIsUnchangedAndStillListsOnlyWhatWasAnswered() {
        // The voided-submission page shows what one refused payload literally contained; padding
        // it with rows that payload never carried would misrepresent it. Pinned so the two
        // methods cannot quietly converge.
        List<FieldDefinition> defs = List.of(
                def("temp", "دما", "number", 1),
                def("bar", "فشار", "number", 2));

        assertThat(helper.rows(Map.of("temp", 42), defs, Map.of()))
                .extracting(FormDataViewHelper.FormFieldRow::label)
                .containsExactly("دما");
    }

    @Test
    void hasMeaningfulDataDetectsFilledAndBlankEntries() {
        assertThat(helper.hasMeaningfulData(Map.of("temp", 42))).isTrue();
        assertThat(helper.hasMeaningfulData(Map.of("note", "ok"))).isTrue();
        assertThat(helper.hasMeaningfulData(Map.of())).isFalse();
        assertThat(helper.hasMeaningfulData(null)).isFalse();
        assertThat(helper.hasMeaningfulData(Map.of("temp", "", "note", "  "))).isFalse();
    }
}
