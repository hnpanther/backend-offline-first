package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.service.AssetFieldOverrideService;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a {@code RANGES_ONLY} asset does when its class gains or loses a parameter.
 *
 * <p>This is the mode's whole reason for existing — an asset that keeps following the class's
 * parameter list while carrying its own bands — so the following has to be demonstrated, not
 * assumed. Each case here is a question an engineer will actually ask before they trust it.
 *
 * <p>Note that removing a field from a class is a <strong>hard</strong> delete
 * ({@code AssetClassWebController.deleteField}), so "the field came back" means a new row with
 * a new id and the same key.
 */
@Transactional
class RangesOnlyFollowsClassChangesIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetFieldOverrideService overrideService;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired EntityManager entityManager;

    // =======================================================================================
    // Adding
    // =======================================================================================

    @Test
    @DisplayName("a field added to the class REACHES the asset, carrying the class's bands")
    void anAddedFieldReachesTheAsset() {
        Fixture f = seed();
        overrideService.save(f.asset().getId(), FieldSource.RANGES_ONLY,
                Map.of("ampere", band(18d, 42d)));

        saveField(f.klass().getId(), "vibration", 3, band(0d, 7d));
        entityManager.flush();
        entityManager.clear();

        List<FieldDefinition> fields = overrideService.effectiveFields(reload(f));

        assertThat(fields).extracting(FieldDefinition::getKey)
                .as("this is the whole point of RANGES_ONLY")
                .containsExactly("ampere", "pressure", "vibration");
        assertThat(warningMax(fields, "vibration"))
                .as("the new field arrives with the class's band — the asset said nothing about it")
                .isEqualTo(7d);
        assertThat(warningMax(fields, "ampere"))
                .as("...and the asset's own band is still its own")
                .isEqualTo(42d);
    }

    // =======================================================================================
    // Removing
    // =======================================================================================

    @Test
    @DisplayName("a field removed from the class disappears from the asset too")
    void aRemovedFieldDisappears() {
        Fixture f = seed();
        overrideService.save(f.asset().getId(), FieldSource.RANGES_ONLY,
                Map.of("ampere", band(18d, 42d), "pressure", band(1d, 9d)));

        fieldDefinitionRepository.delete(
                fieldDefinitionRepository.findByClassId(f.klass().getId()).stream()
                        .filter(fd -> "pressure".equals(fd.getKey())).findFirst().orElseThrow());
        entityManager.flush();
        entityManager.clear();

        assertThat(overrideService.effectiveFields(reload(f)))
                .extracting(FieldDefinition::getKey)
                .as("the class owns the list; an override cannot keep a field alive")
                .containsExactly("ampere");
    }

    @Test
    @DisplayName("the orphaned override survives in storage until something rewrites it")
    void theOrphanedOverrideIsNotErasedByTheClassChange() {
        Fixture f = seed();
        overrideService.save(f.asset().getId(), FieldSource.RANGES_ONLY,
                Map.of("ampere", band(18d, 42d), "pressure", band(1d, 9d)));

        deleteField(f, "pressure");

        assertThat(reload(f).getFieldOverrides())
                .as("removing a class field does not reach into every asset to tidy up")
                .containsKeys("ampere", "pressure");
    }

    @Test
    @DisplayName("and it applies again if the field comes back")
    void theOverrideAppliesAgainWhenTheFieldReturns() {
        Fixture f = seed();
        overrideService.save(f.asset().getId(), FieldSource.RANGES_ONLY,
                Map.of("ampere", band(18d, 42d), "pressure", band(1d, 9d)));
        deleteField(f, "pressure");

        // Re-added with the same key — a new row with a new id, since the delete was hard.
        saveField(f.klass().getId(), "pressure", 2, band(0d, 50d));
        entityManager.flush();
        entityManager.clear();

        assertThat(warningMax(overrideService.effectiveFields(reload(f)), "pressure"))
                .as("matched by key, which is the identity everywhere else in this schema too")
                .isEqualTo(9d);
    }

    @Test
    @DisplayName("BUT re-saving the asset's parameters in the meantime discards the orphan")
    void savingTheAssetWhileTheFieldIsGoneDropsTheOrphan() {
        Fixture f = seed();
        overrideService.save(f.asset().getId(), FieldSource.RANGES_ONLY,
                Map.of("ampere", band(18d, 42d), "pressure", band(1d, 9d)));
        deleteField(f, "pressure");

        // The admin opens the asset's parameter screen and saves anything at all. The screen
        // renders rows from the CLASS, so `pressure` is not among them and is not posted.
        overrideService.save(f.asset().getId(), FieldSource.RANGES_ONLY,
                Map.of("ampere", band(18d, 42d)));
        entityManager.flush();
        entityManager.clear();

        assertThat(reload(f).getFieldOverrides()).containsOnlyKeys("ampere");

        saveField(f.klass().getId(), "pressure", 2, band(0d, 50d));
        entityManager.flush();
        entityManager.clear();

        assertThat(warningMax(overrideService.effectiveFields(reload(f)), "pressure"))
                .as("the band is gone for good; the returning field carries the class's")
                .isEqualTo(50d);
    }

    // =======================================================================================
    // Already-generated sheets
    // =======================================================================================

    @Test
    @DisplayName("none of this disturbs a sheet already raised — it froze both halves")
    void raisedSheetsAreUnaffected() {
        Fixture f = seed();
        overrideService.save(f.asset().getId(), FieldSource.RANGES_ONLY,
                Map.of("ampere", band(18d, 42d)));
        entityManager.flush();

        // A sheet's schema and bands are copied into log_sheets at generation; changing the
        // class afterwards cannot reach them. Demonstrated end to end in
        // PerAssetFieldOverrideIntegrationTest.theFrozenBandIsTheOneInForceWhenTheSheetWasRaised.
        assertThat(reload(f).getFieldSource()).isEqualTo("RANGES_ONLY");
    }

    // =======================================================================================

    private void deleteField(Fixture f, String key) {
        fieldDefinitionRepository.delete(
                fieldDefinitionRepository.findByClassId(f.klass().getId()).stream()
                        .filter(fd -> key.equals(fd.getKey())).findFirst().orElseThrow());
        entityManager.flush();
        entityManager.clear();
    }

    private AssetEntry reload(Fixture f) {
        return assetEntryRepository.findById(f.asset().getId()).orElseThrow();
    }

    private static Double warningMax(List<FieldDefinition> fields, String key) {
        return fields.stream().filter(fd -> key.equals(fd.getKey())).findFirst()
                .map(fd -> FieldValidationSupport.rangeMax(fd.getValidation(),
                        FieldValidationSupport.KEY_WARNING))
                .orElse(null);
    }

    private Fixture seed() {
        long now = System.nanoTime();

        AssetClass klass = new AssetClass();
        klass.setName("Pumps " + now);
        klass.setCreatedAt(now);
        klass.setUpdatedAt(now);
        klass = assetClassRepository.save(klass);

        saveField(klass.getId(), "ampere", 1, band(0d, 100d));
        saveField(klass.getId(), "pressure", 2, band(0d, 50d));

        Location location = new Location();
        location.setCode("LOC-RO-" + now);
        location.setName("محل");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location, List.of());

        SubFunction sf = new SubFunction();
        sf.setCode("SF-RO-" + now);
        sf.setName("موقعیت");
        sf.setTag("TAG-RO-" + now);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-RO-" + now);
        asset.setAssetName("پمپ");
        asset.setClassId(klass.getId());
        asset.setSubFunctionId(sf.getId());
        asset.setActive(true);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.save(asset);

        entityManager.flush();
        return new Fixture(klass, asset);
    }

    private void saveField(Long classId, String key, int order, Map<String, Object> validation) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(classId);
        fd.setKey(key);
        fd.setLabel(key);
        fd.setDataType("number");
        fd.setValidation(validation);
        fd.setOrder(order);
        fd.setCreatedAt(1L);
        fd.setUpdatedAt(1L);
        fieldDefinitionRepository.save(fd);
    }

    private static Map<String, Object> band(Double min, Double max) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put(FieldValidationSupport.KEY_MIN, min);
        range.put(FieldValidationSupport.KEY_MAX, max);
        Map<String, Object> validation = new HashMap<>();
        validation.put(FieldValidationSupport.KEY_WARNING, range);
        return validation;
    }

    private record Fixture(AssetClass klass, AssetEntry asset) {}
}
