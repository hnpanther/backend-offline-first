package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reporting scope is wider than registry scope, and that difference is the whole point.
 *
 * <p>An asset is reportable when it sits in a location the unit owns <em>or</em> when it
 * appears on a log sheet the unit is responsible for. Before this rule existed, reports
 * were filtered purely by location ownership while log sheets are reachable through
 * {@code log_sheets.operational_unit_id} — so a supervisor could be required to fill a
 * sheet and then be denied the readings they had just recorded. Where {@code location_units}
 * is not populated at all, that hid every reading from every unit-scoped user.
 *
 * <p>The fixture deliberately puts the asset in unit B's location while making unit A
 * responsible for the log sheet, which is exactly the configuration a template with
 * {@code restrict_scope_to_unit = false} produces.
 */
@Transactional
class AssetReportingScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired AssetHierarchyService hierarchyService;

    @Test
    void assetOnAnAccessibleLogSheetIsReportableEvenThoughAnotherUnitOwnsItsLocation() {
        Fixture f = seed();

        // Registry scope: unit A does not own the location, so it must not appear there.
        assertThat(assetEntryRepository.findVisibleByIdAndUnitIds(
                Set.of(f.unitA().getId()), f.assetOwnedByB().getId()))
                .as("registry scope stays location-owned")
                .isEmpty();

        // Reporting scope: unit A filled the sheet, so the readings are theirs to see.
        assertThat(assetEntryRepository.findReportableByIdAndUnitIds(
                Set.of(f.unitA().getId()), f.assetOwnedByB().getId()))
                .map(AssetEntry::getId)
                .contains(f.assetOwnedByB().getId());
    }

    @Test
    void ownUnitSupervisorCanReportOnTheirOwnSheetWhenNoLocationIsMappedAtAll() {
        // The live failure mode: location_units empty, so nothing is location-owned and
        // every unit-scoped user saw zero readings — including for their own log sheets.
        Fixture f = seed();
        hierarchyService.replaceLocationUnits(f.locB().getId(), List.of());

        assertThat(assetEntryRepository.findVisibleByIdAndUnitIds(
                Set.of(f.unitA().getId()), f.assetOwnedByB().getId())).isEmpty();
        assertThat(assetEntryRepository.findVisibleByIdAndUnitIds(
                Set.of(f.unitB().getId()), f.assetOwnedByB().getId())).isEmpty();

        assertThat(assetEntryRepository.findReportableByIdAndUnitIds(
                Set.of(f.unitA().getId()), f.assetOwnedByB().getId()))
                .as("responsibility through the log sheet still grants the report")
                .isPresent();
    }

    @Test
    void aUnitWithNeitherOwnershipNorAResponsibleSheetIsStillDenied() {
        Fixture f = seed();

        assertThat(assetEntryRepository.findReportableByIdAndUnitIds(
                Set.of(f.unrelatedUnit().getId()), f.assetOwnedByB().getId()))
                .as("widening reporting scope must not make it unbounded")
                .isEmpty();
    }

    @Test
    void locationOwnedAssetsWithNoLogSheetYetRemainReportable() {
        Fixture f = seed();

        // The union keeps the ownership arm: a freshly registered asset that has never
        // appeared on a log sheet is still reportable by the unit that owns its location.
        assertThat(assetEntryRepository.findReportableByIdAndUnitIds(
                Set.of(f.unitB().getId()), f.assetOwnedByB().getId()))
                .map(AssetEntry::getId)
                .contains(f.assetOwnedByB().getId());
    }

    @Test
    void reportableSearchAndPagingSeeTheSameWiderSet() {
        Fixture f = seed();

        var all = assetEntryRepository.findReportableByUnitIds(
                Set.of(f.unitA().getId()), PageRequest.of(0, 50));
        assertThat(all.getContent()).extracting(AssetEntry::getId)
                .contains(f.assetOwnedByB().getId());

        var searched = assetEntryRepository.searchReportableByUnitIds(
                Set.of(f.unitA().getId()), "reportable", PageRequest.of(0, 50));
        assertThat(searched.getContent()).extracting(AssetEntry::getId)
                .containsExactly(f.assetOwnedByB().getId());
        assertThat(searched.getTotalElements())
                .as("count query must use the same scope as the row query")
                .isEqualTo(1);
    }

    @Test
    void theSameAssetIsNotDuplicatedWhenBothArmsOfTheUnionMatch() {
        // Unit B owns the location AND is made responsible for a second sheet on the same
        // asset. UNION (not UNION ALL) must collapse that to one row.
        Fixture f = seed();
        LogSheet second = saveSheet(f.unitB().getId());
        saveEntry(second.getId(), f.assetOwnedByB().getId());

        var page = assetEntryRepository.findReportableByUnitIds(
                Set.of(f.unitB().getId()), PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(AssetEntry::getId)
                .containsOnlyOnce(f.assetOwnedByB().getId());
    }

    private Fixture seed() {
        long now = System.currentTimeMillis();

        OperationalUnit unitA = saveUnit("OU-RA-" + now, "Responsible unit");
        OperationalUnit unitB = saveUnit("OU-RB-" + now, "Owning unit");
        OperationalUnit unrelated = saveUnit("OU-RX-" + now, "Unrelated unit");

        Location locB = new Location();
        locB.setCode("LOC-RB-" + now);
        locB.setName("Loc owned by B");
        locB.setCreatedAt(now);
        locB.setUpdatedAt(now);
        locB = hierarchyService.saveLocation(locB, List.of(unitB.getId()));

        SubFunction sfB = new SubFunction();
        sfB.setCode("SF-RB-" + now);
        sfB.setName("SF B");
        sfB.setTag("TAG-SF-RB-" + now);
        sfB.setCreatedAt(now);
        sfB.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sfB, AssetHierarchyService.SCOPE_LOCATION, locB.getId());
        sfB = hierarchyService.saveSubFunction(sfB);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-REPORTABLE-" + now);
        asset.setAssetName("Reportable pump");
        asset.setSubFunctionId(sfB.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.save(asset);

        // Unit A is responsible for the sheet even though unit B owns the location.
        LogSheet sheet = saveSheet(unitA.getId());
        saveEntry(sheet.getId(), asset.getId());

        return new Fixture(unitA, unitB, unrelated, locB, asset);
    }

    private OperationalUnit saveUnit(String code, String name) {
        long now = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode(code);
        unit.setName(name);
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        return operationalUnitRepository.save(unit);
    }

    private LogSheet saveSheet(Long unitId) {
        long now = System.currentTimeMillis();
        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("Reporting scope fixture");
        sheet.setScopeSummary("fixture");
        sheet.setOperationalUnitId(unitId);
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setSubmittedAt(now);
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        return logSheetRepository.save(sheet);
    }

    private LogSheetEntry saveEntry(Long sheetId, Long assetId) {
        long now = System.currentTimeMillis();
        LogSheetEntry entry = new LogSheetEntry();
        entry.setLogSheetId(sheetId);
        entry.setAssetId(assetId);
        entry.setAssetName("Reportable pump");
        entry.setFormData(Map.of("pressure", 4.2));
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        return logSheetEntryRepository.save(entry);
    }

    private record Fixture(
            OperationalUnit unitA,
            OperationalUnit unitB,
            OperationalUnit unrelatedUnit,
            Location locB,
            AssetEntry assetOwnedByB) {}
}
