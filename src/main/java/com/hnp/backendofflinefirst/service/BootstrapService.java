package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.BootstrapResponse;
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

        return BootstrapResponse.builder()
                .serverTime(System.currentTimeMillis())
                .userId(userId)
                .operationalUnits(operationalUnits)
                .accessibleUnitIds(accessibleUnitIds)
                .supervisorScopeUnitIds(supervisorScopeUnitIds)
                .primaryUnitId(unitScopeService.getPrimaryUnitId(userId))
                .build();
    }
}
