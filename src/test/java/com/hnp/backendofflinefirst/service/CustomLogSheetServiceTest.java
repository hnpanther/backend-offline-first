package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomLogSheetServiceTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock LogSheetEntryRepository logSheetEntryRepository;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock AssetEntryRepository assetEntryRepository;
    @Mock OperationalUnitScopeService scopeService;
    @Mock LogSheetFieldDefinitionsService fieldDefinitionsService;
    @Mock LogSheetActionLogger actionLogger;
    @Mock BusinessEventLogger businessEventLogger;

    @InjectMocks CustomLogSheetService service;

    private MockedStatic<SecurityUtils> security;

    @AfterEach
    void tearDown() {
        if (security != null) {
            security.close();
            security = null;
        }
    }

    @Test
    void createCustomRejectsBlankName() {
        assertThatThrownBy(() -> service.createCustom(1L, "  ", null, List.of(1L), 9L, 1000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Log sheet name is required.");
    }

    @Test
    void createCustomRejectsMissingUnit() {
        assertThatThrownBy(() -> service.createCustom(null, "Round A", null, List.of(1L), 9L, 1000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Operational unit is required for a custom log sheet.");
    }

    @Test
    void createCustomRejectsEmptyAssetSelection() {
        assertThatThrownBy(() -> service.createCustom(1L, "Round A", null, List.of(), 9L, 1000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Select at least one asset for the custom log sheet.");
    }

    @Test
    void createCustomRejectsPastDueDate() {
        assertThatThrownBy(() -> service.createCustom(1L, "Round A", 500L, List.of(1L), 9L, 1000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Custom log sheet due date must be in the future.");
    }

    @Test
    void createCustomDeniesUnitScopedSupervisorOutsideOwnUnits() {
        security = mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::isUnitScopedOnly).thenReturn(true);
        when(scopeService.getSupervisorScopeUnitIds(9L)).thenReturn(Set.of(99L));

        assertThatThrownBy(() -> service.createCustom(1L, "Round A", null, List.of(1L), 9L, 1000L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("units you supervise");
        verify(assetEntryRepository, never()).findVisibleActiveByIdInAndUnitIds(any(), any());
    }

    @Test
    void createCustomRejectsAssetsOutsideUnitScope() {
        security = mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::isUnitScopedOnly).thenReturn(false);

        AssetEntry a1 = asset(10L, 7L, 100L);
        when(assetEntryRepository.findVisibleActiveByIdInAndUnitIds(eq(Set.of(1L)), anyCollection()))
                .thenReturn(List.of(a1)); // only one of two selected

        assertThatThrownBy(() ->
                service.createCustom(1L, "Round A", null, List.of(10L, 11L), 9L, 1000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Some selected assets are not available in this operational unit.");
    }

    @Test
    void createCustomBuildsMultiClassPendingSheetWithEntriesAndSnapshot() {
        security = mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::isUnitScopedOnly).thenReturn(false);

        AssetEntry pump = asset(10L, 7L, 100L);
        pump.setAssetName("Pump A");
        AssetEntry motor = asset(11L, 8L, 101L);
        motor.setAssetName("Motor B");

        when(assetEntryRepository.findVisibleActiveByIdInAndUnitIds(eq(Set.of(1L)), anyCollection()))
                .thenReturn(List.of(pump, motor));

        SubFunction sfPump = new SubFunction();
        sfPump.setId(100L);
        sfPump.setCode("SF-P");
        sfPump.setTag("TAG-P");
        SubFunction sfMotor = new SubFunction();
        sfMotor.setId(101L);
        sfMotor.setCode("SF-M");
        sfMotor.setTag("TAG-M");
        when(subFunctionRepository.findAllById(Set.of(100L, 101L))).thenReturn(List.of(sfPump, sfMotor));

        FieldDefinitionSnapshot snap = new FieldDefinitionSnapshot();
        snap.setClassId(7L);
        snap.setKey("temp");
        when(fieldDefinitionsService.captureSnapshot(anyCollection())).thenReturn(List.of(snap));

        when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> {
            LogSheet s = inv.getArgument(0);
            s.setId(55L);
            return s;
        });

        long now = 1_700_000_000_000L;
        long dueAt = now + 3_600_000L;
        LogSheet sheet = service.createCustom(1L, "  Custom Round  ", dueAt,
                List.of(10L, 11L, 10L), 9L, now);

        assertThat(sheet.getId()).isEqualTo(55L);
        assertThat(sheet.getTemplateId()).isNull();
        assertThat(sheet.getTemplateName()).isEqualTo("Custom Round");
        assertThat(sheet.getScopeSummary()).isNull();
        assertThat(sheet.getOperationalUnitId()).isEqualTo(1L);
        assertThat(sheet.getOrigin()).isEqualTo(GenerationMode.MANUAL);
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.PENDING);
        assertThat(sheet.getDueAt()).isEqualTo(dueAt);
        assertThat(sheet.getFieldDefinitionsSnapshot()).hasSize(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LogSheetEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(logSheetEntryRepository).saveAll(entriesCaptor.capture());
        assertThat(entriesCaptor.getValue()).hasSize(2);
        assertThat(entriesCaptor.getValue()).extracting(LogSheetEntry::getAssetId)
                .containsExactly(10L, 11L);
        assertThat(entriesCaptor.getValue()).extracting(LogSheetEntry::getClassId)
                .containsExactly(7L, 8L);
        assertThat(entriesCaptor.getValue()).extracting(LogSheetEntry::getSubFunctionCode)
                .containsExactly("SF-P", "SF-M");

        verify(actionLogger).record(eq(55L), eq(LogSheetActionType.GENERATE), any(),
                eq(9L), isNull(), isNull(), eq(now), isNull());
        verify(businessEventLogger).logSheetGenerated(55L, null, "Custom Round", "MANUAL");
        verify(fieldDefinitionsService).captureSnapshot(Set.of(7L, 8L));
    }

    @Test
    void createCustomAllowsAdminWithoutSupervisorCheck() {
        security = mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::isUnitScopedOnly).thenReturn(false);

        AssetEntry a = asset(10L, 7L, 100L);
        when(assetEntryRepository.findVisibleActiveByIdInAndUnitIds(eq(Set.of(1L)), anyCollection()))
                .thenReturn(List.of(a));
        when(subFunctionRepository.findAllById(Set.of(100L))).thenReturn(List.of());
        when(fieldDefinitionsService.captureSnapshot(anyCollection())).thenReturn(List.of());
        when(logSheetRepository.save(any(LogSheet.class))).thenAnswer(inv -> {
            LogSheet s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });

        service.createCustom(1L, "Admin Round", null, List.of(10L), 1L, 1000L);

        verify(scopeService, never()).getSupervisorScopeUnitIds(any());
    }

    private static AssetEntry asset(Long id, Long classId, Long subFunctionId) {
        AssetEntry a = new AssetEntry();
        a.setId(id);
        a.setClassId(classId);
        a.setSubFunctionId(subFunctionId);
        a.setActive(true);
        a.setAssetCode("A-" + id);
        a.setAssetName("Asset " + id);
        return a;
    }
}
