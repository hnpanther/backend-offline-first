package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves entity IDs to human-readable labels for list/detail views. */
@Service("labels")
@RequiredArgsConstructor
public class ReferenceLabelService {

    private final LocationRepository locationRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final AssetClassRepository assetClassRepository;
    private final UserRepository userRepository;

    public String locationLabel(Long id) {
        if (id == null) return "—";
        return locationRepository.findById(id)
                .map(this::locationLabel)
                .orElse(String.valueOf(id));
    }

    private String locationLabel(Location l) {
        return pick(l.getName(), l.getCode(), l.getId());
    }

    public String plantSystemLabel(Long id) {
        if (id == null) return "—";
        return plantSystemRepository.findById(id)
                .map(this::plantSystemLabel)
                .orElse(String.valueOf(id));
    }

    private String plantSystemLabel(PlantSystem s) {
        return pick(s.getName(), s.getCode(), s.getId());
    }

    public String mainFunctionLabel(Long id) {
        if (id == null) return "—";
        return mainFunctionRepository.findById(id)
                .map(this::mainFunctionLabel)
                .orElse(String.valueOf(id));
    }

    private String mainFunctionLabel(MainFunction mf) {
        return pick(mf.getName(), mf.getCode(), mf.getId());
    }

    public String subFunctionLabel(Long id) {
        if (id == null) return "—";
        return subFunctionRepository.findById(id)
                .map(this::subFunctionLabel)
                .orElse(String.valueOf(id));
    }

    private String subFunctionLabel(SubFunction sf) {
        return pick(sf.getName(), sf.getCode(), sf.getId());
    }

    public Map<Long, String> operationalUnitLabels() {
        return operationalUnitRepository.findAllByOrderByIdDesc().stream()
                .collect(Collectors.toMap(OperationalUnit::getId, this::operationalUnitLabel, (a, b) -> a, LinkedHashMap::new));
    }

    public String operationalUnitLabel(Long id) {
        if (id == null) return "—";
        return operationalUnitRepository.findById(id)
                .map(this::operationalUnitLabel)
                .orElse(String.valueOf(id));
    }

    private String operationalUnitLabel(OperationalUnit u) {
        return pick(u.getName(), u.getCode(), u.getId());
    }

    /**
     * Unit label with its code, e.g. «تعميرات برق سایت (DEP-129)». Used where the unit has to be
     * identified unambiguously — plant-wide there are hundreds of units and names repeat.
     */
    public String operationalUnitCodeAndName(Long id) {
        if (id == null) return "—";
        return operationalUnitRepository.findById(id)
                .map(u -> {
                    String name = operationalUnitLabel(u);
                    return u.getCode() != null && !u.getCode().isBlank()
                            ? name + " (" + u.getCode() + ")"
                            : name;
                })
                .orElse("—");
    }

    public String assetClassLabel(Long id) {
        if (id == null) return "—";
        return assetClassRepository.findById(id)
                .map(AssetClass::getName)
                .orElse(String.valueOf(id));
    }

    /**
     * Batch-resolve asset class labels for list pages (avoids per-row {@code findById}).
     * Missing rows fall back to the id string, matching {@link #assetClassLabel(Long)}.
     */
    public Map<Long, String> assetClassLabelsFor(Collection<Long> ids) {
        Set<Long> wanted = nonNullIds(ids);
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> found = assetClassRepository.findAllById(wanted).stream()
                .collect(Collectors.toMap(AssetClass::getId, AssetClass::getName, (a, b) -> a, LinkedHashMap::new));
        return fillMissing(wanted, found);
    }

    /** Build class label map from entities already loaded for the page (e.g. edit dropdown). */
    public Map<Long, String> assetClassLabelsFrom(Collection<AssetClass> classes) {
        if (classes == null || classes.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> map = new LinkedHashMap<>();
        for (AssetClass ac : classes) {
            if (ac == null || ac.getId() == null) {
                continue;
            }
            map.put(ac.getId(), ac.getName() != null ? ac.getName() : String.valueOf(ac.getId()));
        }
        return map;
    }

    /**
     * Batch-resolve sub-function labels for list pages (avoids per-row {@code findById}).
     * Missing rows fall back to the id string, matching {@link #subFunctionLabel(Long)}.
     */
    public Map<Long, String> subFunctionLabelsFor(Collection<Long> ids) {
        Set<Long> wanted = nonNullIds(ids);
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> found = subFunctionRepository.findAllById(wanted).stream()
                .collect(Collectors.toMap(SubFunction::getId, this::subFunctionLabel, (a, b) -> a, LinkedHashMap::new));
        return fillMissing(wanted, found);
    }

    private static Set<Long> nonNullIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Map<Long, String> fillMissing(Set<Long> wanted, Map<Long, String> found) {
        Map<Long, String> result = new LinkedHashMap<>();
        for (Long id : wanted) {
            result.put(id, found.getOrDefault(id, String.valueOf(id)));
        }
        return result;
    }

    public String userDisplayName(Long id) {
        if (id == null) return "—";
        return userRepository.findById(id)
                .map(this::formatUser)
                .orElse("کاربر #" + id);
    }

    private String formatUser(User u) {
        if (u == null) return "—";
        if (u.getFullName() != null && !u.getFullName().isBlank()) {
            return u.getFullName() + " (" + u.getUsername() + ")";
        }
        return u.getUsername();
    }

    /** e.g. «مکان: LOC-01» instead of «location:5». */
    public String scopeDisplayLabel(String scopeType, Long scopeId) {
        if (scopeId == null) return "—";
        String code = scopeCode(scopeType, scopeId);
        return scopeTypeFa(scopeType) + ": " + code;
    }

    /**
     * The Persian name of a scope type. Extracted so the batch label builders below produce the
     * exact same prefix as {@link #scopeDisplayLabel} — two copies of this switch would drift.
     */
    private static String scopeTypeFa(String scopeType) {
        return switch (scopeType != null ? scopeType : "") {
            case "location" -> "مکان";
            case "system" -> "سیستم";
            case "mainFunction" -> "تابع اصلی";
            case "subFunction" -> "تابع فرعی";
            default -> scopeType != null ? scopeType : "محدوده";
        };
    }

    /** Hierarchy scope plus required asset class, e.g. «مکان: LOC-01 · کلاس: پمپ». */
    private String templateScopeDisplayLabel(String scopeType, Long scopeId, Long classId) {
        String hierarchy = scopeDisplayLabel(scopeType, scopeId);
        if (classId == null) {
            return hierarchy;
        }
        return hierarchy + " · کلاس: " + assetClassLabel(classId);
    }

    /**
     * Where a template's assets come from. An EXPLICIT template has no hierarchy scope and no
     * single class, so rendering {@link #templateScopeDisplayLabel} for it would show a bare
     * «—»; name the mode instead.
     */
    public String templateAssetSourceLabel(AssetSelectionMode mode, String scopeType, Long scopeId, Long classId) {
        if (mode == AssetSelectionMode.EXPLICIT) {
            return "فهرست دستی دارایی‌ها";
        }
        return templateScopeDisplayLabel(scopeType, scopeId, classId);
    }

    private String scopeCode(String scopeType, Long scopeId) {
        if (scopeId == null) return "—";
        return switch (scopeType != null ? scopeType : "") {
            case "location" -> locationRepository.findById(scopeId)
                    .map(l -> codeAndTitle(l.getCode(), l.getName(), l.getId()))
                    .orElse(String.valueOf(scopeId));
            case "system" -> plantSystemRepository.findById(scopeId)
                    .map(s -> codeAndTitle(s.getCode(), s.getName(), s.getId()))
                    .orElse(String.valueOf(scopeId));
            case "mainFunction" -> mainFunctionRepository.findById(scopeId)
                    .map(mf -> codeAndTitle(mf.getCode(), mf.getName(), mf.getId()))
                    .orElse(String.valueOf(scopeId));
            case "subFunction" -> subFunctionRepository.findById(scopeId)
                    .map(sf -> codeAndTitle(sf.getCode(), sf.getName(), sf.getId()))
                    .orElse(String.valueOf(scopeId));
            default -> String.valueOf(scopeId);
        };
    }

    /** Parses stored scopeSummary (type:id) into a readable label. */
    public String formatScopeSummary(String scopeSummary) {
        if (scopeSummary == null || scopeSummary.isBlank()) return "—";
        int colon = scopeSummary.indexOf(':');
        if (colon <= 0) return scopeSummary;
        String type = scopeSummary.substring(0, colon).trim();
        try {
            Long id = Long.parseLong(scopeSummary.substring(colon + 1).trim());
            return scopeDisplayLabel(type, id);
        } catch (NumberFormatException e) {
            return scopeSummary;
        }
    }

    public String scopeLabel(String scopeType, Long scopeId) {
        if (scopeId == null) return "—";
        return switch (scopeType != null ? scopeType : "") {
            case "location" -> locationLabel(scopeId);
            case "system" -> plantSystemLabel(scopeId);
            case "mainFunction" -> mainFunctionLabel(scopeId);
            case "subFunction" -> subFunctionLabel(scopeId);
            default -> String.valueOf(scopeId);
        };
    }

    /*
     * Parent label for hierarchy entities: location parent, system→location, etc.
     *
     * No list page calls these any more — each one now reads a map built once for the page by the
     * batch builders further down. They are kept on purpose, for two reasons.
     *
     * They are the specification of what a parent label says. Two formats live in this service and
     * they are easy to confuse: `parentLabelForLocation` gives «نیروگاه» while `parentLabelForSystem`,
     * for that same location, gives «مکان: LOC-01 - نیروگاه». ReferenceLabelBatchEquivalenceTest
     * drives both paths over one fake database and asserts they agree row by row — which only works
     * while the per-row original is here to compare against. Delete these and the batch builders
     * still work, but nothing proves they render what they replaced.
     *
     * And a detail page — one row, one parent — wants exactly this and not a map.
     */
    public String parentLabelForLocation(Long parentId) {
        return locationLabel(parentId);
    }

    public String parentLabelForSystem(Long locationId) {
        return scopeDisplayLabel("location", locationId);
    }

    public String parentLabelForPlantSystemParent(Long parentId) {
        if (parentId == null) {
            return "—";
        }
        return scopeDisplayLabel("system", parentId);
    }

    public String parentLabelForMainFunction(MainFunction mf) {
        if (mf.getParentId() != null) {
            return scopeDisplayLabel("mainFunction", mf.getParentId());
        }
        if (mf.getSystemId() != null) {
            return scopeDisplayLabel("system", mf.getSystemId());
        }
        if (mf.getLocationId() != null) {
            return scopeDisplayLabel("location", mf.getLocationId());
        }
        return "—";
    }

    public String parentLabelForSubFunction(SubFunction sf) {
        if (sf.getParentId() != null) {
            return scopeDisplayLabel("subFunction", sf.getParentId());
        }
        if (sf.getMainFunctionId() != null) {
            return scopeDisplayLabel("mainFunction", sf.getMainFunctionId());
        }
        if (sf.getSystemId() != null) {
            return scopeDisplayLabel("system", sf.getSystemId());
        }
        if (sf.getLocationId() != null) {
            return scopeDisplayLabel("location", sf.getLocationId());
        }
        return "—";
    }

    public String parentLabelForOperationalUnit(Long parentId) {
        return operationalUnitLabel(parentId);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Batch parent labels for list pages
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /*
     * Each per-row `parentLabelForX` helper above does a `findById`, so a list page pays one query
     * per distinct parent. Measured: 147 on /locations, 164 on /plant-systems, ~250 on
     * /main-functions — the last of which is bounded today only by the page size, since the table
     * holds 1143 rows and would issue 1143 queries in one request if that size were raised.
     *
     * The builders below produce the same labels for a whole page in a fixed number of queries.
     * Three things about them are deliberate:
     *
     *  1. Every map is keyed by THE ROW'S OWN ID, never by the parent id. A row id is never null,
     *     so a template can index the map directly. A parent id frequently IS null, and SpEL
     *     throws on a null map index instead of yielding null (AGENTS.md) — keying by the row
     *     keeps that decision in Java, where it is tested.
     *
     *  2. The text comes from the same private formatters the per-row helpers use, so the rendered
     *     string is identical. The two formats are NOT interchangeable: for one and the same
     *     location, `parentLabelForLocation` yields «نیروگاه» (pick: name, else code, else id)
     *     while `parentLabelForSystem` yields «مکان: LOC-01 - نیروگاه» (a Persian type prefix over
     *     codeAndTitle). Swapping one for the other silently changes what the page says.
     *
     *  3. A parent id that no longer resolves falls back to the id as a string, exactly as
     *     `findById(...).orElse(String.valueOf(id))` does today. A dangling reference keeps
     *     rendering the number rather than turning into «—», which would read as "no parent".
     */

    /** Parent labels for a page of locations, keyed by each row's own id. One query. */
    public Map<Long, String> parentLabelsForLocations(Collection<Location> rows) {
        Map<Long, Location> parents = fetchLocations(collectIds(rows, Location::getParentId));
        return perRow(rows, Location::getId,
                row -> plainLabel(row.getParentId(), parents, this::locationLabel));
    }

    /** «سیستم: …» labels for the parent system of each row on /plant-systems. One query. */
    public Map<Long, String> parentSystemLabelsForPlantSystems(Collection<PlantSystem> rows) {
        Map<Long, PlantSystem> parents = fetchPlantSystems(collectIds(rows, PlantSystem::getParentId));
        return perRow(rows, PlantSystem::getId,
                row -> systemScopeLabel(row.getParentId(), parents));
    }

    /** «مکان: …» labels for the location of each row on /plant-systems. One query. */
    public Map<Long, String> locationLabelsForPlantSystems(Collection<PlantSystem> rows) {
        Map<Long, Location> locations = fetchLocations(collectIds(rows, PlantSystem::getLocationId));
        return perRow(rows, PlantSystem::getId,
                row -> locationScopeLabel(row.getLocationId(), locations));
    }

    /**
     * Parent labels for /main-functions. Mirrors {@link #parentLabelForMainFunction}: parent main
     * function first, then system, then location. Three queries for the page, not one per row.
     */
    public Map<Long, String> parentLabelsForMainFunctions(Collection<MainFunction> rows) {
        Map<Long, MainFunction> mains = fetchMainFunctions(collectIds(rows, MainFunction::getParentId));
        Map<Long, PlantSystem> systems = fetchPlantSystems(collectIds(rows, MainFunction::getSystemId));
        Map<Long, Location> locations = fetchLocations(collectIds(rows, MainFunction::getLocationId));
        return perRow(rows, MainFunction::getId, row -> {
            if (row.getParentId() != null) {
                return mainFunctionScopeLabel(row.getParentId(), mains);
            }
            if (row.getSystemId() != null) {
                return systemScopeLabel(row.getSystemId(), systems);
            }
            if (row.getLocationId() != null) {
                return locationScopeLabel(row.getLocationId(), locations);
            }
            return DASH;
        });
    }

    /**
     * Parent labels for /sub-functions. Mirrors {@link #parentLabelForSubFunction}: parent sub
     * function, then main function, then system, then location. Four queries for the page.
     */
    public Map<Long, String> parentLabelsForSubFunctions(Collection<SubFunction> rows) {
        Map<Long, SubFunction> subs = fetchSubFunctions(collectIds(rows, SubFunction::getParentId));
        Map<Long, MainFunction> mains = fetchMainFunctions(collectIds(rows, SubFunction::getMainFunctionId));
        Map<Long, PlantSystem> systems = fetchPlantSystems(collectIds(rows, SubFunction::getSystemId));
        Map<Long, Location> locations = fetchLocations(collectIds(rows, SubFunction::getLocationId));
        return perRow(rows, SubFunction::getId, row -> {
            if (row.getParentId() != null) {
                return scopeLabel("subFunction", row.getParentId(), subs,
                        sf -> codeAndTitle(sf.getCode(), sf.getName(), sf.getId()));
            }
            if (row.getMainFunctionId() != null) {
                return mainFunctionScopeLabel(row.getMainFunctionId(), mains);
            }
            if (row.getSystemId() != null) {
                return systemScopeLabel(row.getSystemId(), systems);
            }
            if (row.getLocationId() != null) {
                return locationScopeLabel(row.getLocationId(), locations);
            }
            return DASH;
        });
    }

    /** Parent labels for a page of operational units, keyed by each row's own id. One query. */
    public Map<Long, String> parentLabelsForOperationalUnits(Collection<OperationalUnit> rows) {
        Map<Long, OperationalUnit> parents =
                fetchOperationalUnits(collectIds(rows, OperationalUnit::getParentId));
        return perRow(rows, OperationalUnit::getId,
                row -> plainLabel(row.getParentId(), parents, this::operationalUnitLabel));
    }

    /**
     * Unit labels for the nested badge loop on /locations, which walks unit ids per location and
     * called {@code operationalUnitLabel} for each. One query for every unit referenced by the page.
     */
    public Map<Long, String> operationalUnitLabelsFor(Collection<Long> unitIds) {
        Set<Long> wanted = nonNullIds(unitIds);
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> found = operationalUnitRepository.findAllById(wanted).stream()
                .collect(Collectors.toMap(OperationalUnit::getId, this::operationalUnitLabel,
                        (a, b) -> a, LinkedHashMap::new));
        return fillMissing(wanted, found);
    }

    /**
     * Batch twin of {@link #formatScopeSummary}, keyed by the stored summary string itself.
     *
     * <p>Keyed by the string rather than by a row id because two pages need it and one of them
     * ({@code /my-inbox}) renders two separate lists; a single map covers both, and identical
     * scopes across rows collapse to one entry. Callers must guard the null case in the template
     * — a null summary is not a key, and SpEL throws on a null map index.
     *
     * <p>Every input string that is not null comes back as a key, including a blank or malformed
     * one, so a lookup never yields null for a row that has a summary at all. Malformed input is
     * passed through unchanged, exactly as the per-row helper does: {@code "location"} with no
     * colon, or {@code "location:abc"} with an unparseable id, is shown rather than swallowed.
     */
    public Map<String, String> scopeSummaryLabels(Collection<String> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return Map.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String summary : summaries) {
            if (summary != null) {
                distinct.add(summary);
            }
        }
        if (distinct.isEmpty()) {
            return Map.of();
        }

        // Group the ids that actually need loading, by the table they live in.
        Map<String, Set<Long>> idsByType = new LinkedHashMap<>();
        Map<String, Long> parsed = new LinkedHashMap<>();
        for (String summary : distinct) {
            int colon = summary.indexOf(':');
            if (summary.isBlank() || colon <= 0) {
                continue;
            }
            try {
                Long id = Long.parseLong(summary.substring(colon + 1).trim());
                String type = summary.substring(0, colon).trim();
                parsed.put(summary, id);
                idsByType.computeIfAbsent(type, t -> new LinkedHashSet<>()).add(id);
            } catch (NumberFormatException e) {
                // left unparsed; rendered verbatim below, as formatScopeSummary does
            }
        }

        Map<Long, Location> locs = fetchLocations(idsByType.getOrDefault("location", Set.of()));
        Map<Long, PlantSystem> syss = fetchPlantSystems(idsByType.getOrDefault("system", Set.of()));
        Map<Long, MainFunction> mfs = fetchMainFunctions(idsByType.getOrDefault("mainFunction", Set.of()));
        Map<Long, SubFunction> sfs = fetchSubFunctions(idsByType.getOrDefault("subFunction", Set.of()));

        Map<String, String> result = new LinkedHashMap<>();
        for (String summary : distinct) {
            if (summary.isBlank()) {
                result.put(summary, DASH);
                continue;
            }
            Long id = parsed.get(summary);
            if (id == null) {
                result.put(summary, summary);        // no colon, or an id that is not a number
                continue;
            }
            String type = summary.substring(0, summary.indexOf(':')).trim();
            result.put(summary, switch (type) {
                case "location" -> locationScopeLabel(id, locs);
                case "system" -> systemScopeLabel(id, syss);
                case "mainFunction" -> mainFunctionScopeLabel(id, mfs);
                case "subFunction" -> scopeLabel("subFunction", id, sfs,
                        sf -> codeAndTitle(sf.getCode(), sf.getName(), sf.getId()));
                default -> scopeTypeFa(type) + ": " + id;
            });
        }
        return result;
    }

    // ── the shared plumbing ──────────────────────────────────────────────────────────────────

    private static final String DASH = "—";

    private Map<Long, Location> fetchLocations(Set<Long> ids) {
        return ids.isEmpty() ? Map.of() : indexBy(locationRepository.findAllById(ids), Location::getId);
    }

    private Map<Long, PlantSystem> fetchPlantSystems(Set<Long> ids) {
        return ids.isEmpty() ? Map.of() : indexBy(plantSystemRepository.findAllById(ids), PlantSystem::getId);
    }

    private Map<Long, MainFunction> fetchMainFunctions(Set<Long> ids) {
        return ids.isEmpty() ? Map.of() : indexBy(mainFunctionRepository.findAllById(ids), MainFunction::getId);
    }

    private Map<Long, SubFunction> fetchSubFunctions(Set<Long> ids) {
        return ids.isEmpty() ? Map.of() : indexBy(subFunctionRepository.findAllById(ids), SubFunction::getId);
    }

    private Map<Long, OperationalUnit> fetchOperationalUnits(Set<Long> ids) {
        return ids.isEmpty() ? Map.of()
                : indexBy(operationalUnitRepository.findAllById(ids), OperationalUnit::getId);
    }

    private String locationScopeLabel(Long id, Map<Long, Location> loaded) {
        return scopeLabel("location", id, loaded, l -> codeAndTitle(l.getCode(), l.getName(), l.getId()));
    }

    private String systemScopeLabel(Long id, Map<Long, PlantSystem> loaded) {
        return scopeLabel("system", id, loaded, s -> codeAndTitle(s.getCode(), s.getName(), s.getId()));
    }

    private String mainFunctionScopeLabel(Long id, Map<Long, MainFunction> loaded) {
        return scopeLabel("mainFunction", id, loaded,
                mf -> codeAndTitle(mf.getCode(), mf.getName(), mf.getId()));
    }

    /** The batch twin of {@link #scopeDisplayLabel}: «‹نوع›: ‹code - name›», «—» for a null id. */
    private static <T> String scopeLabel(String scopeType, Long id, Map<Long, T> loaded,
                                         Function<T, String> codeOf) {
        if (id == null) {
            return DASH;
        }
        T entity = loaded.get(id);
        return scopeTypeFa(scopeType) + ": " + (entity != null ? codeOf.apply(entity) : String.valueOf(id));
    }

    /** The batch twin of the bare {@code xxxLabel(Long)} helpers: pick(name, code, id). */
    private static <T> String plainLabel(Long id, Map<Long, T> loaded, Function<T, String> labelOf) {
        if (id == null) {
            return DASH;
        }
        T entity = loaded.get(id);
        return entity != null ? labelOf.apply(entity) : String.valueOf(id);
    }

    private static <T> Set<Long> collectIds(Collection<T> rows, Function<T, Long> idOf) {
        if (rows == null || rows.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (T row : rows) {
            Long id = row != null ? idOf.apply(row) : null;
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static <T> Map<Long, T> indexBy(Iterable<T> entities, Function<T, Long> idOf) {
        Map<Long, T> map = new LinkedHashMap<>();
        for (T entity : entities) {
            Long id = idOf.apply(entity);
            if (id != null) {
                map.put(id, entity);
            }
        }
        return map;
    }

    private static <T> Map<Long, String> perRow(Collection<T> rows, Function<T, Long> idOf,
                                                Function<T, String> labelOf) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (rows == null) {
            return map;
        }
        for (T row : rows) {
            Long id = row != null ? idOf.apply(row) : null;
            if (id != null) {
                map.put(id, labelOf.apply(row));
            }
        }
        return map;
    }

    private static String pick(String name, String code, Long id) {
        if (name != null && !name.isBlank()) return name;
        if (code != null && !code.isBlank()) return code;
        return String.valueOf(id);
    }

    public static String codeAndTitle(String code, String name, Long id) {
        boolean hasCode = code != null && !code.isBlank();
        boolean hasName = name != null && !name.isBlank();
        if (hasCode && hasName) return code + " - " + name;
        if (hasCode) return code;
        if (hasName) return name;
        return String.valueOf(id);
    }
}
