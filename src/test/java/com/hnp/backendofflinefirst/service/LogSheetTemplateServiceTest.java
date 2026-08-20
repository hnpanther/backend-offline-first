package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.support.TestPrincipals;
import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.domain.LogSheetSizeLimits;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.RecurrenceUnit;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetTemplateServiceTest {

    @Mock LogSheetTemplateRepository templateRepository;
    @Mock AssetClassRepository assetClassRepository;
    @Mock com.hnp.backendofflinefirst.repository.AssetEntryRepository assetEntryRepository;
    @Mock com.hnp.backendofflinefirst.repository.LogSheetTemplateAssetRepository templateAssetRepository;
    @Mock AssetHierarchyService assetHierarchyService;
    @Mock OperationalUnitScopeService unitScopeService;
    @Mock BusinessEventLogger businessEventLogger;

    /**
     * A real instance, not a mock: the thresholds are the behaviour under test in the cases below
     * that exercise them, and a mocked {@code exceedsMax} would answer {@code false} to
     * everything — which is how a limit gets a green suite while enforcing nothing.
     */
    @Spy
    LogSheetSizeLimits sizeLimits = new LogSheetSizeLimits(300, 150);

    @InjectMocks LogSheetTemplateService service;

    @BeforeEach
    void stubAssetClassExists() {
        lenient().when(assetClassRepository.existsById(anyLong())).thenReturn(true);
        lenient().when(assetHierarchyService.resolveLocationIdForScope(org.mockito.ArgumentMatchers.anyString(), anyLong()))
                .thenReturn(1L);
        lenient().when(assetHierarchyService.scopeBelongsToOperationalUnit(
                        org.mockito.ArgumentMatchers.anyString(), anyLong(), anyLong()))
                .thenReturn(true);
        lenient().when(templateRepository.findByNameIgnoreCase(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void supervisorCannotEditTemplate() {
        authenticate(20L, "SUPERVISOR");
        LogSheetTemplate template = template(5L, 10L);
        when(templateRepository.findById(5L)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.update(5L, template))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void highUserCanEditTemplateInSupervisedUnit() {
        authenticate(30L, "HIGH_USER");
        LogSheetTemplate existing = template(5L, 10L);
        LogSheetTemplate form = template(5L, 10L);
        form.setName("Updated");
        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(unitScopeService.isSupervisorOf(30L, 10L)).thenReturn(true);

        service.update(5L, form);

        verify(templateRepository).save(existing);
        assertThat(existing.getName()).isEqualTo("Updated");
    }

    @Test
    void renameDoesNotResetNextRunAtCursor() {
        authenticate(1L, "ADMIN");
        long cursor = System.currentTimeMillis() + 3_600_000L;
        LogSheetTemplate existing = scheduledTemplate(5L, 10L);
        existing.setNextRunAt(cursor);
        existing.setScheduleStartAt(cursor - 24 * 3_600_000L);

        LogSheetTemplate form = copySchedule(existing);
        form.setName("Only renamed");

        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));

        service.update(5L, form);

        assertThat(existing.getName()).isEqualTo("Only renamed");
        assertThat(existing.getNextRunAt()).isEqualTo(cursor);
    }

    @Test
    void changingRecurrenceResetsNextRunAtFromScheduleStart() {
        authenticate(1L, "ADMIN");
        long now = System.currentTimeMillis();
        long start = now - 2 * 3_600_000L;
        long oldCursor = now + 10 * 3_600_000L;

        LogSheetTemplate existing = scheduledTemplate(5L, 10L);
        existing.setScheduleStartAt(start);
        existing.setRecurrenceEvery(1);
        existing.setNextRunAt(oldCursor);

        LogSheetTemplate form = copySchedule(existing);
        form.setRecurrenceEvery(2);

        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));

        service.update(5L, form);

        assertThat(existing.getNextRunAt()).isNotEqualTo(oldCursor);
        assertThat(existing.getNextRunAt()).isGreaterThanOrEqualTo(now);
    }

    @Test
    void deactivatingScheduleClearsNextRunAt() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate existing = scheduledTemplate(5L, 10L);
        existing.setNextRunAt(System.currentTimeMillis() + 60_000L);

        LogSheetTemplate form = copySchedule(existing);
        form.setScheduleActive(false);

        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));

        service.update(5L, form);

        assertThat(existing.getNextRunAt()).isNull();
        assertThat(existing.getScheduleActive()).isFalse();
    }

    @Test
    void switchingToManualClearsNextRunAt() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate existing = scheduledTemplate(5L, 10L);
        existing.setNextRunAt(System.currentTimeMillis() + 60_000L);

        LogSheetTemplate form = copySchedule(existing);
        form.setGenerationMode(GenerationMode.MANUAL);

        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));

        service.update(5L, form);

        assertThat(existing.getGenerationMode()).isEqualTo(GenerationMode.MANUAL);
        assertThat(existing.getNextRunAt()).isNull();
    }

    @Test
    void createSeedsNextRunAtForActiveSchedule() {
        authenticate(1L, "ADMIN");
        long start = System.currentTimeMillis() + 3_600_000L;
        LogSheetTemplate form = scheduledTemplate(null, 10L);
        form.setScheduleStartAt(start);
        form.setNextRunAt(null);

        when(templateRepository.save(form)).thenAnswer(inv -> inv.getArgument(0));

        LogSheetTemplate saved = service.create(form);

        assertThat(saved.getNextRunAt()).isEqualTo(start);
        assertThat(saved.getLastRunAt()).isNull();
    }

    @Test
    void createRejectsMissingAssetClass() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = template(null, 10L);
        form.setClassId(null);

        assertThatThrownBy(() -> service.create(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Asset class is required for log sheet template.");
    }

    @Test
    void createRejectsUnknownAssetClass() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = template(null, 10L);
        form.setClassId(99L);
        when(assetClassRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Asset class not found.");
    }

    @Test
    void updateRejectsMissingAssetClass() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate existing = template(5L, 10L);
        LogSheetTemplate form = template(5L, 10L);
        form.setClassId(null);
        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(5L, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Asset class is required for log sheet template.");
    }

    @Test
    void createRejectsScopeOutsideOperationalUnit() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = template(null, 10L);
        when(assetHierarchyService.resolveLocationIdForScope("location", 1L)).thenReturn(1L);
        when(assetHierarchyService.scopeBelongsToOperationalUnit("location", 1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Scope does not belong to the selected operational unit.");
    }

    @Test
    void createAllowsScopeOutsideUnitWhenRestrictionIsOff() {
        // A unit deliberately made responsible for assets outside its own locations.
        // Access is unaffected — the work is still reachable only through the sheet's
        // operational unit — so the scope check must not fire here.
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = template(null, 10L);
        form.setRestrictScopeToUnit(false);
        when(assetHierarchyService.resolveLocationIdForScope("location", 1L)).thenReturn(1L);
        when(templateRepository.save(any(LogSheetTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetTemplate saved = service.create(form);

        assertThat(saved.getRestrictScopeToUnit()).isFalse();
        verify(assetHierarchyService, never()).scopeBelongsToOperationalUnit(anyString(), anyLong(), anyLong());
    }

    @Test
    void supervisorMayNotCreateTemplatesEvenForTheirOwnUnit() {
        // Templates are ADMIN/HIGH_USER only. A supervisor keeps read access to the templates
        // of the units they belong to, but no write access at all. Enforced in the service as
        // well as by the endpoint permission, since the permission set is user-editable.
        authenticate(20L, "SUPERVISOR");
        LogSheetTemplate form = template(null, 10L);

        assertThatThrownBy(() -> service.create(form))
                .isInstanceOf(AccessDeniedException.class);
        verify(templateRepository, never()).save(any(LogSheetTemplate.class));
    }

    @Test
    void supervisorMayNotDeleteTemplates() {
        authenticate(20L, "SUPERVISOR");
        LogSheetTemplate existing = template(5L, 10L);
        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(AccessDeniedException.class);
        verify(templateRepository, never()).deleteById(anyLong());
    }

    @Test
    void supervisorStillSeesTemplatesOfEveryUnitTheyBelongTo() {
        // A user may be attached to several operational units; read access spans all of them.
        authenticate(20L, "SUPERVISOR");
        when(unitScopeService.getSupervisorScopeUnitIds(20L)).thenReturn(Set.of(10L, 11L, 12L));

        assertThat(service.visibleUnitIds()).containsExactlyInAnyOrder(10L, 11L, 12L);
        assertThat(service.canEditOrDelete()).isFalse();
    }

    @Test
    void adminMayUnrestrictScopeButUnitScopedUserMayNot() {
        authenticate(1L, "ADMIN");
        assertThat(service.canUnrestrictScope()).isTrue();
        SecurityContextHolder.clearContext();
        authenticate(20L, "SUPERVISOR");
        assertThat(service.canUnrestrictScope()).isFalse();
    }

    @Test
    void supervisorSeesOnlySupervisedUnits() {
        authenticate(20L, "SUPERVISOR");
        when(unitScopeService.getSupervisorScopeUnitIds(20L)).thenReturn(Set.of(10L));

        assertThat(service.visibleUnitIds()).containsExactly(10L);
    }

    @Test
    void adminSeesAllUnits() {
        authenticate(1L, "ADMIN");

        assertThat(service.visibleUnitIds()).isNull();
    }

    @Test
    void createRejectsBlankTemplateName() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = template(null, 10L);
        form.setName("  ");

        assertThatThrownBy(() -> service.create(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Log sheet template name is required.");
    }

    @Test
    void createRejectsPastScheduleStart() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = scheduledTemplate(null, 10L);
        form.setScheduleStartAt(System.currentTimeMillis() - 60_000L);

        assertThatThrownBy(() -> service.create(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Schedule start date must be in the future.");
    }

    @Test
    void createRejectsScheduleStartMoreThanTwoYearsAhead() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = scheduledTemplate(null, 10L);
        form.setScheduleStartAt(System.currentTimeMillis() + 3L * 365 * 24 * 3_600_000L);

        assertThatThrownBy(() -> service.create(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Schedule start date must be within 2 years from now.");
    }

    @Test
    void updateRejectsNewPastScheduleStart() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate existing = scheduledTemplate(5L, 10L);
        existing.setScheduleStartAt(System.currentTimeMillis() + 3_600_000L);
        LogSheetTemplate form = copySchedule(existing);
        form.setScheduleStartAt(System.currentTimeMillis() - 60_000L);

        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(5L, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Schedule start date must be in the future.");
    }

    @Test
    void updateDoesNotRevalidateUnchangedScheduleStartEvenIfNowInPast() {
        authenticate(1L, "ADMIN");
        // Original start has already drifted into the past, as happens naturally for a
        // template that has been live for a while — an unrelated edit (rename) must not
        // be blocked by re-checking this untouched value.
        LogSheetTemplate existing = scheduledTemplate(5L, 10L);
        existing.setScheduleStartAt(System.currentTimeMillis() - 10 * 24 * 3_600_000L);
        LogSheetTemplate form = copySchedule(existing);
        form.setName("Only renamed");

        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));

        service.update(5L, form);

        assertThat(existing.getName()).isEqualTo("Only renamed");
    }

    @Test
    void createRejectsCaseInsensitiveDuplicateTemplateName() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate existing = template(9L, 10L);
        existing.setName("Round Check");
        when(templateRepository.findByNameIgnoreCase("round check")).thenReturn(Optional.of(existing));

        LogSheetTemplate form = template(null, 10L);
        form.setName("round check");

        assertThatThrownBy(() -> service.create(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate log sheet template name");
    }

    @Test
    void scheduleOverlapRiskTrueWhenCompletionWindowExceedsRecurrenceInterval() {
        LogSheetTemplate form = scheduledTemplate(null, 10L);
        form.setRecurrenceUnit(RecurrenceUnit.MINUTE);
        form.setRecurrenceEvery(20);
        form.setCompletionWindowMinutes(60);

        assertThat(service.scheduleOverlapRisk(form)).isTrue();
    }

    @Test
    void scheduleOverlapRiskFalseWhenCompletionWindowFitsWithinRecurrenceInterval() {
        LogSheetTemplate form = scheduledTemplate(null, 10L);
        form.setRecurrenceUnit(RecurrenceUnit.MINUTE);
        form.setRecurrenceEvery(20);
        form.setCompletionWindowMinutes(15);

        assertThat(service.scheduleOverlapRisk(form)).isFalse();
    }

    @Test
    void scheduleOverlapRiskFalseWhenNotScheduled() {
        LogSheetTemplate form = scheduledTemplate(null, 10L);
        form.setGenerationMode(GenerationMode.MANUAL);
        form.setCompletionWindowMinutes(600);

        assertThat(service.scheduleOverlapRisk(form)).isFalse();
    }

    @Test
    void scheduleOverlapRiskFalseWhenScheduleInactive() {
        LogSheetTemplate form = scheduledTemplate(null, 10L);
        form.setScheduleActive(false);
        form.setCompletionWindowMinutes(600);

        assertThat(service.scheduleOverlapRisk(form)).isFalse();
    }

    @Test
    void scheduleOverlapRiskFalseWhenNoCompletionWindowConfigured() {
        LogSheetTemplate form = scheduledTemplate(null, 10L);
        form.setRecurrenceUnit(RecurrenceUnit.MINUTE);
        form.setRecurrenceEvery(20);
        form.setCompletionWindowMinutes(null);

        assertThat(service.scheduleOverlapRisk(form)).isFalse();
    }

    @Test
    void scheduleOverlapRiskAcrossHourAndDayUnits() {
        LogSheetTemplate hourly = scheduledTemplate(null, 10L);
        hourly.setRecurrenceUnit(RecurrenceUnit.HOUR);
        hourly.setRecurrenceEvery(1);
        hourly.setCompletionWindowMinutes(90);
        assertThat(service.scheduleOverlapRisk(hourly)).isTrue();

        LogSheetTemplate daily = scheduledTemplate(null, 10L);
        daily.setRecurrenceUnit(RecurrenceUnit.DAY);
        daily.setRecurrenceEvery(1);
        daily.setCompletionWindowMinutes(90);
        assertThat(service.scheduleOverlapRisk(daily)).isFalse();
    }

    // ---- EXPLICIT (frozen asset list) mode ----

    /** An EXPLICIT template deliberately carries no scope and no class. */
    private static LogSheetTemplate explicitForm(Long unitId) {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setName("Custom round");
        t.setOperationalUnitId(unitId);
        t.setAssetSelectionMode(AssetSelectionMode.EXPLICIT);
        return t;
    }

    private static com.hnp.backendofflinefirst.entity.AssetEntry activeAsset(long id) {
        com.hnp.backendofflinefirst.entity.AssetEntry a = new com.hnp.backendofflinefirst.entity.AssetEntry();
        a.setId(id);
        a.setAssetCode("AST-" + id);
        a.setActive(true);
        return a;
    }

    @SuppressWarnings("unchecked")
    private List<Long> savedTemplateAssetIds() {
        org.mockito.ArgumentCaptor<List<com.hnp.backendofflinefirst.entity.LogSheetTemplateAsset>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(templateAssetRepository).saveAll(captor.capture());
        return captor.getValue().stream()
                .map(com.hnp.backendofflinefirst.entity.LogSheetTemplateAsset::getAssetId)
                .toList();
    }

    @Test
    void explicitTemplateNeedsNoScopeOrClassAndFreezesTheChosenAssets() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = explicitForm(10L);
        when(assetEntryRepository.findActiveByIdIn(Set.of(50L, 51L)))
                .thenReturn(List.of(activeAsset(50L), activeAsset(51L)));
        when(templateRepository.save(any(LogSheetTemplate.class))).thenAnswer(inv -> {
            LogSheetTemplate t = inv.getArgument(0);
            t.setId(7L);
            return t;
        });

        LogSheetTemplate saved = service.create(form, List.of(50L, 51L));

        assertThat(saved.getAssetSelectionMode()).isEqualTo(AssetSelectionMode.EXPLICIT);
        assertThat(savedTemplateAssetIds()).containsExactly(50L, 51L);
        // The hierarchy walk is what EXPLICIT replaces — it must not be consulted at all.
        verify(assetHierarchyService, never()).scopeBelongsToOperationalUnit(anyString(), anyLong(), anyLong());
    }

    @Test
    void explicitTemplateKeepsSelectionOrderAndDropsDuplicates() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = explicitForm(10L);
        when(assetEntryRepository.findActiveByIdIn(Set.of(52L, 50L)))
                .thenReturn(List.of(activeAsset(50L), activeAsset(52L)));
        when(templateRepository.save(any(LogSheetTemplate.class))).thenAnswer(inv -> {
            LogSheetTemplate t = inv.getArgument(0);
            t.setId(7L);
            return t;
        });

        service.create(form, java.util.Arrays.asList(52L, 50L, 52L, null));

        assertThat(savedTemplateAssetIds()).containsExactly(52L, 50L);
    }

    @Test
    void explicitTemplateRejectsAnEmptySelection() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = explicitForm(10L);

        assertThatThrownBy(() -> service.create(form, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Select at least one asset for the log sheet template.");
        verify(templateRepository, never()).save(any(LogSheetTemplate.class));
    }

    @Test
    void explicitTemplateRejectsAnAssetThatIsNotActive() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = explicitForm(10L);
        // 51 is inactive (or gone), so the active lookup returns fewer rows than requested.
        when(assetEntryRepository.findActiveByIdIn(Set.of(50L, 51L))).thenReturn(List.of(activeAsset(50L)));

        assertThatThrownBy(() -> service.create(form, List.of(50L, 51L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Some selected assets are not available for this template.");
        verify(templateRepository, never()).save(any(LogSheetTemplate.class));
    }

    @Test
    void supervisorMayNotFreezeAssetsBecauseTheyCannotWriteTemplatesAtAll() {
        // The unit-confinement branch inside validateExplicitAssets is defence-in-depth: with
        // writes limited to ADMIN/HIGH_USER (neither of which is unit-scoped) no writer can
        // currently reach it, and a supervisor is stopped before any asset is even looked at.
        authenticate(20L, "SUPERVISOR");
        LogSheetTemplate form = explicitForm(10L);

        assertThatThrownBy(() -> service.create(form, List.of(50L, 99L)))
                .isInstanceOf(AccessDeniedException.class);
        verify(assetEntryRepository, never()).findActiveByIdIn(any());
        verify(assetEntryRepository, never()).findVisibleActiveByIdInAndUnitIds(any(), any());
    }

    @Test
    void switchingATemplateBackToScopeModeClearsItsFrozenAssets() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate existing = template(5L, 10L);
        existing.setAssetSelectionMode(AssetSelectionMode.EXPLICIT);
        LogSheetTemplate form = template(5L, 10L); // defaults back to SCOPE
        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));

        service.update(5L, form, null);

        verify(templateAssetRepository).deleteByTemplateId(5L);
        verify(templateAssetRepository, never()).saveAll(any());
        assertThat(existing.getAssetSelectionMode()).isEqualTo(AssetSelectionMode.SCOPE);
    }

    @Test
    void editingAnExplicitTemplateReplacesTheWholeFrozenList() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate existing = template(5L, 10L);
        existing.setAssetSelectionMode(AssetSelectionMode.EXPLICIT);
        LogSheetTemplate form = explicitForm(10L);
        form.setId(5L);
        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(assetEntryRepository.findActiveByIdIn(Set.of(60L))).thenReturn(List.of(activeAsset(60L)));

        service.update(5L, form, List.of(60L));

        verify(templateAssetRepository).deleteByTemplateId(5L);
        assertThat(savedTemplateAssetIds()).containsExactly(60L);
    }

    @Test
    void aMissingSelectionModeDefaultsToTheClassicScopeBehaviour() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = template(null, 10L);
        form.setAssetSelectionMode(null); // an old form / import posts nothing for this field
        when(templateRepository.save(any(LogSheetTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        LogSheetTemplate saved = service.create(form);

        assertThat(saved.getAssetSelectionMode()).isEqualTo(AssetSelectionMode.SCOPE);
    }

    // ─────────────────────────── the asset-count ceiling, refused where a human is standing

    @Test
    void createRefusesAScopeThatResolvesToTooManyAssets() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = template(null, 10L);
        when(assetHierarchyService.findAssetsInScope("location", 1L, 2L))
                .thenReturn(assets(412));

        assertThatThrownBy(() -> service.create(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("412 assets")
                .hasMessageContaining("maximum is 300");
        // Refused before anything is written: a rejected template must leave no row behind.
        verify(templateRepository, never()).save(any());
    }

    @Test
    void createAcceptsAScopeExactlyAtTheMaximum() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = template(null, 10L);
        when(assetHierarchyService.findAssetsInScope("location", 1L, 2L))
                .thenReturn(assets(300));
        when(templateRepository.save(form)).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create(form)).isNotNull();
    }

    @Test
    void createRefusesTooManyHandPickedAssets() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = template(null, 10L);
        form.setAssetSelectionMode(AssetSelectionMode.EXPLICIT);
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 1; i <= 301; i++) ids.add(i);
        when(assetEntryRepository.findActiveByIdIn(any())).thenReturn(assets(301));

        assertThatThrownBy(() -> service.create(form, ids))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("301 assets");
        // An EXPLICIT template is counted from the submitted list, so the hierarchy is never
        // touched — a hand-picked set is not a scope and must not be re-resolved as one.
        verify(assetHierarchyService, never()).findAssetsInScope(anyString(), anyLong(), anyLong());
    }

    @Test
    void updateRefusesAScopeWidenedPastTheMaximum() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate existing = template(5L, 10L);
        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));
        LogSheetTemplate form = template(5L, 10L);
        when(assetHierarchyService.findAssetsInScope("location", 1L, 2L))
                .thenReturn(assets(500));

        assertThatThrownBy(() -> service.update(5L, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500 assets");
    }

    @Test
    void anIncompleteScopeCountsAsZeroRatherThanFailing() {
        authenticate(1L, "ADMIN");
        LogSheetTemplate form = template(null, 10L);
        form.setScopeId(null);

        // validateRequiredFields owns "you must pick a scope" and reports it properly. The count
        // check must not race it to a worse message, so an unresolvable scope counts as zero.
        assertThatThrownBy(() -> service.create(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Scope is required for log sheet template.");
    }

    private static List<com.hnp.backendofflinefirst.entity.AssetEntry> assets(int count) {
        List<com.hnp.backendofflinefirst.entity.AssetEntry> list = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            com.hnp.backendofflinefirst.entity.AssetEntry asset =
                    new com.hnp.backendofflinefirst.entity.AssetEntry();
            asset.setId((long) i + 1);
            list.add(asset);
        }
        return list;
    }

    private static LogSheetTemplate template(Long id, Long unitId) {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setId(id);
        t.setName("Round check");
        t.setOperationalUnitId(unitId);
        t.setScopeType("location");
        t.setScopeId(1L);
        t.setClassId(2L);
        return t;
    }

    private static LogSheetTemplate scheduledTemplate(Long id, Long unitId) {
        LogSheetTemplate t = template(id, unitId);
        t.setGenerationMode(GenerationMode.SCHEDULED);
        t.setScheduleActive(true);
        t.setRecurrenceUnit(RecurrenceUnit.HOUR);
        t.setRecurrenceEvery(1);
        t.setScheduleStartAt(System.currentTimeMillis());
        return t;
    }

    private static LogSheetTemplate copySchedule(LogSheetTemplate src) {
        LogSheetTemplate t = template(src.getId(), src.getOperationalUnitId());
        t.setName(src.getName());
        t.setGenerationMode(src.getGenerationMode());
        t.setScheduleActive(src.getScheduleActive());
        t.setRecurrenceUnit(src.getRecurrenceUnit());
        t.setRecurrenceEvery(src.getRecurrenceEvery());
        t.setScheduleStartAt(src.getScheduleStartAt());
        t.setCompletionWindowMinutes(src.getCompletionWindowMinutes());
        t.setActive(src.getActive());
        t.setScopeType(src.getScopeType());
        t.setScopeId(src.getScopeId());
        t.setClassId(src.getClassId());
        return t;
    }

    private static void authenticate(Long userId, String role) {
        User user = new User();
        user.setId(userId);
        user.setUsername("tester");
        user.setPersonnelCode("PC-" + java.util.UUID.randomUUID());
        user.setActive(true);
        AppUserDetails principal = TestPrincipals.of(user, Set.of(role), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
