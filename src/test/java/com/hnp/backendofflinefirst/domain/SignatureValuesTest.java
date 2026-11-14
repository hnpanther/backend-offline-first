package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.service.AttachmentReferences;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signature field (roadmap §32): what a client may contribute, what the server stamps, and
 * the verdict that makes it a signature rather than a picture.
 */
class SignatureValuesTest {

    private static final long NOW = 1_800_000_000_000L;

    private static FieldDefinition def(String key, String dataType) {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey(key);
        fd.setLabel(key);
        fd.setDataType(dataType);
        return fd;
    }

    private static final List<FieldDefinition> DEFS = List.of(
            def("pressure", "number"),
            def("ok", "checkbox"),
            def("parts", "multiselect"),
            def("note", "text"),
            def("sign", "signature"));

    private static Map<String, Object> answers(Object pressure, Object sign) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pressure", pressure);
        m.put("note", "سالم");
        if (sign != null) {
            m.put("sign", sign);
        }
        return m;
    }

    private static Map<String, Object> drawn(String id) {
        return SignatureValues.canonicalIncoming(Map.of("type", "signature", "ids", List.of(id)));
    }

    // ------------------------------------------------------------------ what a client contributes

    @Test
    void aClientMayContributeTheDrawingAndItsTimeAndNothingElse() {
        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("type", "signature");
        incoming.put("ids", List.of("a"));
        incoming.put("signedAt", 123L);
        incoming.put("signedByUserId", 999);          // a forged signer
        incoming.put("answersHash", "sha256:forged");  // and a forged verdict

        assertThat(SignatureValues.canonicalIncoming(incoming))
                .containsOnlyKeys("type", "ids", "signedAt")
                .containsEntry("ids", List.of("a"));
    }

    @Test
    void theWebFormsRepeatedIdsAreAcceptedAndNothingIsUnanswered() {
        assertThat(SignatureValues.canonicalIncoming(List.of("a"))).containsEntry("ids", List.of("a"));
        assertThat(SignatureValues.canonicalIncoming("")).isNull();
        assertThat(SignatureValues.canonicalIncoming(Map.of("type", "signature", "ids", List.of()))).isNull();
    }

    // ------------------------------------------------------------------ stamping

    @Test
    void aNewDrawingIsStampedWithTheSessionUserTheHashAndTheAlgorithm() {
        Map<String, Object> next = answers(12, drawn("a"));

        Map<String, Object> out = SignatureValues.stamp(next, null, DEFS, 42L, NOW);

        Map<?, ?> sign = (Map<?, ?>) out.get("sign");
        assertThat(sign.get("signedByUserId")).isEqualTo(42L);
        assertThat(sign.get("hashAlgo")).isEqualTo("v1");
        assertThat(String.valueOf(sign.get("answersHash"))).startsWith("sha256:");
        assertThat(sign.get("signedAt")).isEqualTo(NOW);
        assertThat(SignatureValues.state(sign, out, DEFS)).isEqualTo(SignatureValues.State.VALID);
    }

    @Test
    void anUnchangedDrawingKeepsItsStampByteForByte_soAResubmitIsNotAChange() {
        Map<String, Object> first = SignatureValues.stamp(answers(12, drawn("a")), null, DEFS, 42L, NOW);
        // The device sends the drawing back (as it received it in the bundle) with a resubmit, by
        // another session even — the stamp must not move.
        Map<String, Object> again = SignatureValues.stamp(
                answers(12, drawn("a")), first, DEFS, 7L, NOW + 60_000);

        assertThat(again).isEqualTo(first);
    }

    @Test
    void aDifferentDrawingIsStampedAfresh() {
        Map<String, Object> first = SignatureValues.stamp(answers(12, drawn("a")), null, DEFS, 42L, NOW);
        Map<String, Object> resigned = SignatureValues.stamp(answers(12, drawn("b")), first, DEFS, 7L, NOW + 1);

        assertThat(((Map<?, ?>) resigned.get("sign")).get("signedByUserId")).isEqualTo(7L);
        assertThat(AttachmentReferences.idsOf(resigned.get("sign"))).containsExactly("b");
    }

    @Test
    void aDrawingWithNoIdsLeavesTheFieldUnanswered() {
        Map<String, Object> next = answers(12, Map.of("type", "signature", "ids", List.of()));
        assertThat(SignatureValues.stamp(next, null, DEFS, 42L, NOW)).doesNotContainKey("sign");
    }

    @Test
    void theDeviceTimeIsKeptWhenPlausibleAndReplacedWhenItIsAWrongClock() {
        Map<String, Object> earlier = new LinkedHashMap<>(drawn("a"));
        earlier.put("signedAt", NOW - 3_600_000L);
        Map<?, ?> kept = (Map<?, ?>) SignatureValues.stamp(answers(1, earlier), null, DEFS, 1L, NOW).get("sign");
        assertThat(kept.get("signedAt")).isEqualTo(NOW - 3_600_000L);

        Map<String, Object> future = new LinkedHashMap<>(drawn("a"));
        future.put("signedAt", NOW + 7L * 24 * 3_600_000L);
        Map<?, ?> replaced = (Map<?, ?>) SignatureValues.stamp(answers(1, future), null, DEFS, 1L, NOW).get("sign");
        assertThat(replaced.get("signedAt")).isEqualTo(NOW);
    }

    // ------------------------------------------------------------------ the verdict

    @Test
    void anAnswerChangedAfterSigningMakesTheSignatureStale() {
        Map<String, Object> signed = SignatureValues.stamp(answers(12, drawn("a")), null, DEFS, 42L, NOW);
        Map<String, Object> corrected = new LinkedHashMap<>(signed);
        corrected.put("pressure", 13);

        assertThat(SignatureValues.state(corrected.get("sign"), corrected, DEFS))
                .isEqualTo(SignatureValues.State.STALE);
    }

    @Test
    void theSameReadingWrittenDifferentlyIsTheSameAnswer() {
        // The web posts strings and the tablet numbers; a cleared and an absent key agree; a
        // multiselect's order is not an answer.
        Map<String, Object> a = new LinkedHashMap<>(Map.of("pressure", 12, "ok", true, "parts", List.of("x", "y")));
        Map<String, Object> b = new LinkedHashMap<>(Map.of("pressure", "12.0", "ok", "true", "parts", List.of("y", "x")));
        b.put("note", "   ");
        assertThat(SignatureValues.answersHash(a, DEFS)).isEqualTo(SignatureValues.answersHash(b, DEFS));
    }

    @Test
    void aSignatureDoesNotCoverItselfOrAnotherSignature() {
        Map<String, Object> m = answers(12, drawn("a"));
        String before = SignatureValues.answersHash(m, DEFS);
        m.put("sign", drawn("b"));
        assertThat(SignatureValues.answersHash(m, DEFS)).isEqualTo(before);
    }

    @Test
    void aSignatureWithoutAStampIsUnverifiedNotValid() {
        assertThat(SignatureValues.state(drawn("a"), answers(1, drawn("a")), DEFS))
                .isEqualTo(SignatureValues.State.UNVERIFIED);
    }

    // ------------------------------------------------------------------ the attachment readers

    @Test
    void everyBlindAttachmentScanSeesTheDrawing() {
        Map<String, Object> m = answers(12, drawn("a"));
        assertThat(AttachmentReferences.looksLikeAttachmentValue(m.get("sign"))).isTrue();
        assertThat(AttachmentReferences.extract(m)).containsEntry("sign", List.of("a"));
        assertThat(AttachmentKind.forFieldDataType("signature")).isEqualTo(AttachmentKind.IMAGE);
    }

    // ------------------------------------------------------------------ the write filters

    @Test
    void retainKnownKeysKeepsTheSignatureShapeRatherThanFlatteningItToAPhoto() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("sign", Map.of("type", "signature", "ids", List.of("a"), "signedByUserId", 999));
        Map<String, Object> kept = FormDataValidationSupport.retainKnownKeys(raw, DEFS);

        @SuppressWarnings("unchecked")
        Map<String, Object> sign = (Map<String, Object>) kept.get("sign");
        assertThat(sign)
                .containsEntry("type", "signature")
                .doesNotContainKey("signedByUserId");
    }

    @Test
    void aSignatureNamesExactlyOneDrawing() {
        FieldDefinition sign = def("sign", "signature");
        sign.setRequired(true);
        assertThat(FormDataValidationSupport.validate(
                Map.of("sign", Map.of("type", "signature", "ids", List.of("a", "b"))), List.of(sign)))
                .extracting(FormDataValidationSupport.ValidationIssue::message)
                .contains("a signature holds exactly one drawing");
        assertThat(FormDataValidationSupport.validate(
                Map.of("sign", Map.of("type", "signature", "ids", List.of("a"))), List.of(sign)))
                .isEmpty();
        assertThat(FormDataValidationSupport.validate(Map.of(), List.of(sign)))
                .extracting(FormDataValidationSupport.ValidationIssue::message)
                .contains("required field is missing");
    }

    @Test
    void theTypeIsOnTheWhitelist() {
        assertThat(FieldDataTypes.isValid("signature")).isTrue();
    }
}
