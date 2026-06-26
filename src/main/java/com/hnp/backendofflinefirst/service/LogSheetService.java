package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LogSheetService {

    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;

    @Transactional
    public List<LogSheetSubmitResult> submitBatch(List<LogSheetDto> dtos) {
        List<LogSheetSubmitResult> results = new ArrayList<>();
        for (LogSheetDto dto : dtos) {
            try {
                Optional<LogSheet> existing = logSheetRepository.findByLocalId(dto.getLocalId());
                String serverId;
                if (existing.isPresent()) {
                    LogSheet sheet = existing.get();
                    sheet.setStatus(dto.getStatus());
                    sheet.setOperatorName(dto.getOperatorName());
                    sheet.setSubmittedAt(dto.getSubmittedAt());
                    sheet.setUpdatedAt(dto.getUpdatedAt());
                    logSheetRepository.save(sheet);
                    serverId = sheet.getId();
                    replaceEntries(serverId, dto.getEntries());
                } else {
                    serverId = UUID.randomUUID().toString();
                    logSheetRepository.save(toEntity(dto, serverId));
                    saveEntries(serverId, dto.getEntries());
                }
                results.add(new LogSheetSubmitResult(dto.getLocalId(), serverId, null));
            } catch (Exception e) {
                results.add(new LogSheetSubmitResult(dto.getLocalId(), null, e.getMessage()));
            }
        }
        return results;
    }

    private LogSheet toEntity(LogSheetDto dto, String serverId) {
        LogSheet sheet = new LogSheet();
        sheet.setId(serverId);
        sheet.setLocalId(dto.getLocalId());
        sheet.setTemplateId(dto.getTemplateId());
        sheet.setTemplateName(dto.getTemplateName());
        sheet.setScopeSummary(dto.getScopeSummary());
        sheet.setOperatorName(dto.getOperatorName());
        sheet.setStatus(dto.getStatus());
        sheet.setSyncStatus(dto.getSyncStatus());
        sheet.setSubmittedAt(dto.getSubmittedAt());
        sheet.setSyncedAt(dto.getSyncedAt());
        sheet.setSyncError(dto.getSyncError());
        sheet.setCreatedAt(dto.getCreatedAt());
        sheet.setUpdatedAt(dto.getUpdatedAt());
        return sheet;
    }

    private void saveEntries(String logSheetId, List<LogSheetEntryDto> entryDtos) {
        if (entryDtos == null) return;
        for (LogSheetEntryDto dto : entryDtos) {
            LogSheetEntry entry = new LogSheetEntry();
            entry.setId(UUID.randomUUID().toString());
            entry.setLogSheetId(logSheetId);
            entry.setAssetId(dto.getAssetId());
            entry.setAssetName(dto.getAssetName());
            entry.setSubFunctionCode(dto.getSubFunctionCode());
            entry.setSubFunctionTag(dto.getSubFunctionTag());
            entry.setClassId(dto.getClassId());
            entry.setFormData(dto.getFormData());
            logSheetEntryRepository.save(entry);
        }
    }

    private void replaceEntries(String logSheetId, List<LogSheetEntryDto> entryDtos) {
        List<LogSheetEntry> old = logSheetEntryRepository.findByLogSheetId(logSheetId);
        logSheetEntryRepository.deleteAll(old);
        saveEntries(logSheetId, entryDtos);
    }
}
