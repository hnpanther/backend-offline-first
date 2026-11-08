package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A field whose value can be read off a label instead of typed.
 *
 * <p>The thing worth protecting here is not the happy path — it is the set of defaults. This
 * configuration lives inside a JSONB blob that an older panel, a newer panel or a hand edit may
 * have written, and every one of those cases has to land on a field an operator can still fill.
 * A malformed blob that produced an un-fillable field would be a round that cannot be
 * completed, discovered in the plant.
 */
class ScanFieldFillTest {

    private static Map<String, Object> build(String dataType, List<String> sources, boolean manual) {
        return FieldValidationSupport.build(dataType, null, null, null, null, null, null, null,
                sources, manual);
    }

    // ------------------------------------------------------------------ writing

    @Test
    void aScannableTextFieldStoresItsSources() {
        Map<String, Object> validation = build("text", List.of("QR", "BARCODE"), true);

        assertThat(FieldValidationSupport.scanSources(validation))
                .containsExactly("QR", "BARCODE");
        // Order is the administrator's: a plant whose labels are mostly QR wants that first.
        assertThat(FieldValidationSupport.scanSources(build("text", List.of("BARCODE", "QR"), true)))
                .containsExactly("BARCODE", "QR");
    }

    @Test
    void manualIsStoredOnlyWhenItIsTurnedOff() {
        Map<String, Object> allowsTyping = build("text", List.of("QR"), true);
        Map<String, Object> scanOnly = build("text", List.of("QR"), false);

        // True is what every reader assumes, so writing it would put a key in almost every
        // scannable field to state what its absence already states.
        assertThat(scanObject(allowsTyping)).doesNotContainKey("manual");
        assertThat(scanObject(scanOnly)).containsEntry("manual", false);

        assertThat(FieldValidationSupport.scanAllowsManual(allowsTyping)).isTrue();
        assertThat(FieldValidationSupport.scanAllowsManual(scanOnly)).isFalse();
    }

    @Test
    void aFieldWithNoSourcesStoresNothingAtAll() {
        assertThat(build("text", List.of(), true)).isNull();
        assertThat(build("text", null, true)).isNull();
        // Including when manual was switched off: "you may not type into this" is meaningless
        // for a field with nothing to scan, and storing it would leave an un-fillable field
        // behind the moment an administrator clears the sources.
        assertThat(build("text", List.of(), false)).isNull();
    }

    @Test
    void onlyTextAndNumberCanBeScanned() {
        assertThat(FieldValidationSupport.supportsScanFill("text")).isTrue();
        assertThat(FieldValidationSupport.supportsScanFill("number")).isTrue();
        for (String other : List.of("select", "multiselect", "checkbox", "textarea",
                "image", "audio", "video", "location")) {
            assertThat(FieldValidationSupport.supportsScanFill(other))
                    .as("%s must not be scannable", other).isFalse();
            // A dropdown is not a boundary: asking for it anyway stores nothing.
            assertThat(build(other, List.of("QR"), true))
                    .as("%s must store no scan rule", other).isNull();
        }
    }

    @Test
    void anUnknownSourceIsDroppedRatherThanRefusingTheWholeField() {
        Map<String, Object> validation = build("text", List.of("QR", "RETINA_SCAN", "NFC"), true);

        // A label, not a credential: a panel newer than this build should not cost an
        // administrator the rest of the field's configuration.
        assertThat(FieldValidationSupport.scanSources(validation)).containsExactly("QR", "NFC");
    }

    @Test
    void sourcesAreNormalisedAndDeDuplicated() {
        assertThat(FieldValidationSupport.scanSources(
                build("text", List.of("qr", " QR ", "Nfc"), true)))
                .containsExactly("QR", "NFC");
    }

    // ------------------------------------------------------------------ the standing trap

    @Test
    void theScanRuleSurvivesAnEditThatChangesSomethingElse() {
        // `build` is a REBUILD, not a merge — its own javadoc says so, and `allowNegative` died
        // exactly this way. This asserts that `scan` entered the signature rather than being
        // written anywhere closer to the form: an edit that only touches the warning range must
        // not silently drop the scan configuration.
        Map<String, Object> edited = FieldValidationSupport.build(
                "number", null, 10.0, 20.0, null, null, null, null, List.of("BARCODE"), false);

        assertThat(FieldValidationSupport.scanSources(edited)).containsExactly("BARCODE");
        assertThat(FieldValidationSupport.scanAllowsManual(edited)).isFalse();
        assertThat(edited).containsKey(FieldValidationSupport.KEY_WARNING);
    }

    @Test
    void theOldBuildSignatureStillWritesNoScanRule() {
        // Every caller that predates this feature keeps behaving identically.
        Map<String, Object> legacy = FieldValidationSupport.build(
                "text", null, null, null, null, null, null, null);

        assertThat(legacy).isNull();
        assertThat(FieldValidationSupport.scanSources(legacy)).isEmpty();
    }

    // ------------------------------------------------------------------ reading a hostile blob

    @Test
    void anUnreadableBlobLeavesTheFieldTypeableRatherThanUnfillable() {
        // THE DEFAULT THAT MATTERS. Each of these is something a hand edit or a future writer
        // could leave behind; every one must yield "not scannable, and you may type".
        for (Map<String, Object> hostile : List.of(
                Map.<String, Object>of("scan", "yes"),
                Map.<String, Object>of("scan", List.of("QR")),
                Map.<String, Object>of("scan", Map.of()),
                Map.<String, Object>of("scan", Map.of("sources", "QR")),
                Map.<String, Object>of("scan", Map.of("sources", List.of())),
                Map.<String, Object>of("warning", Map.of("min", 1)))) {
            assertThat(FieldValidationSupport.scanSources(hostile))
                    .as("sources from %s", hostile).isEmpty();
            assertThat(FieldValidationSupport.scanAllowsManual(hostile))
                    .as("manual from %s", hostile).isTrue();
        }
        assertThat(FieldValidationSupport.scanSources(null)).isEmpty();
        assertThat(FieldValidationSupport.scanAllowsManual(null)).isTrue();
    }

    @Test
    void scanOnlyIsHonouredOnlyWhenTheBlobIsWellFormed() {
        // The one case where `manual: false` is obeyed — it has to be reachable, or the flag
        // would be decorative.
        Map<String, Object> wellFormed = Map.of("scan",
                Map.of("sources", List.of("BARCODE"), "manual", false));

        assertThat(FieldValidationSupport.scanSources(wellFormed)).containsExactly("BARCODE");
        assertThat(FieldValidationSupport.scanAllowsManual(wellFormed)).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scanObject(Map<String, Object> validation) {
        return (Map<String, Object>) validation.get(FieldValidationSupport.KEY_SCAN);
    }
}
