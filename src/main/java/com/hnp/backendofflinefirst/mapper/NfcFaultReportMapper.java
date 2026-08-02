package com.hnp.backendofflinefirst.mapper;

import com.hnp.backendofflinefirst.dto.NfcFaultReportDto;
import com.hnp.backendofflinefirst.entity.NfcFaultReport;

public final class NfcFaultReportMapper {

    private NfcFaultReportMapper() {}

    public static NfcFaultReportDto toDto(NfcFaultReport report) {
        NfcFaultReportDto dto = new NfcFaultReportDto();
        dto.setId(report.getId());
        dto.setLogSheetId(report.getLogSheetId());
        dto.setAssetId(report.getAssetId());
        dto.setReason(report.getReason());
        dto.setReportedByName(report.getReportedByName());
        dto.setSource(report.getSource() != null ? report.getSource().name() : null);
        dto.setStatus(report.getStatus() != null ? report.getStatus().name() : null);
        dto.setCreatedAt(report.getCreatedAt());
        dto.setSyncedAt(report.getSyncedAt());
        dto.setClientActionId(report.getClientActionId());
        dto.setLocalId(report.getLocalId());
        return dto;
    }
}
