package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Reference data scoped to a single log sheet: hierarchy slice, assets, and form
 * field definitions required to fill the sheet offline.
 */
@Data
@Builder
public class LogSheetContextDto {
    private List<Location> locations;
    private List<PlantSystem> plantSystems;
    private List<MainFunction> mainFunctions;
    private List<SubFunction> subFunctions;
    private List<AssetEntry> assetEntries;
    private List<AssetClass> assetClasses;
    private List<FieldDefinition> fieldDefinitions;
    private String scopeDisplayLabel;

    /**
     * The round's <b>own</b> questions, resolved exactly as the web fill page resolves them —
     * {@code LogSheetFieldService.resolveForSheet}: the sheet's frozen copy when it has one, the
     * live list otherwise.
     *
     * <p>Why this exists beside the sheet's own {@code sheetFieldDefinitionsSnapshot}: that
     * column is <b>null on a custom sheet until its first answer</b>, because a custom sheet is
     * created before its questions are written and freezes them only when somebody answers. The
     * tablet read only the column, so an approver could add questions to a custom asset-less
     * round, an operator could pick it up — and see an empty round, with no way to give the first
     * answer that would have frozen them. Resolving here, rather than writing the snapshot onto
     * the sheet, keeps the freeze where it belongs: reading a bundle must never fix the wording
     * an approver is still allowed to change.
     */
    private List<FieldDefinition> sheetFieldDefinitions;

    /**
     * The reference lists this round's lookup fields draw from, with the revision each stands at
     * now (roadmap §31 Phase 2) — what the device must hold, at least at that revision, before a
     * lookup field can offer a search instead of code-only entry.
     *
     * <p>Additive: a build that ignores it fills a lookup field by code, which the server
     * resolves. Never the items themselves — those travel once per device through
     * {@code GET /api/lookups/{code}/items}, not once per round in every bundle.
     */
    private List<RequiredLookup> requiredLookups;

    /** One list a round needs: its code (the identity), its name, its current revision. */
    public record RequiredLookup(String code, String name, long revision) {}
}
