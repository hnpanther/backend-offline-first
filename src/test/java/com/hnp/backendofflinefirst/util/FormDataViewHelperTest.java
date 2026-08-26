package com.hnp.backendofflinefirst.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.AttachmentKind;
import com.hnp.backendofflinefirst.entity.Attachment;
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

    // ---------------------------------------------------------------- revisionRows

    private static FieldDefinition mediaDef(String key, String label, String dataType) {
        FieldDefinition fd = def(key, label, dataType, 1);
        return fd;
    }

    private static Attachment attachment(String id, AttachmentKind kind, long size, Long durationMs) {
        Attachment a = new Attachment();
        a.setId(id);
        a.setKind(kind);
        a.setSizeBytes(size);
        a.setDurationMs(durationMs);
        return a;
    }

    private static Map<String, Object> snapshotMeta(String kind, long size, Long durationMs) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", kind);
        meta.put("mimeType", "audio/webm");
        meta.put("sizeBytes", size);
        meta.put("durationMs", durationMs);
        return meta;
    }

    /**
     * The whole point of the snapshot. The attachment row is gone — deleted along with the value
     * that referenced it — and without the snapshot the panel could only say «در دسترس نیست»,
     * which reads identically to storage having lost the file.
     */
    @Test
    void revisionRowsDescribeAnAttachmentWhoseRowIsGone() {
        List<FieldDefinition> defs = List.of(mediaDef("voice", "یادداشت صوتی", "audio"));
        Map<String, Object> formData = Map.of("voice", List.of("att-1"));

        FormDataViewHelper.FormFieldRow row = helper.revisionRows(
                formData, defs, Map.of(),
                Map.of("att-1", snapshotMeta("AUDIO", 40960, 20000L))).getFirst();

        assertThat(row.attachments()).hasSize(1);
        FormDataViewHelper.AttachmentView att = row.attachments().getFirst();
        assertThat(att.removed()).isTrue();
        assertThat(att.missing()).isFalse();
        assertThat(att.isAudio()).isTrue();
        // 40 KB · 0:20 — the two facts that make "a voice note was removed" actionable.
        assertThat(att.captionLabel()).isEqualTo("40 KB · 0:20");
    }

    /**
     * An id in the snapshot whose attachment still exists is NOT removed. The correction merely
     * detached it from the field; the bytes are streamable, and a live thumbnail beats a
     * description of one.
     */
    @Test
    void revisionRowsPreferTheLiveAttachmentOverTheSnapshot() {
        List<FieldDefinition> defs = List.of(mediaDef("photo", "عکس", "image"));
        Map<String, Object> formData = Map.of("photo", List.of("att-1"));
        Map<String, Attachment> live = Map.of("att-1", attachment("att-1", AttachmentKind.IMAGE, 2048, null));

        FormDataViewHelper.AttachmentView att = helper.revisionRows(
                formData, defs, live,
                Map.of("att-1", snapshotMeta("AUDIO", 999999, 60000L)))
                .getFirst().attachments().getFirst();

        assertThat(att.removed()).isFalse();
        assertThat(att.missing()).isFalse();
        assertThat(att.isImage()).isTrue();
        assertThat(att.sizeLabel()).isEqualTo("2 KB");
    }

    /**
     * No snapshot at all — a revision written before the column existed. It must degrade to the
     * old behaviour rather than claim the file was deliberately removed, because nothing here
     * knows that.
     */
    @Test
    void revisionRowsWithoutASnapshotStillReportAMissingAttachment() {
        List<FieldDefinition> defs = List.of(mediaDef("photo", "عکس", "image"));
        Map<String, Object> formData = Map.of("photo", List.of("att-gone"));

        FormDataViewHelper.AttachmentView att = helper.revisionRows(formData, defs, Map.of(), null)
                .getFirst().attachments().getFirst();

        assertThat(att.missing()).isTrue();
        assertThat(att.removed()).isFalse();
    }

    /** A snapshot entry with nothing in it must not blow up the history panel. */
    @Test
    void revisionRowsSurviveAnEmptyOrUnknownSnapshotEntry() {
        List<FieldDefinition> defs = List.of(mediaDef("photo", "عکس", "image"));
        Map<String, Object> formData = Map.of("photo", List.of("att-1", "att-2"));
        Map<String, Map<String, Object>> snapshot = new LinkedHashMap<>();
        snapshot.put("att-1", Map.of());
        Map<String, Object> weird = new LinkedHashMap<>();
        weird.put("kind", "HOLOGRAM");
        snapshot.put("att-2", weird);

        List<FormDataViewHelper.AttachmentView> atts =
                helper.revisionRows(formData, defs, Map.of(), snapshot).getFirst().attachments();

        assertThat(atts).hasSize(2);
        assertThat(atts).allMatch(FormDataViewHelper.AttachmentView::removed);
        assertThat(atts).noneMatch(FormDataViewHelper.AttachmentView::missing);
        assertThat(atts.getFirst().captionLabel()).isEmpty();
    }

    /**
     * revisionRows enumerates the schema like allRows, so a parameter the superseded value never
     * carried still appears as «ثبت نشده» — a correction that ADDED a reading is exactly as
     * interesting as one that changed it.
     */
    @Test
    void revisionRowsEnumerateTheSchemaLikeAllRows() {
        List<FieldDefinition> defs = List.of(
                def("temp", "دما", "number", 1),
                def("bar", "فشار", "number", 2));

        List<FormDataViewHelper.FormFieldRow> rows =
                helper.revisionRows(Map.of("temp", 42), defs, Map.of(), null);

        assertThat(rows).extracting(FormDataViewHelper.FormFieldRow::label)
                .containsExactly("دما", "فشار");
        assertThat(rows.get(1).isEmpty()).isTrue();
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
