package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.BootstrapResponse;
import com.hnp.backendofflinefirst.dto.LogSheetContextDto;
import com.hnp.backendofflinefirst.entity.LookupList;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Lightweight mobile bootstrap: operational-unit context for the signed-in user.
 * Plant hierarchy and assets are delivered per log-sheet bundle instead.
 */
@Service
@RequiredArgsConstructor
public class BootstrapService {

    private final OperationalUnitScopeService unitScopeService;
    private final OperationalUnitRepository operationalUnitRepository;
    private final AppSettingsService appSettingsService;
    private final LookupGrantService lookupGrantService;

    private static BootstrapResponse.AttachmentLimitsDto toDto(
            AppSettingsService.AttachmentLimits limits) {
        return BootstrapResponse.AttachmentLimitsDto.builder()
                .maxImagesPerField(limits.maxImagesPerField())
                .maxAudiosPerField(limits.maxAudiosPerField())
                .maxVideosPerField(limits.maxVideosPerField())
                .maxAudioSeconds(limits.maxAudioSeconds())
                .maxVideoSeconds(limits.maxVideoSeconds())
                .build();
    }

    public BootstrapResponse getBootstrap(Long userId, boolean unitScopedOnly) {
        Set<Long> accessibleUnitIds = unitScopedOnly
                ? unitScopeService.getAccessibleUnitIds(userId)
                : operationalUnitRepository.findAll().stream()
                        .map(OperationalUnit::getId)
                        .collect(java.util.stream.Collectors.toSet());
        Set<Long> supervisorScopeUnitIds = unitScopeService.getSupervisorScopeUnitIds(userId);

        List<OperationalUnit> operationalUnits = operationalUnitRepository.findAllById(accessibleUnitIds).stream()
                .sorted(Comparator.comparing(OperationalUnit::getId))
                .toList();

        List<LogSheetContextDto.RequiredLookup> formLookups =
                lookupGrantService.requiredListsForFillableForms(userId).stream()
                        .map(BootstrapService::toRequiredLookup)
                        .toList();

        return BootstrapResponse.builder()
                .serverTime(System.currentTimeMillis())
                .userId(userId)
                .operationalUnits(operationalUnits)
                .accessibleUnitIds(accessibleUnitIds)
                .supervisorScopeUnitIds(supervisorScopeUnitIds)
                .primaryUnitId(unitScopeService.getPrimaryUnitId(userId))
                .attachmentLimits(toDto(appSettingsService.getAttachmentLimits()))
                .mobilePolicy(BootstrapResponse.MobilePolicyDto.builder()
                        .imageAnnotationEnabled(appSettingsService.isImageAnnotationEnabled())
                        .nfcStrictSerialMatch(appSettingsService.isNfcStrictSerialMatch())
                        .nfcManualEntryEnabled(appSettingsService.isNfcManualEntryEnabled())
                        .build())
                .deviceRetention(toDto(appSettingsService.getDeviceRetention()))
                .offlineSession(toDto(appSettingsService.getOfflineSessionLimit()))
                .requiredFormLookups(formLookups)
                .build();
    }

    private static LogSheetContextDto.RequiredLookup toRequiredLookup(LookupList list) {
        return new LogSheetContextDto.RequiredLookup(list.getCode(), list.getName(), list.getRevision());
    }

    private static BootstrapResponse.OfflineSessionDto toDto(AppSettingsService.OfflineSessionLimit limit) {
        return BootstrapResponse.OfflineSessionDto.builder()
                .limitEnabled(limit.enabled())
                .limitHours(limit.hours())
                .build();
    }

    private static BootstrapResponse.DeviceRetentionDto toDto(AppSettingsService.DeviceRetention retention) {
        return BootstrapResponse.DeviceRetentionDto.builder()
                .syncedRetentionHours(retention.syncedRetentionHours())
                .unsyncedPurgeEnabled(retention.unsyncedPurgeEnabled())
                .unsyncedRetentionHours(retention.unsyncedRetentionHours())
                .build();
    }
}
