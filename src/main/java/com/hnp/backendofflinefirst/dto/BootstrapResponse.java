package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Lightweight mobile bootstrap payload: user context and accessible operational
 * units only — no plant hierarchy or asset registry.
 */
@Data
@Builder
public class BootstrapResponse {
    private Long serverTime;
    private Long userId;
    private List<OperationalUnit> operationalUnits;
    private Set<Long> accessibleUnitIds;
    private Set<Long> supervisorScopeUnitIds;
    private Long primaryUnitId;
}
