package com.hnp.backendofflinefirst.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How attachment references are read out of {@code form_data}.
 *
 * <p>The canonical shape is {@code {type: "attachment", ids: [...]}}, but this parser also has
 * to survive older or hand-written data, so it is tolerant about shape and strict about
 * content: anything that is not a usable id is dropped rather than carried along as a
 * reference that can never resolve.
 */
class AttachmentReferencesTest {

    private static Map<String, Object> attachmentValue(Object ids) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "attachment");
        value.put("ids", ids);
        return value;
    }

    @Test
    void readsTheCanonicalShape() {
        assertThat(AttachmentReferences.idsOf(attachmentValue(List.of("a", "b"))))
                .containsExactly("a", "b");
    }

    @Test
    void acceptsABareListOrASingleId() {
        // Tolerance for clients that stored the ids directly rather than wrapping them.
        assertThat(AttachmentReferences.idsOf(List.of("a", "b"))).containsExactly("a", "b");
        assertThat(AttachmentReferences.idsOf("solo")).containsExactly("solo");
        assertThat(AttachmentReferences.idsOf(attachmentValue("solo"))).containsExactly("solo");
    }

    @Test
    void dropsBlankNullAndDuplicateIds() {
        // Each of these would otherwise become a reference that can never resolve.
        assertThat(AttachmentReferences.idsOf(Arrays.asList("a", "", "  ", null, "null", "a", "b")))
                .containsExactly("a", "b");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(AttachmentReferences.idsOf(List.of("  a  "))).containsExactly("a");
    }

    @Test
    void treatsAnEmptyOrAbsentValueAsNoReferences() {
        assertThat(AttachmentReferences.idsOf(null)).isEmpty();
        assertThat(AttachmentReferences.idsOf(attachmentValue(List.of()))).isEmpty();
        assertThat(AttachmentReferences.idsOf(new HashMap<>())).isEmpty();
        assertThat(AttachmentReferences.idsOf(List.of())).isEmpty();
    }

    @Test
    void recognisesTheReferenceShapeEvenWhenItHoldsNoIds() {
        // Needed to tell "an attachment field that was left empty" apart from "a text field",
        // which is what lets the validator report the right message.
        assertThat(AttachmentReferences.looksLikeAttachmentValue(attachmentValue(List.of()))).isTrue();
        assertThat(AttachmentReferences.looksLikeAttachmentValue("just text")).isFalse();
        assertThat(AttachmentReferences.looksLikeAttachmentValue(Map.of("type", "number"))).isFalse();
        assertThat(AttachmentReferences.looksLikeAttachmentValue(null)).isFalse();
    }

    @Test
    void extractsOnlyTheFieldsThatActuallyCarryIds() {
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("pressure", 42);
        formData.put("pump_photo", attachmentValue(List.of("a", "b")));
        formData.put("empty_photo", attachmentValue(List.of()));
        formData.put("note", "");

        Map<String, List<String>> extracted = AttachmentReferences.extract(formData);

        assertThat(extracted).containsOnlyKeys("pump_photo");
        assertThat(extracted.get("pump_photo")).containsExactly("a", "b");
    }

    @Test
    void aNumericValueIsNotMistakenForAnAttachmentReference() {
        // 42 stringifies to "42", a plausible-looking id. idsOf is permissive because it is
        // only called on values already known to be attachment-typed; extract scans blind and
        // must therefore require the reference *shape*, or every number field would look like
        // it held an attachment.
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("pressure", 42);
        formData.put("note", "some text");

        assertThat(AttachmentReferences.idsOf(42)).containsExactly("42");
        assertThat(AttachmentReferences.extract(formData))
                .as("scalars are not references")
                .isEmpty();
    }

    @Test
    void extractAcceptsAPlainCollectionOfIds() {
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("legacy_photo", List.of("a", "b"));

        assertThat(AttachmentReferences.extract(formData).get("legacy_photo"))
                .containsExactly("a", "b");
    }

    @Test
    void survivesNullAndEmptyFormData() {
        assertThat(AttachmentReferences.extract(null)).isEmpty();
        assertThat(AttachmentReferences.extract(Map.of())).isEmpty();
    }

    @Test
    void writesBackACanonicalValue() {
        Map<String, Object> value = AttachmentReferences.toValue(List.of("a", "b"));

        assertThat(value).containsEntry("type", "attachment");
        assertThat(value).containsEntry("ids", List.of("a", "b"));
        assertThat(AttachmentReferences.idsOf(value)).containsExactly("a", "b");
    }

    @Test
    void canonicalValueHandlesNullIds() {
        assertThat(AttachmentReferences.toValue(null)).containsEntry("ids", List.of());
    }
}
