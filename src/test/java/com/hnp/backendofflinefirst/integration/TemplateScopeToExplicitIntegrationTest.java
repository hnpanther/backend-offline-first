package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetTemplateService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Freezing a scoped template's asset list — {@code roadmap.md} §21.
 *
 * <p>What has to be true afterwards is mostly about what <em>stops</em> happening: the template
 * must no longer follow the plant, and it must say so by carrying no scope at all rather than a
 * scope that disagrees with its list.
 */
@Transactional
class TemplateScopeToExplicitIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetTemplateService templateService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired EntityManager entityManager;

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("the edited list is frozen and the scope is cleared")
    void convertsAndClearsTheScope() {
        Fixture f = seed();

        LogSheetTemplate converted = templateService.convertToExplicit(
                f.template().getId(), List.of(f.assetA().getId(), f.assetB().getId()));

        assertThat(converted.getAssetSelectionMode()).isEqualTo(AssetSelectionMode.EXPLICIT);
        assertThat(converted.getScopeType()).isNull();
        assertThat(converted.getScopeId()).isNull();
        assertThat(converted.getClassId())
                .as("a list and a scope that disagree leave nobody able to say which one is the template")
                .isNull();
        assertThat(templateService.assetIdsForTemplate(converted.getId()))
                .containsExactlyInAnyOrder(f.assetA().getId(), f.assetB().getId());
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("a removed asset stays out of the next round")
    void aRemovedAssetDoesNotComeBack() {
        Fixture f = seed();
        templateService.convertToExplicit(f.template().getId(), List.of(f.assetA().getId()));
        entityManager.flush();
        entityManager.clear();

        LogSheet sheet = generate(f.template().getId());

        assertThat(assetIdsOn(sheet)).containsExactly(f.assetA().getId());
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("newly installed equipment no longer joins by itself — the whole point")
    void theTemplateStopsFollowingThePlant() {
        Fixture f = seed();
        templateService.convertToExplicit(
                f.template().getId(), List.of(f.assetA().getId(), f.assetB().getId()));
        entityManager.flush();

        // A third pump is installed in the same scope after the conversion.
        saveAsset("C", System.nanoTime(), f.pumpClass().getId(),
                childPosition("C", System.nanoTime(), f.subFunction().getId()).getId());
        entityManager.flush();
        entityManager.clear();

        LogSheet sheet = generate(f.template().getId());

        assertThat(assetIdsOn(sheet))
                .as("a SCOPE template would have picked it up; that is what was given away")
                .containsExactlyInAnyOrder(f.assetA().getId(), f.assetB().getId());
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("an empty list is refused — it would generate empty rounds, silently")
    void refusesAnEmptyList() {
        Fixture f = seed();

        assertThatThrownBy(() -> templateService.convertToExplicit(f.template().getId(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(templateRepository.findById(f.template().getId()).orElseThrow()
                .getAssetSelectionMode()).isEqualTo(AssetSelectionMode.SCOPE);
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("converting an already-explicit template is refused rather than silently redone")
    void refusesASecondConversion() {
        Fixture f = seed();
        templateService.convertToExplicit(f.template().getId(), List.of(f.assetA().getId()));
        entityManager.flush();

        assertThatThrownBy(() -> templateService.convertToExplicit(
                f.template().getId(), List.of(f.assetB().getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already");
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("a sheet raised before the conversion is untouched")
    void alreadyGeneratedSheetsAreNotRewritten() {
        Fixture f = seed();
        LogSheet before = generate(f.template().getId());
        List<Long> assetsBefore = assetIdsOn(before);

        templateService.convertToExplicit(f.template().getId(), List.of(f.assetA().getId()));
        entityManager.flush();
        entityManager.clear();

        assertThat(assetIdsOn(logSheetRepository.findById(before.getId()).orElseThrow()))
                .as("entries were frozen when the sheet was raised; that is the point of freezing them")
                .containsExactlyInAnyOrderElementsOf(assetsBefore);
    }

    // ---------------------------------------------------------------------------------------

    private LogSheet generate(Long templateId) {
        LogSheetTemplate t = templateRepository.findById(templateId).orElseThrow();
        LogSheet sheet = generationService.generateAt(t, GenerationMode.MANUAL, 1L,
                System.currentTimeMillis(), System.currentTimeMillis());
        logSheetRepository.flush();
        entityManager.clear();
        return logSheetRepository.findById(sheet.getId()).orElseThrow();
    }

    private List<Long> assetIdsOn(LogSheet sheet) {
        return logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .map(LogSheetEntry::getAssetId).collect(Collectors.toList());
    }

    private Fixture seed() {
        long now = System.nanoTime();

        AssetClass pump = new AssetClass();
        pump.setName("Pumps " + now);
        pump.setCreatedAt(now);
        pump.setUpdatedAt(now);
        pump = assetClassRepository.save(pump);

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-CNV-" + now);
        unit.setName("واحد");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location location = new Location();
        location.setCode("LOC-CNV-" + now);
        location.setName("محل");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location, List.of(unit.getId()));

        SubFunction root = new SubFunction();
        root.setCode("SF-CNV-" + now);
        root.setName("ساب‌فانکشن");
        root.setTag("TAG-CNV-" + now);
        root.setCreatedAt(now);
        root.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(root, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        root = hierarchyService.saveSubFunction(root);

        AssetEntry a = saveAsset("A", now, pump.getId(), childPosition("A", now, root.getId()).getId());
        AssetEntry b = saveAsset("B", now, pump.getId(), childPosition("B", now, root.getId()).getId());

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Round " + now);
        template.setAssetSelectionMode(AssetSelectionMode.SCOPE);
        template.setScopeType(AssetHierarchyService.SCOPE_SUB_FUNCTION);
        template.setScopeId(root.getId());
        template.setClassId(pump.getId());
        template.setOperationalUnitId(unit.getId());
        template.setActive(true);
        template.setScheduleActive(false);
        template.setGenerationMode(GenerationMode.MANUAL);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.save(template);

        entityManager.flush();
        return new Fixture(pump, a, b, root, template);
    }

    private SubFunction childPosition(String suffix, long now, Long parentId) {
        SubFunction sf = new SubFunction();
        sf.setCode("SF-CNV-" + suffix + "-" + now);
        sf.setName("موقعیت " + suffix);
        sf.setTag("TAG-CNV-" + suffix + "-" + now);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_SUB_FUNCTION, parentId);
        return hierarchyService.saveSubFunction(sf);
    }

    private AssetEntry saveAsset(String suffix, long now, Long classId, Long subFunctionId) {
        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-CNV-" + suffix + "-" + now);
        asset.setAssetName("پمپ " + suffix);
        asset.setClassId(classId);
        asset.setSubFunctionId(subFunctionId);
        asset.setActive(true);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return assetEntryRepository.save(asset);
    }

    private record Fixture(AssetClass pumpClass, AssetEntry assetA, AssetEntry assetB,
                           SubFunction subFunction, LogSheetTemplate template) {}
}
