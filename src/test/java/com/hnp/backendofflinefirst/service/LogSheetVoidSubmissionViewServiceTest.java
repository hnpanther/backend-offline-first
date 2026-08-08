package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetVoidSubmission;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.util.FormDataViewHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetVoidSubmissionViewServiceTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock AssetEntryRepository assetEntryRepository;
    @Mock LogSheetFieldDefinitionsService fieldDefinitionsService;
    // The service resolves a sheet's attachments so a voided payload can still show its
    // photos. Lenient because most cases here carry no media and never reach the lookup.
    @Mock(lenient = true) AttachmentService attachmentService;

    LogSheetVoidSubmissionViewService service;

    @BeforeEach
    void setUp() {
        when(attachmentService.findForLogSheet(any())).thenReturn(List.of());
        service = new LogSheetVoidSubmissionViewService(
                logSheetRepository, assetEntryRepository, fieldDefinitionsService,
                new FormDataViewHelper(new ObjectMapper()), attachmentService);
    }

    private static LogSheetVoidSubmission submission(List<Map<String, Object>> payload) {
        LogSheetVoidSubmission v = new LogSheetVoidSubmission();
        v.setId(7L);
        v.setLogSheetId(1L);
        v.setPayload(payload);
        return v;
    }

    private static Map<String, Object> item(Object assetId, String assetName, Map<String, Object> formData) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assetId", assetId);
        m.put("assetName", assetName);
        m.put("formData", formData);
        m.put("updatedAt", 1_700_000_000_000L);
        return m;
    }

    private static AssetEntry asset(long id, String code, String name, Long classId) {
        AssetEntry a = new AssetEntry();
        a.setId(id);
        a.setAssetCode(code);
        a.setAssetName(name);
        a.setClassId(classId);
        return a;
    }

    private static FieldDefinition field(String key, String label, String unit) {
        FieldDefinition f = new FieldDefinition();
        f.setKey(key);
        f.setLabel(label);
        f.setUnit(unit);
        f.setDataType("text");
        return f;
    }

    @Test
    void rendersTheSubmittedValuesWithFieldLabelsFromTheAssetsOwnClass() {
        LogSheet sheet = new LogSheet();
        sheet.setId(1L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(sheet));
        when(assetEntryRepository.findAllById(Set.of(50L)))
                .thenReturn(List.of(asset(50L, "AST-1", "پمپ", 5L)));
        when(fieldDefinitionsService.resolveForClass(sheet, 5L))
                .thenReturn(List.of(field("temp", "دما", "°C")));

        var rows = service.toRows(submission(List.of(item(50, "پمپ", Map.of("temp", "88")))));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().assetCode()).isEqualTo("AST-1");
        assertThat(rows.getFirst().hasData()).isTrue();
        assertThat(rows.getFirst().fields()).singleElement()
                .satisfies(f -> {
                    assertThat(f.label()).isEqualTo("دما");
                    assertThat(f.value()).isEqualTo("88");
                    assertThat(f.unit()).isEqualTo("°C");
                });
    }

    @Test
    void keepsTheNameFromThePayloadWhenTheAssetNoLongerExists() {
        // The payload is a snapshot of what the device sent — it must stay readable even
        // after the asset row is gone, which is the whole point of storing it.
        LogSheet sheet = new LogSheet();
        sheet.setId(1L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(sheet));
        when(assetEntryRepository.findAllById(Set.of(99L))).thenReturn(List.of());
        when(fieldDefinitionsService.resolve(sheet)).thenReturn(List.of());

        var rows = service.toRows(submission(List.of(item(99L, "دارایی حذف‌شده", Map.of("k", "v")))));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().assetId()).isEqualTo(99L);
        assertThat(rows.getFirst().assetCode()).isNull();
        assertThat(rows.getFirst().assetName()).isEqualTo("دارایی حذف‌شده");
        // Unknown key falls back to the raw key as its label.
        assertThat(rows.getFirst().fields()).singleElement()
                .satisfies(f -> assertThat(f.label()).isEqualTo("k"));
    }

    @Test
    void marksAnEntryWithoutFormDataAsHavingNoData() {
        LogSheet sheet = new LogSheet();
        sheet.setId(1L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(sheet));
        when(assetEntryRepository.findAllById(Set.of(50L)))
                .thenReturn(List.of(asset(50L, "AST-1", "پمپ", 5L)));
        lenient().when(fieldDefinitionsService.resolveForClass(any(), any())).thenReturn(List.of());

        var rows = service.toRows(submission(List.of(item(50L, "پمپ", Map.of()))));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().hasData()).isFalse();
    }

    @Test
    void marksAnEntryWhoseOnlyReadingIsAnEmptyMultiselectAsHavingNoData() {
        LogSheet sheet = new LogSheet();
        sheet.setId(1L);
        when(logSheetRepository.findById(1L)).thenReturn(Optional.of(sheet));
        when(assetEntryRepository.findAllById(Set.of(50L)))
                .thenReturn(List.of(asset(50L, "AST-1", "پمپ", 5L)));
        when(fieldDefinitionsService.resolveForClass(sheet, 5L))
                .thenReturn(List.of(field("Status", "وضعیت", null)));

        var rows = service.toRows(
                submission(List.of(item(50, "پمپ", Map.of("Status", List.of())))));

        // The row exists and renders «ثبت نشده» — but a card where every parameter is unfilled
        // belongs under "بدون داده", which is the whole point of the filter.
        assertThat(rows.getFirst().fields()).hasSize(1);
        assertThat(rows.getFirst().hasData()).isFalse();
    }

    @Test
    void anEmptyPayloadProducesNoRowsAndTouchesNothing() {
        assertThat(service.toRows(submission(List.of()))).isEmpty();
        assertThat(service.toRows(submission(null))).isEmpty();
    }
}
