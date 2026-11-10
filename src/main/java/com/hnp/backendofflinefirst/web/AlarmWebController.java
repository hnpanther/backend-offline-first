package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.AlarmState;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.entity.Alarm;
import com.hnp.backendofflinefirst.entity.AlarmEvent;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AlarmWebhookDeliveryRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.alarm.AlarmService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The supervisor's alarm queue, one alarm's page, and the two decisions.
 *
 * <p>Scope and the decision rules are {@link AlarmService}'s; this class only renders and routes.
 * The detail page reuses {@code GET:/alarms} — seeing the queue and seeing one of its rows are one
 * permission, as the log-sheet export reuses the list's.
 */
@Controller
@RequestMapping("/alarms")
@RequiredArgsConstructor
public class AlarmWebController {

    private final AlarmService alarmService;
    private final AssetEntryRepository assetEntryRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final UserRepository userRepository;
    private final AlarmWebhookDeliveryRepository deliveryRepository;

    /**
     * @param state {@code LIVE} (default: open and acknowledged), {@code OPEN}, {@code ACKNOWLEDGED},
     *              {@code CLOSED} or {@code ALL}. Anything else falls back to the default — a bad
     *              query parameter on a list page must not be an error page.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('GET:/alarms')")
    public String list(@RequestParam(required = false) String state,
                       @RequestParam(required = false) String severity,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        String stateFilter = normaliseState(state);
        FieldValidationSeverity severityFilter = parseSeverity(severity);
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Page<Alarm> result = alarmService.findVisible(statesFor(stateFilter), severityFilter,
                WebListSupport.unsortedPageable(page, pageSize));

        model.addAttribute("activePage", "alarms");
        model.addAttribute("alarms", result.getContent());
        model.addAttribute("stateFilter", stateFilter);
        model.addAttribute("severityFilter", severityFilter == null ? "" : severityFilter.name());
        model.addAttribute("openCount", alarmService.countVisible(AlarmState.OPEN));
        model.addAttribute("acknowledgedCount", alarmService.countVisible(AlarmState.ACKNOWLEDGED));
        WebListSupport.addPagination(model, result, null, page, pageSize);
        addLookups(model, result.getContent(), List.of());
        return "alarms";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('GET:/alarms')")
    public String detail(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Alarm alarm;
        try {
            alarm = alarmService.requireVisible(id);
        } catch (IllegalArgumentException | AccessDeniedException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
            return "redirect:/alarms";
        }
        List<AlarmEvent> events = alarmService.timeline(id);
        model.addAttribute("activePage", "alarms");
        model.addAttribute("alarm", alarm);
        model.addAttribute("events", events);
        // State and attempts only. The payload carries supervisors' phone numbers and is not
        // shown to everyone who can see the alarm.
        model.addAttribute("deliveries", deliveryRepository.findByAlarmIdOrderByIdAsc(id));
        addLookups(model, List.of(alarm), events);
        return "alarm-detail";
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAuthority('POST:/alarms/{id}/acknowledge')")
    public String acknowledge(@PathVariable Long id,
                              @RequestParam(required = false) String comment,
                              @RequestParam(required = false) String returnTo,
                              RedirectAttributes ra) {
        try {
            alarmService.acknowledge(id, SecurityUtils.currentUserId(), comment);
            ra.addFlashAttribute("successMessage", "آلارم به‌عنوان «دیده‌شده» ثبت شد.");
        } catch (ObjectOptimisticLockingFailureException e) {
            ra.addFlashAttribute("errorMessage", "این آلارم همین حالا تغییر کرد؛ دوباره نگاه کنید.");
        } catch (IllegalArgumentException | IllegalStateException | AccessDeniedException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:" + safeReturn(returnTo, id);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('POST:/alarms/{id}/close')")
    public String close(@PathVariable Long id,
                        @RequestParam(required = false) String comment,
                        @RequestParam(required = false) String returnTo,
                        RedirectAttributes ra) {
        try {
            alarmService.close(id, SecurityUtils.currentUserId(), comment);
            ra.addFlashAttribute("successMessage", "آلارم بسته شد.");
        } catch (ObjectOptimisticLockingFailureException e) {
            ra.addFlashAttribute("errorMessage", "این آلارم همین حالا تغییر کرد؛ دوباره نگاه کنید.");
        } catch (IllegalArgumentException | IllegalStateException | AccessDeniedException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:" + safeReturn(returnTo, id);
    }

    /**
     * Only a path inside this application. A {@code returnTo} taken verbatim is an open redirect:
     * a link that logs a supervisor's click somewhere else after doing what they asked.
     */
    static String safeReturn(String returnTo, Long id) {
        if (returnTo != null && returnTo.startsWith("/") && !returnTo.startsWith("//")
                && !returnTo.contains("\\") && !returnTo.contains("://")) {
            return returnTo;
        }
        return "/alarms/" + id;
    }

    private static String normaliseState(String raw) {
        if (raw == null || raw.isBlank()) {
            return "LIVE";
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "LIVE", "OPEN", "ACKNOWLEDGED", "CLOSED", "ALL" -> value;
            default -> "LIVE";
        };
    }

    private static Collection<AlarmState> statesFor(String filter) {
        return switch (filter) {
            case "OPEN" -> List.of(AlarmState.OPEN);
            case "ACKNOWLEDGED" -> List.of(AlarmState.ACKNOWLEDGED);
            case "CLOSED" -> List.of(AlarmState.CLOSED);
            case "ALL" -> null;
            default -> List.of(AlarmState.OPEN, AlarmState.ACKNOWLEDGED);
        };
    }

    private static FieldValidationSeverity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (value.equals("WARNING")) {
            return FieldValidationSeverity.WARNING;
        }
        if (value.equals("DANGER")) {
            return FieldValidationSeverity.DANGER;
        }
        return null;
    }

    /** One query per dimension for the whole page — never one per row. */
    private void addLookups(Model model, List<Alarm> alarms, List<AlarmEvent> events) {
        Set<Long> assetIds = alarms.stream().map(Alarm::getAssetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> unitIds = alarms.stream().map(Alarm::getOperationalUnitId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> userIds = new HashSet<>();
        for (Alarm alarm : alarms) {
            addIfPresent(userIds, alarm.getAcknowledgedByUserId());
            addIfPresent(userIds, alarm.getClosedByUserId());
        }
        for (AlarmEvent event : events) {
            addIfPresent(userIds, event.getActorUserId());
        }
        Map<Long, AssetEntry> assetById = assetIds.isEmpty() ? Map.of()
                : assetEntryRepository.findAllById(assetIds).stream()
                        .collect(Collectors.toMap(AssetEntry::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Long, OperationalUnit> unitById = unitIds.isEmpty() ? Map.of()
                : operationalUnitRepository.findAllById(unitIds).stream()
                        .collect(Collectors.toMap(OperationalUnit::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Long, User> userById = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        model.addAttribute("assetById", assetById);
        model.addAttribute("unitById", unitById);
        model.addAttribute("userById", userById);
    }

    private static void addIfPresent(Set<Long> ids, Long id) {
        if (id != null) {
            ids.add(id);
        }
    }
}
