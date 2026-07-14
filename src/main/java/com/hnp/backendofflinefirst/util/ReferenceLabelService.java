package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public Map<Long, String> locationLabels() {
        return locationRepository.findAllByOrderByIdDesc().stream()
                .collect(Collectors.toMap(Location::getId, this::locationLabel, (a, b) -> a, LinkedHashMap::new));
    }

    public String locationLabel(Long id) {
        if (id == null) return "—";
        return locationRepository.findById(id)
                .map(this::locationLabel)
                .orElse(String.valueOf(id));
    }

    private String locationLabel(Location l) {
        return pick(l.getName(), l.getCode(), l.getId());
    }

    public Map<Long, String> plantSystemLabels() {
        return plantSystemRepository.findAllByOrderByIdDesc().stream()
                .collect(Collectors.toMap(PlantSystem::getId, this::plantSystemLabel, (a, b) -> a, LinkedHashMap::new));
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

    public Map<Long, String> mainFunctionLabels() {
        return mainFunctionRepository.findAllByOrderByIdDesc().stream()
                .collect(Collectors.toMap(MainFunction::getId, this::mainFunctionLabel, (a, b) -> a, LinkedHashMap::new));
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

    public Map<Long, String> subFunctionLabels() {
        return subFunctionRepository.findAllByOrderByIdDesc().stream()
                .collect(Collectors.toMap(SubFunction::getId, this::subFunctionLabel, (a, b) -> a, LinkedHashMap::new));
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

    public Map<Long, String> assetClassLabels() {
        return assetClassRepository.findAllByOrderByIdDesc().stream()
                .collect(Collectors.toMap(AssetClass::getId, AssetClass::getName, (a, b) -> a, LinkedHashMap::new));
    }

    public String assetClassLabel(Long id) {
        if (id == null) return "—";
        return assetClassRepository.findById(id)
                .map(AssetClass::getName)
                .orElse(String.valueOf(id));
    }

    public Map<Long, String> userDisplayNames() {
        return userRepository.findAllByOrderByIdDesc().stream()
                .collect(Collectors.toMap(User::getId, this::formatUser, (a, b) -> a, LinkedHashMap::new));
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
        String typeFa = switch (scopeType != null ? scopeType : "") {
            case "location" -> "مکان";
            case "system" -> "سیستم";
            case "mainFunction" -> "تابع اصلی";
            default -> scopeType != null ? scopeType : "محدوده";
        };
        String code = scopeCode(scopeType, scopeId);
        return typeFa + ": " + code;
    }

    /** Hierarchy scope plus required asset class, e.g. «مکان: LOC-01 · کلاس: پمپ». */
    public String templateScopeDisplayLabel(String scopeType, Long scopeId, Long classId) {
        String hierarchy = scopeDisplayLabel(scopeType, scopeId);
        if (classId == null) {
            return hierarchy;
        }
        return hierarchy + " · کلاس: " + assetClassLabel(classId);
    }

    public String scopeCode(String scopeType, Long scopeId) {
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
            default -> String.valueOf(scopeId);
        };
    }

    /** Parent label for hierarchy entities: location parent, system→location, etc. */
    public String parentLabelForLocation(Long parentId) {
        return locationLabel(parentId);
    }

    public String parentLabelForSystem(Long locationId) {
        return locationLabel(locationId);
    }

    public String parentLabelForPlantSystemParent(Long parentId) {
        return plantSystemLabel(parentId);
    }

    public String parentLabelForMainFunction(MainFunction mf) {
        if (mf.getSystemId() != null) return plantSystemLabel(mf.getSystemId());
        if (mf.getLocationId() != null) return locationLabel(mf.getLocationId());
        return "—";
    }

    public String parentLabelForSubFunction(SubFunction sf) {
        if (sf.getMainFunctionId() != null) return mainFunctionLabel(sf.getMainFunctionId());
        if (sf.getSystemId() != null) return plantSystemLabel(sf.getSystemId());
        if (sf.getLocationId() != null) return locationLabel(sf.getLocationId());
        return "—";
    }

    public String parentLabelForOperationalUnit(Long parentId) {
        return operationalUnitLabel(parentId);
    }

    public String parentLabelForAssetEntry(AssetEntry ae) {
        return subFunctionLabel(ae.getSubFunctionId());
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
