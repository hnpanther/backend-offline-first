package com.hnp.backendofflinefirst.service.alarm;

import com.hnp.backendofflinefirst.domain.AlarmCloseReason;
import com.hnp.backendofflinefirst.domain.AlarmPriority;
import com.hnp.backendofflinefirst.domain.AlarmRuleScope;
import com.hnp.backendofflinefirst.domain.AlarmState;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.entity.Alarm;
import com.hnp.backendofflinefirst.entity.AlarmRule;
import com.hnp.backendofflinefirst.entity.AlarmRuleField;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AlarmRepository;
import com.hnp.backendofflinefirst.repository.AlarmRuleFieldRepository;
import com.hnp.backendofflinefirst.repository.AlarmRuleRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Writing down which parameters raise an alarm (V18: one rule, many parameters, one default).
 *
 * <h2>The two kinds of rule</h2>
 * <ul>
 *   <li><b>The default rule</b> ({@link AlarmRuleScope#ALL_FIELDS}) — at most one. Governs every
 *       parameter no specific rule lists: «three dangers in a row on anything».</li>
 *   <li><b>Specific rules</b> ({@link AlarmRuleScope#FIELDS}) — one or many parameters each, and a
 *       parameter in at most one of them. They override the default for exactly what they list,
 *       <em>even when switched off</em>: a switched-off specific rule silences its parameters,
 *       which is the only way to exempt one from the default.</li>
 * </ul>
 *
 * <p>Every value is checked here, not in the form — a form is not a boundary — and the CHECKs and
 * unique indexes of V17/V18 are the last line, whose errors would reach an administrator as a
 * constraint name.
 */
@Service
@RequiredArgsConstructor
public class AlarmRuleService {

    /** The types whose readings carry a severity at all — {@code FieldValidationSupport.evaluateValue}. */
    public static final Set<String> ALARMABLE_TYPES = Set.of("number", "select", "multiselect");

    public static final String DEFAULT_RULE_NAME = "قاعده عمومی — همه پارامترها";
    static final int MAX_NAME = 200;
    static final int MAX_FIELDS_PER_RULE = 500;

    private final AlarmRuleRepository ruleRepository;
    private final AlarmRuleFieldRepository ruleFieldRepository;
    private final AlarmRepository alarmRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final AlarmLifecycle lifecycle;

    /**
     * What an administrator submits. Raw, as the form sends it.
     *
     * @param scope              {@code ALL_FIELDS} or {@code FIELDS}; ignored on update — a rule's
     *                           scope is its identity
     * @param fieldDefinitionIds the parameters of a {@code FIELDS} rule; ignored for the default
     */
    public record RuleForm(String name, String scope, Collection<Long> fieldDefinitionIds,
                           String triggerSeverity, Integer consecutiveReadings,
                           Integer clearAfterReadings, Integer windowDays, String priority,
                           String expectedAction, boolean active) {}

    /**
     * Every rule, each with its parameter list attached — two statements however many rules there
     * are (the rules, then every link). The only correct way to read a rule's list: a rule read
     * straight from {@link AlarmRuleRepository} has an empty one.
     *
     * <p>Also called by the evaluation, inside its own transaction, which it simply joins.
     */
    @Transactional(readOnly = true)
    public List<AlarmRule> all() {
        List<AlarmRule> rules = ruleRepository.findAllByOrderByIdAsc();
        Map<Long, Set<Long>> fieldsByRule = new HashMap<>();
        for (AlarmRuleField link : ruleFieldRepository.findAllByOrderByAlarmRuleIdAscFieldDefinitionIdAsc()) {
            fieldsByRule.computeIfAbsent(link.getAlarmRuleId(), k -> new LinkedHashSet<>())
                    .add(link.getFieldDefinitionId());
        }
        for (AlarmRule rule : rules) {
            // A fresh set every time: inside one transaction these are the same managed instances
            // a previous call handed out, and an edit in progress must not see a stale list.
            rule.setFieldDefinitionIds(fieldsByRule.getOrDefault(rule.getId(), new LinkedHashSet<>()));
        }
        return rules;
    }

    /** Parameter id → the rule that lists it. For the page, and for the «already in a rule» check. */
    @Transactional(readOnly = true)
    public Map<Long, AlarmRule> ruleByField() {
        Map<Long, AlarmRule> out = new LinkedHashMap<>();
        for (AlarmRule rule : all()) {
            for (Long fieldId : rule.getFieldDefinitionIds()) {
                out.put(fieldId, rule);
            }
        }
        return out;
    }

    @Transactional
    public AlarmRule create(RuleForm form, Long actorUserId) {
        AlarmRuleScope scope = parseScope(form.scope());
        AlarmRule rule = new AlarmRule();
        rule.setScope(scope);
        if (scope == AlarmRuleScope.ALL_FIELDS) {
            if (ruleRepository.findFirstByScope(AlarmRuleScope.ALL_FIELDS).isPresent()) {
                throw new IllegalArgumentException("قاعده عمومی قبلاً تعریف شده است؛ همان را ویرایش کنید.");
            }
            rule.setName(nameOr(form.name(), DEFAULT_RULE_NAME));
        } else {
            Set<Long> fields = validatedFields(form.fieldDefinitionIds(), null);
            rule.setFieldDefinitionIds(fields);
            rule.setName(nameOr(form.name(), derivedName(fields)));
        }
        apply(rule, form);
        long now = System.currentTimeMillis();
        rule.setCreatedByUserId(actorUserId);
        rule.setUpdatedByUserId(actorUserId);
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        Set<Long> fields = rule.getFieldDefinitionIds();
        AlarmRule saved = ruleRepository.save(rule);
        writeFields(saved.getId(), Set.of(), fields);
        saved.setFieldDefinitionIds(fields);
        return saved;
    }

    /**
     * Edits a rule. Its scope is its identity and does not change.
     *
     * <p>Live alarms keep the parameters they were raised under (frozen onto the alarm); the edit
     * describes the alarms this rule will raise from now on. A parameter taken out of a specific
     * rule goes back to the default rule. Switching a rule off closes the live alarms it raised —
     * «stop bothering anyone about this» has to include the alarms already in a queue.
     */
    @Transactional
    public AlarmRule update(Long id, RuleForm form, Long actorUserId) {
        AlarmRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("قاعده آلارم پیدا نشد."));
        boolean wasActive = rule.isActive();
        Set<Long> current = currentFields(rule.getId());
        Set<Long> fields = new LinkedHashSet<>();
        if (rule.getScope() == AlarmRuleScope.FIELDS) {
            fields = validatedFields(form.fieldDefinitionIds(), rule.getId());
            rule.setName(nameOr(form.name(), derivedName(fields)));
        } else {
            rule.setName(nameOr(form.name(), DEFAULT_RULE_NAME));
        }
        apply(rule, form);
        long now = System.currentTimeMillis();
        rule.setUpdatedByUserId(actorUserId);
        rule.setUpdatedAt(now);
        AlarmRule saved = ruleRepository.save(rule);
        writeFields(saved.getId(), current, fields);
        saved.setFieldDefinitionIds(fields);
        if (wasActive && !saved.isActive()) {
            for (Alarm alarm : alarmRepository.findByAlarmRuleIdAndStateIn(saved.getId(), AlarmState.LIVE)) {
                lifecycle.close(alarm, AlarmCloseReason.RULE_DISABLED, actorUserId, null, now);
                alarmRepository.save(alarm);
            }
        }
        return saved;
    }

    /**
     * Deletes a rule that has never raised anything. One that has is history — its alarms point at
     * it — and is switched off instead.
     */
    @Transactional
    public void delete(Long id) {
        AlarmRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("قاعده آلارم پیدا نشد."));
        if (alarmRepository.existsByAlarmRuleId(rule.getId())) {
            throw new IllegalStateException("این قاعده تاکنون آلارم ایجاد کرده و تاریخچه دارد؛ "
                    + "به‌جای حذف، غیرفعالش کنید.");
        }
        // The FK would cascade these, but removed one by one through the repository each one is
        // audited (RepositoryAuditAspect watches delete, not deleteAll), and the deletes are
        // queued before the rule's own.
        for (AlarmRuleField link : ruleFieldRepository.findByAlarmRuleId(rule.getId())) {
            ruleFieldRepository.delete(link);
        }
        ruleRepository.delete(rule);
    }

    private Set<Long> currentFields(Long ruleId) {
        Set<Long> out = new LinkedHashSet<>();
        for (AlarmRuleField link : ruleFieldRepository.findByAlarmRuleId(ruleId)) {
            out.add(link.getFieldDefinitionId());
        }
        return out;
    }

    /**
     * Brings a rule's rows from {@code before} to {@code after} by difference: only what was taken
     * out is deleted and only what was added is inserted. A wholesale delete-then-insert would put
     * a removal and an insertion of the same key in one flush, and Hibernate runs inserts before
     * deletes — a primary-key violation on every save that kept a parameter.
     *
     * <p>Row by row through {@code save} and {@code delete}, never {@code saveAll}/{@code deleteAll}:
     * {@code RepositoryAuditAspect} intercepts the former only, and a rule's parameter list is an
     * administrative decision that belongs in {@code audit_log}. A rule lists at most
     * {@value #MAX_FIELDS_PER_RULE}; this is an administrator's click, not a hot path.
     */
    private void writeFields(Long ruleId, Set<Long> before, Set<Long> after) {
        List<AlarmRuleField> removed = ruleFieldRepository.findByAlarmRuleId(ruleId).stream()
                .filter(link -> before.contains(link.getFieldDefinitionId())
                        && !after.contains(link.getFieldDefinitionId()))
                .toList();
        if (!removed.isEmpty()) {
            for (AlarmRuleField link : removed) {
                ruleFieldRepository.delete(link);
            }
            ruleFieldRepository.flush();
        }
        for (Long fieldId : after) {
            if (!before.contains(fieldId)) {
                ruleFieldRepository.save(new AlarmRuleField(ruleId, fieldId));
            }
        }
    }

    /**
     * The parameters of a specific rule, each one checked: it exists, it is on, it can carry a
     * severity, and no OTHER rule lists it.
     *
     * @param ruleId the rule being edited, whose own parameters are not «taken»; null on create
     */
    private Set<Long> validatedFields(Collection<Long> requested, Long ruleId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (requested != null) {
            requested.stream().filter(Objects::nonNull).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("دست‌کم یک پارامتر برای این قاعده انتخاب کنید.");
        }
        if (ids.size() > MAX_FIELDS_PER_RULE) {
            throw new IllegalArgumentException("یک قاعده حداکثر " + MAX_FIELDS_PER_RULE
                    + " پارامتر می‌گیرد؛ برای همه پارامترها از قاعده عمومی استفاده کنید.");
        }
        Map<Long, FieldDefinition> fields = new LinkedHashMap<>();
        fieldDefinitionRepository.findAllById(ids).forEach(f -> fields.put(f.getId(), f));
        Map<Long, AlarmRule> taken = ruleByField();
        for (Long id : ids) {
            FieldDefinition field = fields.get(id);
            if (field == null) {
                throw new IllegalArgumentException("یکی از پارامترهای انتخاب‌شده پیدا نشد.");
            }
            String label = field.getLabel() != null ? field.getLabel() : field.getKey();
            AlarmRule owner = taken.get(id);
            boolean alreadyHere = owner != null && owner.getId().equals(ruleId);
            // A parameter switched off after it was put in this rule may stay: it reads nothing,
            // so it raises nothing, and refusing it would make the rule uneditable until somebody
            // found and unticked it. Only ADDING a switched-off parameter is refused.
            if (field.isDeleted() && !alreadyHere) {
                throw new IllegalArgumentException("پارامتر «" + label + "» غیرفعال است و قرائتی نمی‌گیرد.");
            }
            if (!ALARMABLE_TYPES.contains(field.getDataType())) {
                throw new IllegalArgumentException("پارامتر «" + label + "» شدت ندارد؛ فقط پارامترهای "
                        + "عددی، انتخابی و چندانتخابی می‌توانند آلارم بدهند.");
            }
            if (owner != null && !alreadyHere) {
                throw new IllegalArgumentException("پارامتر «" + label + "» در قاعده «" + owner.getName()
                        + "» است؛ هر پارامتر فقط در یک قاعده می‌آید. اول از آن قاعده برش دارید.");
            }
        }
        return ids;
    }

    private String derivedName(Set<Long> fieldIds) {
        FieldDefinition first = fieldDefinitionRepository.findById(fieldIds.iterator().next()).orElse(null);
        String label = first == null ? "پارامتر" : (first.getLabel() != null ? first.getLabel() : first.getKey());
        return fieldIds.size() == 1 ? label : label + " و " + (fieldIds.size() - 1) + " پارامتر دیگر";
    }

    private static String nameOr(String requested, String fallback) {
        String trimmed = requested == null ? "" : requested.trim();
        String name = trimmed.isEmpty() ? fallback : trimmed;
        return name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME);
    }

    private static AlarmRuleScope parseScope(String raw) {
        if (raw != null && raw.trim().equalsIgnoreCase(AlarmRuleScope.ALL_FIELDS.name())) {
            return AlarmRuleScope.ALL_FIELDS;
        }
        if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase(AlarmRuleScope.FIELDS.name())) {
            return AlarmRuleScope.FIELDS;
        }
        throw new IllegalArgumentException("نوع قاعده نامعتبر است.");
    }

    private static void apply(AlarmRule rule, RuleForm form) {
        FieldValidationSeverity trigger = parseTrigger(form.triggerSeverity());
        int consecutive = bounded(form.consecutiveReadings(), 1, "تعداد قرائت پیاپی");
        int clearAfter = bounded(form.clearAfterReadings(), 1, "تعداد قرائت عادی برای رفع");
        Integer window = form.windowDays();
        if (window != null && (window < 1 || window > 365)) {
            throw new IllegalArgumentException("بازه زمانی باید بین ۱ تا ۳۶۵ روز باشد، یا خالی بماند.");
        }
        AlarmPriority priority = AlarmPriority.fromNullable(form.priority());
        if (priority == null) {
            throw new IllegalArgumentException("اولویت آلارم را انتخاب کنید.");
        }
        String action = AlarmLifecycle.trim(form.expectedAction());
        if (action == null) {
            // ISA-18.2: an alarm with no defined response is not an alarm.
            throw new IllegalArgumentException("اقدام مورد انتظار را بنویسید — آلارمی که پاسخ "
                    + "تعریف‌شده ندارد، فقط مزاحمت است.");
        }
        rule.setTriggerSeverity(trigger);
        rule.setConsecutiveReadings(consecutive);
        rule.setClearAfterReadings(clearAfter);
        rule.setWindowDays(window);
        rule.setPriority(priority);
        rule.setExpectedAction(action);
        rule.setActive(form.active());
    }

    private static FieldValidationSeverity parseTrigger(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (FieldValidationSeverity.WARNING.name().equalsIgnoreCase(value)) {
            return FieldValidationSeverity.WARNING;
        }
        if (FieldValidationSeverity.DANGER.name().equalsIgnoreCase(value)) {
            return FieldValidationSeverity.DANGER;
        }
        throw new IllegalArgumentException("شدت آغازگر باید «هشدار یا بدتر» یا «خطر» باشد.");
    }

    private static int bounded(Integer value, int fallback, String label) {
        int resolved = value == null ? fallback : value;
        if (resolved < 1 || resolved > 20) {
            throw new IllegalArgumentException(label + " باید بین ۱ تا ۲۰ باشد.");
        }
        return resolved;
    }
}
