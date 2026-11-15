package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.LookupValues;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LookupItem;
import com.hnp.backendofflinefirst.entity.LookupList;
import com.hnp.backendofflinefirst.repository.LookupItemRepository;
import com.hnp.backendofflinefirst.repository.LookupListRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Puts every lookup answer of a form into its stored shape — {@code {code, label, state}} — on
 * every write path, beside {@code SignatureValues.stamp} (roadmap §31, Phase 2).
 *
 * <h2>What it decides</h2>
 *
 * <ul>
 *   <li><b>The label.</b> A code the list knows takes the list's label at this moment — a client
 *       cannot store a forged name against a real person's code. Only an unknown code keeps the
 *       client's label (it is all there is), and its {@code UNKNOWN} state says so.</li>
 *   <li><b>The verdict</b> — {@code ACTIVE}, {@code INACTIVE}, {@code UNKNOWN}. Never a refusal:
 *       see {@link LookupValues}.</li>
 *   <li><b>Nothing, for an answer that did not change.</b> An element whose code the previous
 *       value already held is carried over byte for byte. So re-saving a form writes no revision,
 *       a rename of the item afterwards does not rewrite what was recorded, and an item retired
 *       after it was chosen stays «active at entry».</li>
 * </ul>
 *
 * <h2>What it never does</h2>
 *
 * <p>It never drops an answer. A value it cannot read (which validation refuses before any write
 * path gets here) is passed through untouched rather than discarded, and a list that no longer
 * exists makes every code {@code UNKNOWN}, not absent. Losing an operator's answer to a list
 * problem is the one outcome worse than a flag.
 *
 * <p>One query per referenced list per save, and none at all for a form with no lookup field or
 * whose lookup answers are all unchanged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LookupAnswerService {

    private final LookupListRepository listRepository;
    private final LookupItemRepository itemRepository;

    /**
     * @param formData the answers about to be stored (already validated and narrowed to the
     *                 definitions); returned unchanged when no lookup field is among them
     * @param previous what is stored now, or null
     * @param defs     the definitions those answers are judged by — the sheet's frozen ones
     * @return a new map with every lookup answer stamped; the same map when there is nothing to do
     */
    public Map<String, Object> stamp(Map<String, Object> formData, Map<String, Object> previous,
                                     List<FieldDefinition> defs) {
        if (formData == null || formData.isEmpty() || defs == null || defs.isEmpty()) {
            return formData;
        }
        List<FieldDefinition> lookupDefs = new ArrayList<>();
        for (FieldDefinition def : defs) {
            if (def != null && def.getKey() != null && LookupValues.isLookupField(def.getDataType())
                    && formData.containsKey(def.getKey())) {
                lookupDefs.add(def);
            }
        }
        if (lookupDefs.isEmpty()) {
            return formData;
        }

        // Pass 1: what each field asks for, and which codes need the database at all.
        Map<String, List<LookupValues.Choice>> wanted = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Object>>> carried = new HashMap<>();
        Map<String, Set<String>> toResolve = new LinkedHashMap<>();
        for (FieldDefinition def : lookupDefs) {
            List<LookupValues.Choice> choices = LookupValues.parseIncoming(formData.get(def.getKey()));
            if (choices == null) {
                log.warn("Lookup answer for '{}' is unreadable and is stored as sent", def.getKey());
                continue;
            }
            wanted.put(def.getKey(), choices);
            Map<String, Map<String, Object>> before = new HashMap<>();
            for (Map<String, Object> element : LookupValues.storedChoices(previous == null ? null : previous.get(def.getKey()))) {
                if (LookupValues.State.fromStored(element.get(LookupValues.KEY_STATE)) != null) {
                    before.put(String.valueOf(element.get(LookupValues.KEY_CODE)).toLowerCase(Locale.ROOT), element);
                }
            }
            carried.put(def.getKey(), before);
            String list = FieldValidationSupport.lookupList(def.getValidation());
            for (LookupValues.Choice choice : choices) {
                String lower = choice.code().toLowerCase(Locale.ROOT);
                if (!before.containsKey(lower) && list != null) {
                    toResolve.computeIfAbsent(list.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>()).add(lower);
                }
            }
        }

        // Pass 2: one query per list.
        Map<String, Map<String, LookupItem>> found = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : toResolve.entrySet()) {
            Map<String, LookupItem> byCode = new HashMap<>();
            LookupList list = listRepository.findByCodeIgnoreCase(entry.getKey()).orElse(null);
            if (list != null) {
                for (LookupItem item : itemRepository.findByListIdAndLowerCodeIn(list.getId(), entry.getValue())) {
                    byCode.put(item.getCode().toLowerCase(Locale.ROOT), item);
                }
            }
            found.put(entry.getKey(), byCode);
        }

        // Pass 3: the stored shape.
        Map<String, Object> out = new LinkedHashMap<>(formData);
        for (FieldDefinition def : lookupDefs) {
            List<LookupValues.Choice> choices = wanted.get(def.getKey());
            if (choices == null) {
                continue;
            }
            String list = FieldValidationSupport.lookupList(def.getValidation());
            Map<String, LookupItem> byCode = list == null ? Map.of()
                    : found.getOrDefault(list.toLowerCase(Locale.ROOT), Map.of());
            Map<String, Map<String, Object>> before = carried.getOrDefault(def.getKey(), Map.of());
            List<Map<String, Object>> elements = new ArrayList<>(choices.size());
            for (LookupValues.Choice choice : choices) {
                String lower = choice.code().toLowerCase(Locale.ROOT);
                Map<String, Object> kept = before.get(lower);
                if (kept != null) {
                    elements.add(kept);
                    continue;
                }
                LookupItem item = byCode.get(lower);
                if (item == null) {
                    elements.add(LookupValues.element(choice.code(), choice.label(), LookupValues.State.UNKNOWN));
                } else {
                    elements.add(LookupValues.element(item.getCode(), item.getLabel(),
                            item.isActive() ? LookupValues.State.ACTIVE : LookupValues.State.INACTIVE));
                }
            }
            Object value = LookupValues.toValue(elements, LookupValues.isMulti(def.getDataType()));
            if (value == null) {
                out.remove(def.getKey());
            } else {
                out.put(def.getKey(), value);
            }
        }
        return out;
    }
}
