package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What may be written into an asset's band layer.
 *
 * <p>Every rejection here guards against the same failure: a malformed band does not throw at
 * read time, it resolves to "no band" or to a band nothing can satisfy, and a dangerous reading
 * is then recorded as OK — or a safe one as DANGER. Neither is visible without going back to the
 * configuration and reading it carefully, which nobody does until something has gone wrong.
 */
@ExtendWith(MockitoExtension.class)
class AssetFieldOverrideServiceTest {

    @Mock AssetEntryRepository assetEntryRepository;
    @Mock LogSheetFieldDefinitionsService fieldDefinitionsService;
    @InjectMocks AssetFieldOverrideService service;

    private AssetEntry asset;

    @BeforeEach
    void setUp() {
        asset = new AssetEntry();
        asset.setId(7L);
        asset.setClassId(3L);
        asset.setFieldSource(FieldSource.INHERIT.name());

        lenient().when(assetEntryRepository.findById(7L)).thenReturn(Optional.of(asset));
        lenient().when(assetEntryRepository.save(any(AssetEntry.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(fieldDefinitionsService.flattenedByClass(List.of(3L)))
                .thenReturn(Map.of(3L, List.of(numberField("ampere"), textField("note"))));
    }

    // =======================================================================================
    // Storing
    // =======================================================================================

    @Test
    void storesAValidRangesOnlyOverride() {
        AssetEntry saved = service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", band(18d, 42d)));

        assertThat(saved.getFieldSource()).isEqualTo("RANGES_ONLY");
        assertThat(saved.getFieldOverrides()).containsOnlyKeys("ampere");
    }

    @Test
    @DisplayName("switching to INHERIT discards the overrides, because dormant ones are a trap")
    void inheritClearsTheOverrides() {
        asset.setFieldSource(FieldSource.RANGES_ONLY.name());
        asset.setFieldOverrides(new LinkedHashMap<>(Map.of("ampere", band(18d, 42d))));

        AssetEntry saved = service.save(7L, FieldSource.INHERIT, Map.of("ampere", band(1d, 2d)));

        assertThat(saved.getFieldSource()).isEqualTo("INHERIT");
        assertThat(saved.getFieldOverrides())
                .as("a later reader that forgets to check field_source must not find a band to apply")
                .isNull();
    }

    @Test
    @DisplayName("a mode whose overrides all fell away is stored as INHERIT, not as an empty map")
    void anEmptyResultFallsBackToInherit() {
        AssetEntry saved = service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("gone", band(1d, 2d)));   // not a field of this class

        assertThat(saved.getFieldSource()).isEqualTo("INHERIT");
        assertThat(saved.getFieldOverrides()).isNull();
    }

    @Test
    void missingAssetIsReported() {
        when(assetEntryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(99L, FieldSource.RANGES_ONLY, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Asset not found");
    }

    // =======================================================================================
    // What is refused
    // =======================================================================================

    @Test
    @DisplayName("a minimum above its maximum is refused, not stored")
    void refusesAnInvertedRange() {
        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", band(90d, 10d))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be greater than");

        verify(assetEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("NaN is refused — every comparison against it is false, so the band never breaches")
    void refusesNaN() {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put(FieldValidationSupport.KEY_MIN, Double.NaN);
        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of(FieldValidationSupport.KEY_WARNING, range))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }

    @Test
    void refusesInfinity() {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put(FieldValidationSupport.KEY_MAX, Double.POSITIVE_INFINITY);
        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of(FieldValidationSupport.KEY_WARNING, range))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }

    @Test
    void refusesANonNumericBound() {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put(FieldValidationSupport.KEY_MIN, "ten-ish");
        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of(FieldValidationSupport.KEY_WARNING, range))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a number");
    }

    @Test
    @DisplayName("a band on a text field is refused — nothing would ever evaluate it")
    void refusesABandOnANonNumericField() {
        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("note", band(1d, 2d))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ranges apply to numbers only");
    }

    @Test
    @DisplayName("RANGES_ONLY may not smuggle in a structural change")
    void refusesStructuralKeysUnderRangesOnly() {
        assertThatThrownBy(() -> service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of("dataType", "text"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported override key");
    }

    @Test
    void refusesAnUnknownKeyEvenUnderFull() {
        assertThatThrownBy(() -> service.save(7L, FieldSource.FULL,
                Map.of("ampere", Map.of("somethingElse", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported override key");
    }

    // =======================================================================================
    // What is dropped rather than refused
    // =======================================================================================

    @Test
    @DisplayName("RANGES_ONLY silently drops a leftover for a field the class has removed")
    void dropsALeftoverOverrideUnderRangesOnly() {
        AssetEntry saved = service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", band(18d, 42d), "vanished", band(1d, 2d)));

        assertThat(saved.getFieldOverrides())
                .as("the class owns the field list here; a removed field is a leftover, not an error")
                .containsOnlyKeys("ampere");
    }

    @Test
    @DisplayName("FULL keeps a key the class does not have — that is how a field is added")
    void keepsAnUnknownKeyUnderFull() {
        Map<String, Object> added = new LinkedHashMap<>();
        added.put("label", "Vibration");
        added.put("dataType", "number");

        AssetEntry saved = service.save(7L, FieldSource.FULL, Map.of("vibration", added));

        assertThat(saved.getFieldOverrides()).containsOnlyKeys("vibration");
    }

    @Test
    @DisplayName("a band with neither bound is dropped — a half-filled form is not a rule")
    void dropsAnEmptyBand() {
        AssetEntry saved = service.save(7L, FieldSource.RANGES_ONLY,
                Map.of("ampere", Map.of(FieldValidationSupport.KEY_WARNING, Map.of())));

        assertThat(saved.getFieldSource()).isEqualTo("RANGES_ONLY");
        @SuppressWarnings("unchecked")
        Map<String, Object> ampere = (Map<String, Object>) saved.getFieldOverrides().get("ampere");
        assertThat(ampere).containsEntry(FieldValidationSupport.KEY_WARNING, null);
    }

    @Test
    @DisplayName("an explicit null band is kept — it means 'unbounded here', which is a decision")
    void keepsAnExplicitNullBand() {
        Map<String, Object> override = new LinkedHashMap<>();
        override.put(FieldValidationSupport.KEY_DANGER, null);

        AssetEntry saved = service.save(7L, FieldSource.RANGES_ONLY, Map.of("ampere", override));

        @SuppressWarnings("unchecked")
        Map<String, Object> ampere = (Map<String, Object>) saved.getFieldOverrides().get("ampere");
        assertThat(ampere).containsKey(FieldValidationSupport.KEY_DANGER);
        assertThat(ampere.get(FieldValidationSupport.KEY_DANGER)).isNull();
    }

    // =======================================================================================
    // FULL — adding and removing a parameter
    // =======================================================================================

    @Test
    @DisplayName("a field key the asset invents is validated as the identifier it becomes")
    void refusesAnInvalidAddedKey() {
        Map<String, Object> added = new LinkedHashMap<>();
        added.put("label", "لرزش");
        added.put("dataType", "number");

        assertThatThrownBy(() -> service.save(7L, FieldSource.FULL, Map.of("vibration level", added)))
                .as("a space in a key strands the reading in form_data for one asset only — "
                        + "the hardest kind of breakage to notice")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only English letters");

        assertThatThrownBy(() -> service.save(7L, FieldSource.FULL, Map.of("لرزش", added)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only English letters");
    }

    @Test
    @DisplayName("an added field must name a type the fill controls can render")
    void refusesAnUnknownDataTypeOnAnAddedField() {
        Map<String, Object> added = new LinkedHashMap<>();
        added.put("dataType", "quaternion");

        assertThatThrownBy(() -> service.save(7L, FieldSource.FULL, Map.of("vibration", added)))
                .as("an unknown type falls through to a plain text input on the device, silently")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown type");
    }

    @Test
    @DisplayName("a valid added field is stored with its own definition")
    void storesAnAddedField() {
        Map<String, Object> added = new LinkedHashMap<>();
        added.put("label", "لرزش");
        added.put("unit", "mm/s");
        added.put("dataType", "number");
        added.put(FieldValidationSupport.KEY_WARNING, rangeOf(0d, 7d));

        AssetEntry saved = service.save(7L, FieldSource.FULL, Map.of("vibration", added));

        assertThat(saved.getFieldSource()).isEqualTo("FULL");
        assertThat(saved.getFieldOverrides()).containsKey("vibration");
    }

    @Test
    @DisplayName("an added key may reuse a class field's name only by replacing it, not twice")
    void refusesTheSameAddedKeyTwice() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("vibration", Map.of("dataType", "number"));
        raw.put("VIBRATION", Map.of("dataType", "number"));

        assertThatThrownBy(() -> service.save(7L, FieldSource.FULL, raw))
                .as("keys are unique per class case-insensitively; per asset must match")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
    }

    @Test
    @DisplayName("a class field the asset drops is stored as a removal, not as an absence")
    void storesARemoval() {
        AssetEntry saved = service.save(7L, FieldSource.FULL,
                Map.of("ampere", Map.of("removed", true)));

        assertThat(saved.getFieldOverrides()).containsKey("ampere");
        assertThat(service.effectiveFields(saved))
                .as("an absence would simply mean 'unchanged'; the removal has to be explicit")
                .extracting(FieldDefinition::getKey).containsExactly("note");
    }

    /**
     * The second removal used to undo the first.
     *
     * <p>The editor built its rows from the <strong>resolved</strong> schema, which by
     * definition no longer contains what the asset removed — so the row disappeared from the
     * page. The form is the complete statement of the override, and a save posts exactly the
     * rows the page rendered: the next save therefore never mentioned the first field, and it
     * came back. An administrator removing two parameters ended up with one removed and the
     * other restored, with nothing on screen to explain it.
     *
     * <p>It is also the only way back. Without a ticked row there is no control to untick, so a
     * field dropped by mistake could be recovered only by resetting the asset to «از کلاس» and
     * re-entering every band it had.
     */
    @Test
    @DisplayName("a field the asset already removed still has a row, ticked")
    void aRemovedFieldKeepsItsRowSoTheNextSaveDoesNotResurrectIt() {
        asset.setFieldSource(FieldSource.FULL.name());
        asset.setFieldOverrides(new LinkedHashMap<>(Map.of("ampere", Map.of("removed", true))));

        var rows = service.editorRows(asset);

        assertThat(rows).extracting(r -> r.fieldKey())
                .as("the class's own order, with nothing missing")
                .containsExactly("ampere", "note");
        assertThat(rows).filteredOn(r -> "ampere".equals(r.fieldKey()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.removed()).as("rendered ticked, which is what survives the save").isTrue();
                    assertThat(r.custom()).as("it belongs to the class; the asset only dropped it").isFalse();
                    assertThat(r.differsFromClass())
                            .as("a dropped field follows the class, so it is not 'customised'")
                            .isFalse();
                });
        assertThat(rows).filteredOn(r -> "note".equals(r.fieldKey()))
                .singleElement()
                .satisfies(r -> assertThat(r.removed()).isFalse());
    }

    @Test
    @DisplayName("removing a second field does not bring the first one back")
    void twoRemovalsSurviveEachOther() {
        // First removal, exactly as the page posts it.
        AssetEntry afterFirst = service.save(7L, FieldSource.FULL,
                Map.of("ampere", Map.of("removed", true)));

        // The page re-renders. Whatever it renders is what the next save will post, so build
        // the second form from the rows themselves rather than from what we happen to know.
        Map<String, Object> secondPost = new LinkedHashMap<>();
        for (var row : service.editorRows(afterFirst)) {
            boolean ticked = row.removed() || "note".equals(row.fieldKey());
            if (ticked) {
                secondPost.put(row.fieldKey(), Map.of("removed", true));
            }
        }
        AssetEntry afterSecond = service.save(7L, FieldSource.FULL, secondPost);

        assertThat(afterSecond.getFieldOverrides())
                .as("both removals, not just the one the admin just ticked")
                .containsOnlyKeys("ampere", "note");
        assertThat(service.effectiveFields(afterSecond)).isEmpty();
    }

    @Test
    @DisplayName("unticking a removed field brings it back on the class's own terms")
    void untickingARemovalRestoresTheField() {
        AssetEntry removed = service.save(7L, FieldSource.FULL,
                Map.of("ampere", Map.of("removed", true)));
        assertThat(service.effectiveFields(removed)).extracting(FieldDefinition::getKey)
                .containsExactly("note");

        // The admin unticks it: the row is posted with nothing to say, so it carries no override.
        AssetEntry restored = service.save(7L, FieldSource.FULL, Map.of());

        assertThat(service.effectiveFields(restored)).extracting(FieldDefinition::getKey)
                .containsExactly("ampere", "note");
    }

    @Test
    @DisplayName("an added field appears in the editor rows, marked as the asset's own")
    void addedFieldsComeBackAsCustomRows() {
        Map<String, Object> added = new LinkedHashMap<>();
        added.put("label", "لرزش");
        added.put("dataType", "number");
        asset.setFieldSource(FieldSource.FULL.name());
        asset.setFieldOverrides(new LinkedHashMap<>(Map.of("vibration", added)));

        var rows = service.editorRows(asset);

        assertThat(rows).extracting(r -> r.fieldKey()).contains("vibration");
        assertThat(rows).filteredOn(r -> "vibration".equals(r.fieldKey()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.custom()).isTrue();
                    assertThat(r.label()).isEqualTo("لرزش");
                });
        assertThat(rows).filteredOn(r -> "ampere".equals(r.fieldKey()))
                .singleElement()
                .satisfies(r -> assertThat(r.custom())
                        .as("a class field is not the asset's own, even in FULL")
                        .isFalse());
    }

    // =======================================================================================
    // Reading
    // =======================================================================================

    @Test
    @DisplayName("the editor opens pre-filled from the class, never blank")
    void effectiveFieldsStartFromTheClass() {
        List<FieldDefinition> fields = service.effectiveFields(asset);

        assertThat(fields).extracting(FieldDefinition::getKey).containsExactly("ampere", "note");
    }

    @Test
    void effectiveFieldsShowWhatTheAssetAlreadyOverrides() {
        asset.setFieldSource(FieldSource.RANGES_ONLY.name());
        asset.setFieldOverrides(new LinkedHashMap<>(Map.of("ampere", band(18d, 42d))));

        List<FieldDefinition> fields = service.effectiveFields(asset);

        @SuppressWarnings("unchecked")
        Map<String, Object> warning = (Map<String, Object>)
                fields.get(0).getValidation().get(FieldValidationSupport.KEY_WARNING);
        assertThat(warning).containsEntry(FieldValidationSupport.KEY_MAX, 42d);
    }

    @Test
    void anAssetWithNoClassHasNoFields() {
        asset.setClassId(null);

        assertThat(service.effectiveFields(asset)).isEmpty();
        assertThat(service.classFields(asset)).isEmpty();
    }

    // =======================================================================================

    private static FieldDefinition numberField(String key) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(3L);
        fd.setKey(key);
        fd.setDataType("number");
        fd.setOrder(1);
        return fd;
    }

    private static FieldDefinition textField(String key) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(3L);
        fd.setKey(key);
        fd.setDataType("text");
        fd.setOrder(2);
        return fd;
    }

    private static Map<String, Object> rangeOf(Double min, Double max) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put(FieldValidationSupport.KEY_MIN, min);
        range.put(FieldValidationSupport.KEY_MAX, max);
        return range;
    }

    private static Map<String, Object> band(Double min, Double max) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put(FieldValidationSupport.KEY_MIN, min);
        range.put(FieldValidationSupport.KEY_MAX, max);
        return Map.of(FieldValidationSupport.KEY_WARNING, range);
    }
}
