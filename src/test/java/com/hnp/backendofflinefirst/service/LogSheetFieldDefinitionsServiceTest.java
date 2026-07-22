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
        FieldDefinition deleted = field("old", true);

        when(fieldDefinitionRepository.findByClassId(7L)).thenReturn(List.of(active, deleted));

        List<FieldDefinitionSnapshot> snapshot = service.captureSnapshot(7L);

        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).getKey()).isEqualTo("temp");
    }

    @Test
    void resolveUsesSnapshotWhenPresent() {
        FieldDefinitionSnapshot snap = FieldDefinitionSnapshot.from(field("temp", false));
        LogSheet sheet = new LogSheet();
        sheet.setFieldDefinitionsSnapshot(List.of(snap));

        List<FieldDefinition> resolved = service.resolveForBundle(sheet, Set.of(7L));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).getKey()).isEqualTo("temp");
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
    void resolveForBundleIgnoresLiveRepositoryWhenSnapshotExists() {
        FieldDefinitionSnapshot snap = FieldDefinitionSnapshot.from(field("temp", false));
        LogSheet sheet = new LogSheet();
        sheet.setFieldDefinitionsSnapshot(List.of(snap));

        service.resolveForBundle(sheet, Set.of(7L));

        org.mockito.Mockito.verifyNoInteractions(fieldDefinitionRepository);
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
