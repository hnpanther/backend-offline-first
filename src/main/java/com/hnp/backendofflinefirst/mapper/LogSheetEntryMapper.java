package com.hnp.backendofflinefirst.mapper;

import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;

public final class LogSheetEntryMapper {

    private LogSheetEntryMapper() {}

    public static LogSheetEntryDto toDto(LogSheetEntry entry) {
        LogSheetEntryDto dto = new LogSheetEntryDto();
        dto.setAssetId(entry.getAssetId());
        dto.setAssetName(entry.getAssetName());
        dto.setSubFunctionCode(entry.getSubFunctionCode());
        dto.setSubFunctionTag(entry.getSubFunctionTag());
        dto.setNfcTagId(entry.getNfcTagId());
        dto.setClassId(entry.getClassId());
        dto.setFormData(entry.getFormData());
        dto.setCreatedAt(entry.getCreatedAt());
        dto.setUpdatedAt(entry.getUpdatedAt());
        return dto;
    }
}
