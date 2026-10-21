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

    private static Map<String, Object> band(Double min, Double max) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put(FieldValidationSupport.KEY_MIN, min);
        range.put(FieldValidationSupport.KEY_MAX, max);
        return Map.of(FieldValidationSupport.KEY_WARNING, range);
    }
}
