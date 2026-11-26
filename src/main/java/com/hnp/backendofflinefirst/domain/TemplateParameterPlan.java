package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;

import java.util.*;
import java.util.stream.Collectors;

/** Computes both the editable preview and the dictionary issued to a round. */
public record TemplateParameterPlan(Map<Long, List<FieldDefinitionSnapshot>> classes,
                                    List<AssetFields> assets, List<String> problems) {
    public record AssetFields(Long id, String code, String name, Long classId,
                              List<FieldDefinitionSnapshot> effective,
                              List<FieldDefinitionSnapshot> selected) {}

    public static class InvalidSelection extends IllegalArgumentException {
        public InvalidSelection(String message) { super(message); }
    }

    public static TemplateParameterPlan resolve(List<FieldDefinitionSnapshot> base, List<AssetEntry> equipment,
                                                TemplateParameterRules recipe, boolean editing) {
        var rules = recipe == null ? TemplateParameterRules.all() : recipe;
        Map<Long, List<FieldDefinitionSnapshot>> classes = base.stream().collect(Collectors.groupingBy(
                FieldDefinitionSnapshot::getClassId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<FieldDefinition>> definitions = new HashMap<>();
        var problems = new ArrayList<String>();
        var rows = new ArrayList<AssetFields>();
        Set<Long> checkedClasses = new HashSet<>();
        for (AssetEntry asset : equipment) {
            Long classId = asset.getClassId();
            var classFields = classes.computeIfAbsent(classId, ignored -> List.of());
            var fields = definitions.computeIfAbsent(classId, ignored -> FieldDefinitionSnapshot.toFieldDefinitions(classFields));
            var classRule = rules.classes().getOrDefault(classId,
                    new TemplateParameterRules.ClassRule(rules.defaultMode(), Set.of()));
            if (checkedClasses.add(classId)) {
                checkBindings(classRule.bindings(), classFields, "class " + classId, problems);
            }
            var exception = rules.assets().getOrDefault(asset.getId(),
                    new TemplateParameterRules.AssetRule(Set.of(), Set.of(), false));
            var effective = new ArrayList<FieldDefinitionSnapshot>();
            var selected = new ArrayList<FieldDefinitionSnapshot>();
            for (var field : AssetFieldResolver.applyAssetOverrides(fields, FieldSource.from(asset.getFieldSource()),
                    asset.getFieldOverrides(), classId)) {
                FieldDefinitionSnapshot snapshot = FieldDefinitionSnapshot.from(field);
                // A compatible override keeps its source. A structural change gets the asset's own binding.
                if (field.getBindingId() == null || classFields.stream().anyMatch(original ->
                        Objects.equals(original.getId(), field.getId()) && original.getId() != null &&
                        (!Objects.equals(original.getUnit(), field.getUnit()) ||
                         !Objects.equals(original.getDataType(), field.getDataType())))) {
                    var binding = asset.getFieldBindings() == null ? null :
                            asset.getFieldBindings().get(field.getKey().trim().toLowerCase(Locale.ROOT));
                    snapshot.setBindingId(binding == null ? null : binding.id());
                }
                effective.add(snapshot);
                String binding = Objects.toString(snapshot.getBindingId(), "");
                boolean checked = classRule.bindings().contains(binding);
                boolean included = classRule.mode() == TemplateParameterRules.Mode.ONLY ? checked : !checked;
                if ((included || exception.include().contains(binding)) && !exception.exclude().contains(binding)) {
                    selected.add(snapshot);
                }
            }
            checkBindings(exception.include(), effective, "asset " + asset.getAssetCode(), problems);
            checkBindings(exception.exclude(), effective, "asset " + asset.getAssetCode(), problems);
            if (recipe != null && selected.isEmpty() && !exception.allowEmpty()) {
                problems.add("Empty parameter list: " + asset.getAssetCode());
            }
            if (recipe != null) checkDependencies(asset, selected, problems);
            rows.add(new AssetFields(asset.getId(), asset.getAssetCode(), asset.getAssetName(), classId, effective, selected));
        }
        if (editing && (!checkedClasses.containsAll(rules.classes().keySet()) ||
                !equipment.stream().map(AssetEntry::getId).collect(Collectors.toSet()).containsAll(rules.assets().keySet()))) {
            problems.add("Parameter target outside template scope.");
        }
        return new TemplateParameterPlan(classes, rows, problems);
    }

    private static void checkBindings(Set<String> requested, List<FieldDefinitionSnapshot> fields,
                                      String target, List<String> problems) {
        if (requested.isEmpty()) return;
        var missing = new LinkedHashSet<>(requested);
        fields.forEach(f -> missing.remove(Objects.toString(f.getBindingId(), "")));
        if (!missing.isEmpty()) problems.add("Missing parameter binding: " + target + " (" + missing.size() + ")");
    }

    private static void checkDependencies(AssetEntry asset, List<FieldDefinitionSnapshot> selected, List<String> problems) {
        Set<String> keys = selected.stream().map(FieldDefinitionSnapshot::getKey).collect(Collectors.toSet());
        for (var field : selected) {
            var parentList = FieldValidationSupport.lookupParentList(field.getValidation());
            if (parentList != null && selected.stream().noneMatch(parent -> parent.getValidation() != null
                    && !Objects.equals(parent.getKey(), field.getKey())
                    && parentList.equalsIgnoreCase(Objects.toString(parent.getValidation().get("lookupList"), "")))) {
                problems.add("Missing conditional parameter: " + asset.getAssetCode() + " / " + field.getKey() + " → " + parentList);
            }
            if (field.getValidation() == null || !(field.getValidation().get("conditions") instanceof List<?> conditions)) continue;
            for (Object item : conditions) {
                if (item instanceof Map<?, ?> condition && condition.get("when") instanceof Map<?, ?> gate &&
                        gate.get("field") instanceof String dependency && !keys.contains(dependency)) {
                    problems.add("Missing conditional parameter: " + asset.getAssetCode() + " / " + field.getKey() + " → " + dependency);
                }
            }
        }
    }

    public void requireValid() {
        if (!problems.isEmpty()) throw new InvalidSelection(String.join(" | ", problems));
    }

    public IssuedEntrySchema freeze(boolean filtered) {
        requireValid();
        Map<List<FieldDefinitionSnapshot>, Integer> dictionary = new LinkedHashMap<>();
        Map<String, Integer> byAsset = new LinkedHashMap<>();
        for (var asset : assets) {
            int index = dictionary.computeIfAbsent(asset.selected(), ignored -> dictionary.size());
            byAsset.put(asset.id().toString(), index);
        }
        return new IssuedEntrySchema(1, filtered, List.copyOf(dictionary.keySet()), byAsset);
    }
}
