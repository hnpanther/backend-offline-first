package com.hnp.backendofflinefirst.service;

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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetTemplateServiceTest {

    @Mock LogSheetTemplateRepository templateRepository;
    @Mock AssetClassRepository assetClassRepository;
    @Mock AssetHierarchyService assetHierarchyService;
    @Mock OperationalUnitScopeService unitScopeService;
    @Mock BusinessEventLogger businessEventLogger;

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
        user.setActive(true);
        AppUserDetails principal = new AppUserDetails(user, Set.of(role), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
