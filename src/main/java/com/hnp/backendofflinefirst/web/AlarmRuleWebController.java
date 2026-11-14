package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.AlarmWebhookState;
import com.hnp.backendofflinefirst.entity.AlarmRule;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AlarmWebhookDeliveryRepository;
import com.hnp.backendofflinefirst.repository.AlarmWebhookEndpointRepository;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.alarm.AlarmRuleService;
import com.hnp.backendofflinefirst.service.alarm.AlarmRuleService.RuleForm;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Administration of alarms: the default rule and the specific rules.
 *
 * <p>The webhook receivers and their delivery log have their own page since V21
 * ({@link AlarmWebhookWebController}); this one only says how many receivers are active and how
 * many deliveries wait or failed, with a link there.
 */
@Controller
@RequiredArgsConstructor
public class AlarmRuleWebController {

    /**
     * Two administrators saving at once can both pass the «one rule per parameter» check and meet
     * in {@code uq_alarm_rule_fields_field} (or {@code ux_alarm_rules_one_default}) at commit. The
     * index is right and the second save is refused whole; this says so instead of a 500.
     */
    static final String CONCURRENT_EDIT = "هم‌زمان قاعده دیگری همین پارامتر (یا قاعده عمومی) را ثبت کرد؛ "
            + "صفحه را دوباره باز کنید و دوباره ذخیره کنید. چیزی ذخیره نشد.";

    private final AlarmRuleService ruleService;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final AssetClassRepository assetClassRepository;
    private final AlarmWebhookDeliveryRepository deliveryRepository;
    private final AlarmWebhookEndpointRepository endpointRepository;

    /**
     * One selectable parameter.
     *
     * @param searchText class, label and key together, for the picker's filter
     * @param ownerRuleId the rule that already lists it, or null
     */
    public record FieldOption(Long id, String classLabel, String label, String key, String dataType,
                              Long ownerRuleId, String ownerRuleName, String searchText) {}

    /** The picker groups parameters under their class, so «which class is this» is never a question. */
    public record FieldGroup(String classLabel, List<FieldOption> options) {}

    @GetMapping("/alarm-rules")
    @PreAuthorize("hasAuthority('GET:/alarm-rules')")
    public String page(@RequestParam(required = false) Long edit, Model model) {
        List<AlarmRule> rules = ruleService.all();
        Map<Long, AlarmRule> owners = ruleService.ruleByField();
        Map<Long, AssetClass> classes = assetClassRepository.findAll().stream()
                .collect(Collectors.toMap(AssetClass::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        Map<Long, FieldOption> optionById = new LinkedHashMap<>();
        fieldDefinitionRepository.findAll().stream()
                .filter(f -> !f.isDeleted() && AlarmRuleService.ALARMABLE_TYPES.contains(f.getDataType()))
                .map(f -> option(f, classes, owners))
                .sorted(Comparator.comparing(FieldOption::classLabel)
                        .thenComparing(FieldOption::label, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(o -> optionById.put(o.id(), o));
        // A rule may list a parameter switched off since; it still needs a name on this page.
        for (AlarmRule rule : rules) {
            for (Long fieldId : rule.getFieldDefinitionIds()) {
                if (!optionById.containsKey(fieldId)) {
                    fieldDefinitionRepository.findById(fieldId)
                            .ifPresent(f -> optionById.put(fieldId, option(f, classes, owners)));
                }
            }
        }

        AlarmRule defaultRule = rules.stream().filter(AlarmRule::isDefaultRule).findFirst().orElse(null);
        List<AlarmRule> specificRules = rules.stream().filter(r -> !r.isDefaultRule()).toList();
        AlarmRule editing = edit == null ? null
                : rules.stream().filter(r -> r.getId().equals(edit)).findFirst().orElse(null);

        Map<Long, List<FieldOption>> ruleFields = new LinkedHashMap<>();
        for (AlarmRule rule : specificRules) {
            ruleFields.put(rule.getId(), rule.getFieldDefinitionIds().stream()
                    .map(optionById::get).filter(java.util.Objects::nonNull).toList());
        }

        model.addAttribute("activePage", "alarm-rules");
        model.addAttribute("defaultRule", defaultRule);
        model.addAttribute("specificRules", specificRules);
        model.addAttribute("ruleFields", ruleFields);
        model.addAttribute("fieldGroups", groups(optionById.values().stream()
                .filter(o -> AlarmRuleService.ALARMABLE_TYPES.contains(o.dataType())).toList()));
        model.addAttribute("fieldCount", optionById.size());
        model.addAttribute("editing", editing);
        model.addAttribute("editingFieldIds", editing == null ? Set.of() : Set.copyOf(editing.getFieldDefinitionIds()));
        model.addAttribute("noFields", Set.of());
        model.addAttribute("activeWebhooks", endpointRepository.findByActiveTrueOrderByIdAsc().size());
        model.addAttribute("pendingDeliveries", deliveryRepository.countByState(AlarmWebhookState.PENDING));
        model.addAttribute("failedDeliveries", deliveryRepository.countByState(AlarmWebhookState.FAILED));
        return "alarm-rules";
    }

    @PostMapping("/alarm-rules")
    @PreAuthorize("hasAuthority('POST:/alarm-rules')")
    public String create(@RequestParam(required = false) String name,
                         @RequestParam(required = false) String scope,
                         @RequestParam(required = false) List<Long> fieldDefinitionIds,
                         @RequestParam(required = false) String triggerSeverity,
                         @RequestParam(required = false) Integer consecutiveReadings,
                         @RequestParam(required = false) Integer clearAfterReadings,
                         @RequestParam(required = false) Integer windowDays,
                         @RequestParam(required = false) String priority,
                         @RequestParam(required = false) String expectedAction,
                         RedirectAttributes ra) {
        try {
            // Created switched on: a rule created switched off is a rule somebody will believe works.
            ruleService.create(new RuleForm(name, scope, fieldDefinitionIds, triggerSeverity,
                    consecutiveReadings, clearAfterReadings, windowDays, priority, expectedAction, true),
                    SecurityUtils.currentUserId());
            ra.addFlashAttribute("successMessage", "قاعده آلارم ثبت شد.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            ra.addFlashAttribute("errorMessage", CONCURRENT_EDIT);
        }
        return "redirect:/alarm-rules";
    }

    /**
     * {@code active} is a checkbox with one name and one value ({@code true}). A browser sends
     * nothing for an unticked box, and this endpoint is only ever posted by the edit form, which
     * always renders the box — so «absent» can only mean «unticked», and reads as switched off.
     * No hidden field shares the name: two values under one name is the shape AGENTS.md #171
     * records breaking a checkbox.
     */
    @PostMapping("/alarm-rules/{id}")
    @PreAuthorize("hasAuthority('POST:/alarm-rules/{id}')")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) String name,
                         @RequestParam(required = false) List<Long> fieldDefinitionIds,
                         @RequestParam(required = false) String triggerSeverity,
                         @RequestParam(required = false) Integer consecutiveReadings,
                         @RequestParam(required = false) Integer clearAfterReadings,
                         @RequestParam(required = false) Integer windowDays,
                         @RequestParam(required = false) String priority,
                         @RequestParam(required = false) String expectedAction,
                         @RequestParam(required = false) String active,
                         RedirectAttributes ra) {
        try {
            ruleService.update(id, new RuleForm(name, null, fieldDefinitionIds, triggerSeverity,
                    consecutiveReadings, clearAfterReadings, windowDays, priority, expectedAction,
                    "true".equals(active)), SecurityUtils.currentUserId());
            ra.addFlashAttribute("successMessage", "قاعده آلارم ذخیره شد.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
            return "redirect:/alarm-rules?edit=" + id;
        } catch (DataIntegrityViolationException e) {
            ra.addFlashAttribute("errorMessage", CONCURRENT_EDIT);
            return "redirect:/alarm-rules?edit=" + id;
        }
        return "redirect:/alarm-rules";
    }

    @PostMapping("/alarm-rules/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/alarm-rules/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            ruleService.delete(id);
            ra.addFlashAttribute("successMessage", "قاعده آلارم حذف شد.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/alarm-rules";
    }

    private static FieldOption option(FieldDefinition field, Map<Long, AssetClass> classes,
                                      Map<Long, AlarmRule> owners) {
        String classLabel = classLabel(classes.get(field.getClassId()), field.getClassId());
        String label = field.getLabel() != null ? field.getLabel() : field.getKey();
        if (field.isDeleted()) {
            // Only reachable for a parameter a rule already lists: it may stay, never be added.
            label = label + " (غیرفعال)";
        }
        AlarmRule owner = owners.get(field.getId());
        return new FieldOption(field.getId(), classLabel, label, field.getKey(), field.getDataType(),
                owner == null ? null : owner.getId(), owner == null ? null : owner.getName(),
                (classLabel + " " + label + " " + field.getKey()));
    }

    private static List<FieldGroup> groups(List<FieldOption> options) {
        Map<String, List<FieldOption>> byClass = new LinkedHashMap<>();
        for (FieldOption o : options) {
            byClass.computeIfAbsent(o.classLabel(), k -> new ArrayList<>()).add(o);
        }
        List<FieldGroup> out = new ArrayList<>();
        byClass.forEach((label, list) -> out.add(new FieldGroup(label, list)));
        return out;
    }

    private static String classLabel(AssetClass assetClass, Long classId) {
        if (assetClass == null) {
            return classId == null ? "—" : "#" + classId;
        }
        return assetClass.getName();
    }
}
