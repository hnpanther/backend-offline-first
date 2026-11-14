package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.AlarmWebhookEvents;
import com.hnp.backendofflinefirst.domain.AlarmWebhookRuleScope;
import com.hnp.backendofflinefirst.domain.AlarmWebhookState;
import com.hnp.backendofflinefirst.entity.AlarmRule;
import com.hnp.backendofflinefirst.entity.AlarmWebhookEndpoint;
import com.hnp.backendofflinefirst.repository.AlarmWebhookDeliveryRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.alarm.AlarmRuleService;
import com.hnp.backendofflinefirst.service.alarm.AlarmWebhookDispatcher;
import com.hnp.backendofflinefirst.service.alarm.AlarmWebhookEndpointService;
import com.hnp.backendofflinefirst.service.alarm.AlarmWebhookEndpointService.Form;
import com.hnp.backendofflinefirst.service.alarm.AlarmWebhookTargetPolicy;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The alarm webhook receivers (V21): register, edit, switch off, test, delete — and the log of
 * what was sent to them.
 *
 * <p><b>What the page never shows.</b> An endpoint's API key and signing secret after they are
 * saved (the secret is shown once, as a flash attribute, when it is created or regenerated); a
 * receiver's response body (only its status code is kept); and, to anyone who may only look, the
 * endpoint's full address — the list shows scheme, host and port, because a receiver's path or
 * query often <em>is</em> its credential. The full address appears in the edit form, to those who
 * may edit.
 */
@Controller
@RequiredArgsConstructor
public class AlarmWebhookWebController {

    static final int DELIVERY_PAGE = 50;

    private final AlarmWebhookEndpointService endpointService;
    private final AlarmWebhookDeliveryRepository deliveryRepository;
    private final AlarmWebhookDispatcher dispatcher;
    private final AlarmWebhookTargetPolicy targetPolicy;
    private final AlarmRuleService ruleService;

    /** Persian names of the events, for the checkboxes and the list. */
    static final Map<String, String> EVENT_LABELS = Map.of(
            AlarmWebhookEvents.ALARM_RAISED, "ایجاد آلارم",
            AlarmWebhookEvents.ALARM_ESCALATED, "تشدید آلارم",
            AlarmWebhookEvents.WEBHOOK_TEST, "ارسال آزمایشی");

    public record EventOption(String name, String label) {}

    /** One alarm rule a receiver can be for — the default rule first, labelled for what it covers. */
    public record RuleOption(Long id, String label, boolean defaultRule, boolean active) {

        static RuleOption of(AlarmRule rule) {
            String label = rule.isDefaultRule()
                    ? "قاعده عمومی (همه پارامترهای بدون قاعده اختصاصی)"
                    : rule.getName();
            return new RuleOption(rule.getId(), label, rule.isDefaultRule(), rule.isActive());
        }
    }

    /** One endpoint as the list shows it — nothing secret, and no path or query. */
    public record EndpointView(Long id, String name, String target, boolean https, boolean active,
                               boolean hasApiKey, boolean includeContactDetails, List<String> eventLabels,
                               boolean allRules, List<String> ruleLabels) {

        static EndpointView of(AlarmWebhookEndpoint e, Set<Long> ruleIds, Map<Long, RuleOption> rules) {
            Set<String> events = AlarmWebhookEvents.parse(e.getEventTypes());
            List<String> labels = events.isEmpty()
                    ? List.of("همه رویدادها")
                    : AlarmWebhookEvents.SUBSCRIBABLE.stream().filter(events::contains).map(EVENT_LABELS::get).toList();
            boolean allRules = e.getRuleScope() != AlarmWebhookRuleScope.SELECTED;
            List<String> ruleLabels = allRules || ruleIds == null ? List.of()
                    : ruleIds.stream()
                        .map(id -> rules.containsKey(id) ? rules.get(id).label() : "#" + id)
                        .toList();
            return new EndpointView(e.getId(), e.getName(), AlarmWebhookEndpointService.displayTarget(e.getUrl()),
                    e.getUrl().regionMatches(true, 0, "https://", 0, 8), e.isActive(),
                    e.getApiKey() != null && !e.getApiKey().isBlank(), e.isIncludeContactDetails(), labels,
                    allRules, ruleLabels);
        }
    }

    @GetMapping("/alarm-webhooks")
    @PreAuthorize("hasAuthority('GET:/alarm-webhooks')")
    public String page(@RequestParam(required = false) Long edit, Model model) {
        List<AlarmWebhookEndpoint> endpoints = endpointService.all();
        AlarmWebhookEndpoint editing = edit == null ? null
                : endpoints.stream().filter(e -> e.getId().equals(edit)).findFirst().orElse(null);
        Map<Long, Set<Long>> rulesByEndpoint = endpointService.rulesByEndpoint(endpoints);
        // The default rule first, then the specific ones by name — the order the picker shows.
        Map<Long, RuleOption> ruleOptions = new LinkedHashMap<>();
        ruleService.all().stream()
                .sorted(Comparator.comparing((AlarmRule r) -> !r.isDefaultRule())
                        .thenComparing(AlarmRule::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(rule -> ruleOptions.put(rule.getId(), RuleOption.of(rule)));

        model.addAttribute("activePage", "alarm-webhooks");
        model.addAttribute("endpoints", endpoints.stream()
                .map(e -> EndpointView.of(e, rulesByEndpoint.get(e.getId()), ruleOptions)).toList());
        model.addAttribute("ruleOptions", List.copyOf(ruleOptions.values()));
        model.addAttribute("editingRuleIds", editing == null ? Set.of()
                : rulesByEndpoint.getOrDefault(editing.getId(), Set.of()));
        model.addAttribute("editingAllRules", editing == null || editing.getRuleScope() != AlarmWebhookRuleScope.SELECTED);
        model.addAttribute("editing", editing);
        model.addAttribute("editingEvents", editing == null ? Set.of() : AlarmWebhookEvents.parse(editing.getEventTypes()));
        model.addAttribute("eventOptions", AlarmWebhookEvents.SUBSCRIBABLE.stream()
                .map(name -> new EventOption(name, EVENT_LABELS.get(name))).toList());
        model.addAttribute("eventLabels", EVENT_LABELS);
        model.addAttribute("allowedHosts", targetPolicy.allowedHosts());
        model.addAttribute("deliveries", deliveryRepository
                .findAllByOrderByIdDesc(PageRequest.of(0, DELIVERY_PAGE)).getContent());
        model.addAttribute("pendingDeliveries", deliveryRepository.countByState(AlarmWebhookState.PENDING));
        model.addAttribute("failedDeliveries", deliveryRepository.countByState(AlarmWebhookState.FAILED));
        return "alarm-webhooks";
    }

    @PostMapping("/alarm-webhooks")
    @PreAuthorize("hasAuthority('POST:/alarm-webhooks')")
    public String create(@RequestParam(required = false) String name,
                         @RequestParam(required = false) String url,
                         @RequestParam(required = false) String apiKey,
                         @RequestParam(defaultValue = "false") boolean includeContactDetails,
                         @RequestParam(required = false) List<String> eventTypes,
                         @RequestParam(defaultValue = "false") boolean active,
                         @RequestParam(required = false) String ruleScope,
                         @RequestParam(required = false) List<Long> ruleIds,
                         RedirectAttributes ra) {
        try {
            var created = endpointService.create(
                    new Form(name, url, apiKey, false, includeContactDetails, eventTypes, active,
                            AlarmWebhookRuleScope.parse(ruleScope), ruleIds),
                    SecurityUtils.currentUserId());
            flashSecret(ra, created);
            ra.addFlashAttribute("successMessage", "وب‌هوک «" + created.endpoint().getName() + "» ثبت شد.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            // Two administrators saving the same name at once: ux_alarm_webhook_endpoints_name_lower.
            ra.addFlashAttribute("errorMessage", ErrorTranslator.dataIntegrityViolation(e));
        }
        return "redirect:/alarm-webhooks";
    }

    @PostMapping("/alarm-webhooks/{id}")
    @PreAuthorize("hasAuthority('POST:/alarm-webhooks/{id}')")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) String name,
                         @RequestParam(required = false) String url,
                         @RequestParam(required = false) String apiKey,
                         @RequestParam(defaultValue = "false") boolean clearApiKey,
                         @RequestParam(defaultValue = "false") boolean includeContactDetails,
                         @RequestParam(required = false) List<String> eventTypes,
                         @RequestParam(defaultValue = "false") boolean active,
                         @RequestParam(required = false) String ruleScope,
                         @RequestParam(required = false) List<Long> ruleIds,
                         RedirectAttributes ra) {
        try {
            endpointService.update(id, new Form(name, url, apiKey, clearApiKey, includeContactDetails, eventTypes,
                            active, AlarmWebhookRuleScope.parse(ruleScope), ruleIds),
                    SecurityUtils.currentUserId());
            ra.addFlashAttribute("successMessage", "وب‌هوک ذخیره شد.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            // Two administrators saving the same name at once: ux_alarm_webhook_endpoints_name_lower.
            ra.addFlashAttribute("errorMessage", ErrorTranslator.dataIntegrityViolation(e));
            return "redirect:/alarm-webhooks?edit=" + id;
        }
        return "redirect:/alarm-webhooks";
    }

    @PostMapping("/alarm-webhooks/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/alarm-webhooks/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            endpointService.delete(id, SecurityUtils.currentUserId());
            ra.addFlashAttribute("successMessage", "وب‌هوک حذف شد؛ ارسال‌های در صف آن ناموفق علامت خوردند و سابقه ارسال‌ها باقی است.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/alarm-webhooks";
    }

    @PostMapping("/alarm-webhooks/{id}/secret")
    @PreAuthorize("hasAuthority('POST:/alarm-webhooks/{id}/secret')")
    public String regenerateSecret(@PathVariable Long id, RedirectAttributes ra) {
        try {
            flashSecret(ra, endpointService.regenerateSecret(id, SecurityUtils.currentUserId()));
            ra.addFlashAttribute("successMessage",
                    "کلید امضای جدید ساخته شد؛ از درخواست بعدی با همین کلید امضا می‌شود و کلید قبلی دیگر معتبر نیست.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/alarm-webhooks";
    }

    @PostMapping("/alarm-webhooks/{id}/test")
    @PreAuthorize("hasAuthority('POST:/alarm-webhooks/{id}/test')")
    public String test(@PathVariable Long id, RedirectAttributes ra) {
        try {
            endpointService.queueTest(id, SecurityUtils.currentUserId());
            ra.addFlashAttribute("successMessage",
                    "درخواست آزمایشی در صف قرار گرفت؛ نتیجه چند ثانیه بعد در جدول ارسال‌ها دیده می‌شود.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/alarm-webhooks";
    }

    /** Resends a FAILED delivery. Same authority as before V21; the page it returns to moved here. */
    @PostMapping("/alarm-deliveries/{id}/retry")
    @PreAuthorize("hasAuthority('POST:/alarm-deliveries/{id}/retry')")
    public String retry(@PathVariable Long id, RedirectAttributes ra) {
        try {
            dispatcher.retry(id);
            ra.addFlashAttribute("successMessage", "ارسال دوباره در صف قرار گرفت.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/alarm-webhooks";
    }

    /** Flash only: it survives one redirect and then exists nowhere, like a new integration key. */
    private static void flashSecret(RedirectAttributes ra, AlarmWebhookEndpointService.WithSecret issued) {
        ra.addFlashAttribute("createdSigningSecret", issued.signingSecret());
        ra.addFlashAttribute("createdSigningSecretEndpoint", issued.endpoint().getName());
    }
}
