package com.hnp.backendofflinefirst.domain;

import java.util.Map;
import java.util.Set;

/** Targets are actual class and asset ids. Each checklist contains persistent source bindings. */
public record TemplateParameterRules(Mode defaultMode, Map<Long, ClassRule> classes,
                                     Map<Long, AssetRule> assets) {
    public enum Mode { ONLY, ALL_EXCEPT }
    public record ClassRule(Mode mode, Set<String> bindings) {}
    public record AssetRule(Set<String> include, Set<String> exclude, boolean allowEmpty) {}

    public void validate() {
        if (defaultMode == null || classes == null || assets == null ||
                classes.values().stream().anyMatch(r -> r == null || r.mode() == null || r.bindings() == null) ||
                assets.values().stream().anyMatch(r -> r == null || r.include() == null || r.exclude() == null ||
                        !java.util.Collections.disjoint(r.include(), r.exclude()))) {
            throw new IllegalArgumentException("Invalid parameter rules.");
        }
    }

    public static TemplateParameterRules all() {
        return new TemplateParameterRules(Mode.ALL_EXCEPT, Map.of(), Map.of());
    }
}
