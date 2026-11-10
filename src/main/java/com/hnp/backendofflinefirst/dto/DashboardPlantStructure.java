package com.hnp.backendofflinefirst.dto;

/**
 * The plant at a glance, in the dashboard's header card: how much there is to watch.
 *
 * <p>Plant-wide counts, so only ever built for a plant-wide viewer
 * ({@code DashboardService.plantStructureOrNull}). A unit-scoped viewer is shown nothing rather
 * than numbers that are not theirs — the reason these counts once left the dashboard altogether.
 */
public record DashboardPlantStructure(long activeTemplates, long assetClasses, long operationalUnits) {}
