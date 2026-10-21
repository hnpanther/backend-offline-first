package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resolution chain class → sub-class → asset.
 *
 * <p>These are the cases where a mistake is invisible: a band that silently stays the class's,
 * an override that silently does nothing, a shared definition mutated so that one asset's range
 * leaks onto another. Each of those produces a reading that stores, reports and looks right.
 */
class AssetFieldResolverTest {

    // ===========================================================================================
    // Class chain
    // ===========================================================================================

    @Nested
    @DisplayName("flattenClassChain")
    class FlattenClassChain {

        @Test
        void inheritsParentFieldsWhenTheSubClassAddsNothing() {
            List<FieldDefinition> out = AssetFieldResolver.flattenClassChain(
                    List.of(field("ampere", 1), field("pressure", 2)), List.of(), 9L);

            assertThat(out).extracting(FieldDefinition::getKey).containsExactly("ampere", "pressure");
        }

        @Test
        @DisplayName("stamps the sub-class id on inherited fields — the client filters by it")
        void restampsInheritedFieldsWithTheChildClassId() {
            FieldDefinition parentField = field("ampere", 1);
            parentField.setClassId(1L);

            List<FieldDefinition> out = AssetFieldResolver.flattenClassChain(
                    List.of(parentField), List.of(), 9L);

            assertThat(out).singleElement()
                    .extracting(FieldDefinition::getClassId).isEqualTo(9L);
            // ...and the parent's own row is untouched, because the sheet may also carry it.
            assertThat(parentField.getClassId()).isEqualTo(1L);
        }

        @Test
        void childReplacesTheParentsFieldOfTheSameKey() {
            FieldDefinition parentAmpere = field("ampere", 1);
            parentAmpere.setValidation(bands(0.0, 100.0, null, null));
            FieldDefinition childAmpere = field("ampere", 1);
            childAmpere.setValidation(bands(18.0, 42.0, null, null));

            List<FieldDefinition> out = AssetFieldResolver.flattenClassChain(
                    List.of(parentAmpere), List.of(childAmpere), 9L);

            assertThat(out).singleElement()
                    .extracting(f -> warning(f).get("max")).isEqualTo(42.0);
        }

        @Test
        @DisplayName("matches keys case-insensitively, as the unique index does")
        void matchesKeysCaseInsensitively() {
            FieldDefinition parentField = field("Ampere", 1);
            FieldDefinition childField = field("ampere", 1);
            childField.setLabel("overridden");

            List<FieldDefinition> out = AssetFieldResolver.flattenClassChain(
                    List.of(parentField), List.of(childField), 9L);

            assertThat(out).singleElement().extracting(FieldDefinition::getLabel).isEqualTo("overridden");
        }

        @Test
        void dropsDeletedFieldsFromBothGenerations() {
            FieldDefinition deletedParent = field("gone", 1);
            deletedParent.setDeleted(true);
            FieldDefinition deletedChild = field("alsogone", 2);
            deletedChild.setDeleted(true);

            List<FieldDefinition> out = AssetFieldResolver.flattenClassChain(
                    List.of(deletedParent, field("kept", 3)), List.of(deletedChild), 9L);

            assertThat(out).extracting(FieldDefinition::getKey).containsExactly("kept");
        }

        @Test
        void ordersByDisplayOrderThenKey() {
            List<FieldDefinition> out = AssetFieldResolver.flattenClassChain(
                    List.of(field("zebra", 1), field("alpha", 1), field("first", 0)), List.of(), 9L);

            assertThat(out).extracting(FieldDefinition::getKey).containsExactly("first", "alpha", "zebra");
        }
    }

    // ===========================================================================================
    // Per-asset overrides
    // ===========================================================================================

    @Nested
    @DisplayName("applyAssetOverrides")
    class ApplyAssetOverrides {

        @Test
        void inheritReturnsTheClassFieldsUntouched() {
            List<FieldDefinition> classFields = List.of(field("ampere", 1));

            assertThat(AssetFieldResolver.applyAssetOverrides(
                    classFields, FieldSource.INHERIT, Map.of("ampere", bandOverride(1.0, 2.0)), 9L))
                    .isSameAs(classFields);
        }

        @Test
        void nullSourceIsTreatedAsInherit() {
            List<FieldDefinition> classFields = List.of(field("ampere", 1));

            assertThat(AssetFieldResolver.applyAssetOverrides(classFields, null, Map.of(), 9L))
                    .isSameAs(classFields);
        }

        @Test
        @DisplayName("RANGES_ONLY replaces the bands and nothing else")
        void rangesOnlyReplacesOnlyTheBands() {
            FieldDefinition ampere = field("ampere", 1);
            ampere.setLabel("Ampere");
            ampere.setUnit("A");
            ampere.setValidation(bands(0.0, 100.0, -10.0, 110.0));

            List<FieldDefinition> out = AssetFieldResolver.applyAssetOverrides(
                    List.of(ampere), FieldSource.RANGES_ONLY,
                    Map.of("ampere", bandOverride(18.0, 42.0)), 9L);

            FieldDefinition resolved = out.get(0);
            assertThat(warning(resolved)).containsEntry("min", 18.0).containsEntry("max", 42.0);
            assertThat(resolved.getLabel()).isEqualTo("Ampere");
            assertThat(resolved.getUnit()).isEqualTo("A");
        }

        @Test
        @DisplayName("an override naming only `warning` leaves the class's danger band standing")
        void aPartialOverrideDoesNotWipeTheOtherBand() {
            FieldDefinition ampere = field("ampere", 1);
            ampere.setValidation(bands(0.0, 100.0, -10.0, 110.0));

            List<FieldDefinition> out = AssetFieldResolver.applyAssetOverrides(
                    List.of(ampere), FieldSource.RANGES_ONLY,
                    Map.of("ampere", bandOverride(18.0, 42.0)), 9L);

            assertThat(danger(out.get(0))).containsEntry("min", -10.0).containsEntry("max", 110.0);
        }

        @Test
        @DisplayName("an explicit null band means 'unbounded here', not 'inherit'")
        void anExplicitNullBandRemovesIt() {
            FieldDefinition ampere = field("ampere", 1);
            ampere.setValidation(bands(0.0, 100.0, -10.0, 110.0));
            Map<String, Object> override = new LinkedHashMap<>();
            override.put(FieldValidationSupport.KEY_DANGER, null);

            List<FieldDefinition> out = AssetFieldResolver.applyAssetOverrides(
                    List.of(ampere), FieldSource.RANGES_ONLY, Map.of("ampere", override), 9L);

            assertThat(out.get(0).getValidation()).doesNotContainKey(FieldValidationSupport.KEY_DANGER);
            assertThat(warning(out.get(0))).containsEntry("max", 100.0);
        }

        @Test
        @DisplayName("select options survive a band override")
        void preservesOtherValidationKeys() {
            FieldDefinition status = field("status", 1);
            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put(FieldValidationSupport.KEY_OPTIONS, List.of("Running", "Idle"));
            status.setValidation(validation);

            List<FieldDefinition> out = AssetFieldResolver.applyAssetOverrides(
                    List.of(status), FieldSource.RANGES_ONLY,
                    Map.of("status", bandOverride(1.0, 2.0)), 9L);

            assertThat(out.get(0).getValidation())
                    .containsEntry(FieldValidationSupport.KEY_OPTIONS, List.of("Running", "Idle"));
        }

        @Test
        @DisplayName("RANGES_ONLY ignores an override for a field the class no longer has")
        void rangesOnlyDoesNotResurrectARemovedField() {
            List<FieldDefinition> out = AssetFieldResolver.applyAssetOverrides(
                    List.of(field("ampere", 1)), FieldSource.RANGES_ONLY,
                    Map.of("ampere", bandOverride(1.0, 2.0),
                           "pressure", bandOverride(3.0, 4.0)), 9L);

            assertThat(out).extracting(FieldDefinition::getKey).containsExactly("ampere");
        }

        @Test
        @DisplayName("RANGES_ONLY lets a field added to the class through")
        void rangesOnlyKeepsFollowingTheClass() {
            List<FieldDefinition> out = AssetFieldResolver.applyAssetOverrides(
                    List.of(field("ampere", 1), field("brandnew", 2)), FieldSource.RANGES_ONLY,
                    Map.of("ampere", bandOverride(1.0, 2.0)), 9L);

            assertThat(out).extracting(FieldDefinition::getKey).containsExactly("ampere", "brandnew");
        }

        @Test
        @DisplayName("FULL adds a field the class does not have")
        void fullAddsFields() {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("label", "Vibration");
            extra.put("dataType", "number");
            extra.put("unit", "mm/s");
            extra.put("sortOrder", 5);

            List<FieldDefinition> out = AssetFieldResolver.applyAssetOverrides(
                    List.of(field("ampere", 1)), FieldSource.FULL, Map.of("vibration", extra), 9L);

            assertThat(out).extracting(FieldDefinition::getKey).containsExactly("ampere", "vibration");
            FieldDefinition added = out.get(1);
            assertThat(added.getLabel()).isEqualTo("Vibration");
            assertThat(added.getUnit()).isEqualTo("mm/s");
            assertThat(added.getClassId()).isEqualTo(9L);
            // No id: it corresponds to no field_definitions row, and inventing one would collide.
            assertThat(added.getId()).isNull();
        }

        @Test
        @DisplayName("FULL removes a class field marked removed")
        void fullRemovesFields() {
            List<FieldDefinition> out = AssetFieldResolver.applyAssetOverrides(
                    List.of(field("ampere", 1), field("pressure", 2)), FieldSource.FULL,
                    Map.of("pressure", Map.of(AssetFieldResolver.OVERRIDE_REMOVED, true)), 9L);

            assertThat(out).extracting(FieldDefinition::getKey).containsExactly("ampere");
        }

        @Test
        @DisplayName("RANGES_ONLY ignores `removed` — only FULL owns the field list")
        void rangesOnlyIgnoresRemoved() {
            List<FieldDefinition> out = AssetFieldResolver.applyAssetOverrides(
                    List.of(field("ampere", 1)), FieldSource.RANGES_ONLY,
                    Map.of("ampere", Map.of(AssetFieldResolver.OVERRIDE_REMOVED, true)), 9L);

            assertThat(out).extracting(FieldDefinition::getKey).containsExactly("ampere");
        }

        @Test
        void fullCanRelabelRetypeAndReorder() {
            FieldDefinition ampere = field("ampere", 1);
            ampere.setLabel("Ampere");
            Map<String, Object> override = new LinkedHashMap<>();
            override.put("label", "Current draw");
            override.put("unit", "mA");
            override.put("required", true);
            override.put("sortOrder", 7);

            List<FieldDefinition> out = AssetFieldResolver.applyAssetOverrides(
                    List.of(ampere), FieldSource.FULL, Map.of("ampere", override), 9L);

            FieldDefinition resolved = out.get(0);
            assertThat(resolved.getLabel()).isEqualTo("Current draw");
            assertThat(resolved.getUnit()).isEqualTo("mA");
            assertThat(resolved.isRequired()).isTrue();
            assertThat(resolved.getOrder()).isEqualTo(7);
        }

        @Test
        @DisplayName("never mutates the definitions it is given — they are shared across assets")
        void doesNotMutateTheInput() {
            FieldDefinition shared = field("ampere", 1);
            shared.setValidation(bands(0.0, 100.0, null, null));
            Map<String, Object> originalValidation = shared.getValidation();

            AssetFieldResolver.applyAssetOverrides(List.of(shared), FieldSource.RANGES_ONLY,
                    Map.of("ampere", bandOverride(18.0, 42.0)), 9L);

            assertThat(shared.getValidation()).isSameAs(originalValidation);
            assertThat(warning(shared)).containsEntry("max", 100.0);
        }

        @Test
        @DisplayName("two assets of one class resolve independently")
        void twoAssetsOfOneClassDoNotLeakIntoEachOther() {
            FieldDefinition shared = field("ampere", 1);
            shared.setValidation(bands(0.0, 100.0, null, null));
            List<FieldDefinition> classFields = List.of(shared);

            List<FieldDefinition> first = AssetFieldResolver.applyAssetOverrides(
                    classFields, FieldSource.RANGES_ONLY, Map.of("ampere", bandOverride(10.0, 20.0)), 9L);
            List<FieldDefinition> second = AssetFieldResolver.applyAssetOverrides(
                    classFields, FieldSource.RANGES_ONLY, Map.of("ampere", bandOverride(30.0, 40.0)), 9L);

            assertThat(warning(first.get(0))).containsEntry("max", 20.0);
            assertThat(warning(second.get(0))).containsEntry("max", 40.0);
        }

        @Test
        void ignoresAnOverrideWhoseValueIsNotAnObject() {
            Map<String, Object> malformed = new LinkedHashMap<>();
            malformed.put("ampere", "not an object");

            List<FieldDefinition> out = AssetFieldResolver.applyAssetOverrides(
                    List.of(field("ampere", 1)), FieldSource.RANGES_ONLY, malformed, 9L);

            assertThat(out).singleElement().extracting(FieldDefinition::getKey).isEqualTo("ampere");
        }
    }

    // ===========================================================================================
    // The frozen delta
    // ===========================================================================================

    @Nested
    @DisplayName("snapshotEntry and applyFrozenDelta round-trip")
    class Delta {

        @Test
        void inheritProducesNoDelta() {
            assertThat(AssetFieldResolver.snapshotEntry(List.of(field("ampere", 1)),
                    FieldSource.INHERIT, Map.of("ampere", bandOverride(1.0, 2.0)), 9L)).isEmpty();
        }

        @Test
        @DisplayName("a field whose bands match the class contributes nothing")
        void onlyDifferencesAreCarried() {
            FieldDefinition ampere = field("ampere", 1);
            ampere.setValidation(bands(0.0, 100.0, null, null));
            FieldDefinition pressure = field("pressure", 2);
            pressure.setValidation(bands(0.0, 50.0, null, null));

            Map<String, Object> delta = AssetFieldResolver.snapshotEntry(
                    List.of(ampere, pressure), FieldSource.RANGES_ONLY,
                    Map.of("ampere", bandOverride(0.0, 100.0),      // same as the class
                           "pressure", bandOverride(5.0, 40.0)),     // different
                    9L);

            assertThat(fieldsOf(delta)).containsOnlyKeys("pressure");
            assertThat(delta).containsEntry(AssetFieldResolver.SNAPSHOT_SOURCE, "RANGES_ONLY");
        }

        @Test
        @DisplayName("the delta re-applies to produce exactly what the asset resolved to")
        void roundTripsThroughTheSnapshot() {
            FieldDefinition ampere = field("ampere", 1);
            ampere.setValidation(bands(0.0, 100.0, -10.0, 110.0));
            List<FieldDefinition> classFields = List.of(ampere);
            Map<String, Object> overrides = Map.of("ampere", bandOverride(18.0, 42.0));

            List<FieldDefinition> live = AssetFieldResolver.applyAssetOverrides(
                    classFields, FieldSource.RANGES_ONLY, overrides, 9L);
            Map<String, Object> snapshot = AssetFieldResolver.snapshotEntry(
                    classFields, FieldSource.RANGES_ONLY, overrides, 9L);
            List<FieldDefinition> fromSnapshot = AssetFieldResolver.applyFrozenDelta(classFields, snapshot);

            assertThat(fromSnapshot.get(0).getValidation()).isEqualTo(live.get(0).getValidation());
        }

        @Test
        @DisplayName("the delta key is the definition's key, not the override's spelling")
        void deltaUsesTheResolvedKey() {
            FieldDefinition ampere = field("Ampere", 1);
            ampere.setValidation(bands(0.0, 100.0, null, null));

            Map<String, Object> delta = AssetFieldResolver.snapshotEntry(
                    List.of(ampere), FieldSource.RANGES_ONLY,
                    Map.of("ampere", bandOverride(18.0, 42.0)), 9L);

            assertThat(fieldsOf(delta)).containsOnlyKeys("Ampere");
        }

        @Test
        @DisplayName("FULL carries its whole override map, so a removal survives the freeze")
        void fullSnapshotsTheWholeOverrideMap() {
            FieldDefinition ampere = field("ampere", 1);
            FieldDefinition vibration = field("vibration", 2);
            Map<String, Object> overrides = new LinkedHashMap<>();
            overrides.put("vibration", Map.of(AssetFieldResolver.OVERRIDE_REMOVED, true));

            Map<String, Object> snapshot = AssetFieldResolver.snapshotEntry(
                    List.of(ampere, vibration), FieldSource.FULL, overrides, 9L);

            assertThat(snapshot).containsEntry(AssetFieldResolver.SNAPSHOT_SOURCE, "FULL");
            assertThat(AssetFieldResolver.applyFrozenDelta(List.of(ampere, vibration), snapshot))
                    .extracting(FieldDefinition::getKey).containsExactly("ampere");
        }

        @Test
        void applyFrozenDeltaIsANoOpForAnEmptyDelta() {
            List<FieldDefinition> classFields = List.of(field("ampere", 1));

            assertThat(AssetFieldResolver.applyFrozenDelta(classFields, Map.of())).isSameAs(classFields);
            assertThat(AssetFieldResolver.applyFrozenDelta(classFields, null)).isSameAs(classFields);
        }

        @Test
        @DisplayName("a frozen key the snapshot does not have is ignored, not added")
        void unknownKeysInAFrozenDeltaAreIgnored() {
            Map<String, Object> snapshot = Map.of(
                    AssetFieldResolver.SNAPSHOT_SOURCE, "RANGES_ONLY",
                    AssetFieldResolver.SNAPSHOT_FIELDS, Map.of("vanished", bandOverride(1.0, 2.0)));

            assertThat(AssetFieldResolver.applyFrozenDelta(List.of(field("ampere", 1)), snapshot))
                    .extracting(FieldDefinition::getKey).containsExactly("ampere");
        }

        @Test
        @DisplayName("a frozen entry with no source is replayed as bands only, never structurally")
        void aMissingSourceFallsBackToBandsOnly() {
            Map<String, Object> snapshot = Map.of(AssetFieldResolver.SNAPSHOT_FIELDS,
                    Map.of("ampere", Map.of(AssetFieldResolver.OVERRIDE_REMOVED, true)));

            assertThat(AssetFieldResolver.applyFrozenDelta(List.of(field("ampere", 1)), snapshot))
                    .extracting(FieldDefinition::getKey)
                    .as("an unreadable source must not be allowed to delete a field")
                    .containsExactly("ampere");
        }
    }

    // ===========================================================================================
    // Helpers
    // ===========================================================================================

    private static FieldDefinition field(String key, int order) {
        FieldDefinition fd = new FieldDefinition();
        fd.setKey(key);
        fd.setOrder(order);
        fd.setDataType("number");
        fd.setClassId(1L);
        return fd;
    }

    private static Map<String, Object> bands(Double warnMin, Double warnMax, Double dangerMin, Double dangerMax) {
        Map<String, Object> validation = new LinkedHashMap<>();
        if (warnMin != null || warnMax != null) {
            validation.put(FieldValidationSupport.KEY_WARNING, range(warnMin, warnMax));
        }
        if (dangerMin != null || dangerMax != null) {
            validation.put(FieldValidationSupport.KEY_DANGER, range(dangerMin, dangerMax));
        }
        return validation;
    }

    private static Map<String, Object> bandOverride(Double min, Double max) {
        Map<String, Object> override = new LinkedHashMap<>();
        override.put(FieldValidationSupport.KEY_WARNING, range(min, max));
        return override;
    }

    private static Map<String, Object> range(Double min, Double max) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (min != null) r.put("min", min);
        if (max != null) r.put("max", max);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(Map<String, Object> snapshotEntry) {
        return (Map<String, Object>) snapshotEntry.get(AssetFieldResolver.SNAPSHOT_FIELDS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> warning(FieldDefinition fd) {
        return (Map<String, Object>) fd.getValidation().get(FieldValidationSupport.KEY_WARNING);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> danger(FieldDefinition fd) {
        return (Map<String, Object>) fd.getValidation().get(FieldValidationSupport.KEY_DANGER);
    }
}
