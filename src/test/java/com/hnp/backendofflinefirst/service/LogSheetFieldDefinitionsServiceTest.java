package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetFieldDefinitionsServiceTest {

    @Mock FieldDefinitionRepository fieldDefinitionRepository;
    @InjectMocks LogSheetFieldDefinitionsService service;

    @Test
    void captureSnapshotExcludesDeletedFields() {
        FieldDefinition active = field("temp", false);
        active.setId(11L);
        FieldDefinition deleted = field("old", true);
        deleted.setId(12L);

        when(fieldDefinitionRepository.findByClassId(7L)).thenReturn(List.of(active, deleted));

        List<FieldDefinitionSnapshot> snapshot = service.captureSnapshot(7L);

        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).getKey()).isEqualTo("temp");
        assertThat(snapshot.get(0).getId()).isEqualTo(11L);
    }

    @Test
    void resolveUsesSnapshotWhenPresent() {
        FieldDefinition source = field("temp", false);
        source.setId(11L);
        FieldDefinitionSnapshot snap = FieldDefinitionSnapshot.from(source);
        LogSheet sheet = new LogSheet();
        sheet.setFieldDefinitionsSnapshot(List.of(snap));

        List<FieldDefinition> resolved = service.resolveForBundle(sheet, Set.of(7L));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).getKey()).isEqualTo("temp");
        assertThat(resolved.get(0).getId()).isEqualTo(11L);
    }

    @Test
    void resolveFallsBackToLiveDefinitionsForLegacySheets() {
        FieldDefinition live = field("pressure", false);
        live.setId(99L);
        LogSheet sheet = new LogSheet();
        LogSheetEntry entry = new LogSheetEntry();
        entry.setClassId(7L);

        when(fieldDefinitionRepository.findByClassIdIn(Set.of(7L))).thenReturn(List.of(live));

        List<FieldDefinition> resolved = service.resolveForEntries(sheet, List.of(entry));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).getKey()).isEqualTo("pressure");
        verify(fieldDefinitionRepository).findByClassIdIn(Set.of(7L));
    }

    @Test
    void resolveForBundleIgnoresLiveRepositoryWhenSnapshotHasIds() {
        FieldDefinition source = field("temp", false);
        source.setId(11L);
        FieldDefinitionSnapshot snap = FieldDefinitionSnapshot.from(source);
        LogSheet sheet = new LogSheet();
        sheet.setFieldDefinitionsSnapshot(List.of(snap));

        service.resolveForBundle(sheet, Set.of(7L));

        org.mockito.Mockito.verifyNoInteractions(fieldDefinitionRepository);
    }

    @Test
    void resolveEnrichesMissingIdsFromLiveDefinitions() {
        FieldDefinitionSnapshot snap = FieldDefinitionSnapshot.from(field("temp", false));
        snap.setId(null);
        FieldDefinitionSnapshot snapBar = FieldDefinitionSnapshot.from(field("bar", false));
        snapBar.setId(null);
        snapBar.setKey("bar");
        snapBar.setLabel("Bar");

        LogSheet sheet = new LogSheet();
        sheet.setFieldDefinitionsSnapshot(List.of(snap, snapBar));

        FieldDefinition liveTemp = field("temp", false);
        liveTemp.setId(101L);
        FieldDefinition liveBar = field("bar", false);
        liveBar.setId(102L);
        when(fieldDefinitionRepository.findByClassIdIn(Set.of(7L))).thenReturn(List.of(liveTemp, liveBar));

        List<FieldDefinition> resolved = service.resolveForBundle(sheet, Set.of(7L));

        assertThat(resolved).hasSize(2);
        assertThat(resolved).extracting(FieldDefinition::getId).containsExactlyInAnyOrder(101L, 102L);
    }

    private static FieldDefinition field(String key, boolean deleted) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(7L);
        fd.setKey(key);
        fd.setLabel(key);
        fd.setDataType("number");
        fd.setDeleted(deleted);
        fd.setValidation(Map.of());
        return fd;
    }
}
