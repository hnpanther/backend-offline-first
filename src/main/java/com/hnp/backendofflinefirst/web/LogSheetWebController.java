package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.LogSheetVoidSubmissionRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.LogSheetAccessService;
import com.hnp.backendofflinefirst.service.LogSheetActionLogger;
import com.hnp.backendofflinefirst.service.LogSheetAssignmentService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.service.LogSheetTemplateService;
import com.hnp.backendofflinefirst.service.OperationalUnitScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/log-sheets")
@RequiredArgsConstructor
public class LogSheetWebController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tehran");

    private final LogSheetAccessService logSheetAccessService;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final LogSheetAssignmentService assignmentService;
    private final LogSheetGenerationService generationService;
    private final LogSheetService logSheetService;
    private final LogSheetTemplateService templateService;
    private final LogSheetTemplateRepository templateRepository;
    private final LogSheetVoidSubmissionRepository voidSubmissionRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final OperationalUnitScopeService scopeService;
    private final UnitOperatorRepository unitOperatorRepository;
    private final UserRepository userRepository;
    private final LogSheetActionLogger actionLogger;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/log-sheets')")
    public String list(@RequestParam(required = false) String status, Model model) {
        model.addAttribute("activePage", "log-sheets");
        model.addAttribute("logSheets", logSheetAccessService.findVisibleLogSheets(status));
        model.addAttribute("filterStatus", status);
        model.addAttribute("templates", templateRepository.findAll());
        return "log-sheets";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('GET:/log-sheets/{id}')")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "log-sheets");
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(id);
        model.addAttribute("logSheet", sheet);
        model.addAttribute("entries", logSheetEntryRepository.findByLogSheetId(id));
        model.addAttribute("history", actionLogger.history(id));

        Long userId = SecurityUtils.currentUserId();
        boolean isSupervisor = scopeService.isSupervisorOf(userId, sheet.getOperationalUnitId());
        model.addAttribute("isSupervisor", isSupervisor);
        model.addAttribute("canOperate", scopeService.isOperatorOf(userId, sheet.getOperationalUnitId()) || isSupervisor);
        model.addAttribute("currentUserId", userId);
        model.addAttribute("unitOperators", unitOperators(sheet.getOperationalUnitId()));
        model.addAttribute("voidSubmissions", voidSubmissionRepository.findByLogSheetId(id));
        return "log-sheet-detail";
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('POST:/log-sheets/generate')")
    public String generate(@RequestParam Long templateId, RedirectAttributes ra) {
        LogSheetTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("قالب یافت نشد."));
        templateService.assertCanManageUnit(template.getOperationalUnitId());
        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, SecurityUtils.currentUserId(), System.currentTimeMillis());
        ra.addFlashAttribute("successMessage", "لاگ‌شیت با موفقیت از قالب ساخته شد.");
        return "redirect:/log-sheets/" + sheet.getId();
    }

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/claim')")
    public String claim(@PathVariable Long id, RedirectAttributes ra) {
        assignmentService.claim(id, SecurityUtils.currentUserId(), ActionSource.WEB);
        ra.addFlashAttribute("successMessage", "لاگ‌شیت پیک‌آپ شد.");
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/release')")
    public String release(@PathVariable Long id, RedirectAttributes ra) {
        assignmentService.release(id, SecurityUtils.currentUserId(), ActionSource.WEB);
        ra.addFlashAttribute("successMessage", "لاگ‌شیت به استخر برگردانده شد.");
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/assign')")
    public String assign(@PathVariable Long id, @RequestParam Long operatorId, RedirectAttributes ra) {
        assignmentService.assign(id, operatorId, SecurityUtils.currentUserId(), ActionSource.WEB);
        ra.addFlashAttribute("successMessage", "لاگ‌شیت به اپراتور انتساب داده شد.");
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/reassign")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/reassign')")
    public String reassign(@PathVariable Long id, @RequestParam Long operatorId, RedirectAttributes ra) {
        assignmentService.reassign(id, operatorId, SecurityUtils.currentUserId(), ActionSource.WEB);
        ra.addFlashAttribute("successMessage", "لاگ‌شیت بازانتساب داده شد.");
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/takeover")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/takeover')")
    public String takeover(@PathVariable Long id, RedirectAttributes ra) {
        assignmentService.takeover(id, SecurityUtils.currentUserId(), ActionSource.WEB);
        ra.addFlashAttribute("successMessage", "لاگ‌شیت تصاحب شد؛ سینک بعدی اپراتور ابطال خواهد شد.");
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/extend")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/extend')")
    public String extend(@PathVariable Long id, @RequestParam String dueAt, RedirectAttributes ra) {
        long newDueAt = parseLocalDateTime(dueAt);
        assignmentService.extend(id, SecurityUtils.currentUserId(), newDueAt, ActionSource.WEB);
        ra.addFlashAttribute("successMessage", "مهلت لاگ‌شیت تمدید شد.");
        return "redirect:/log-sheets/" + id;
    }

    @GetMapping("/{id}/fill")
    @PreAuthorize("hasAuthority('GET:/log-sheets/{id}/fill')")
    public String fill(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "log-sheets");
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(id);
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(id);
        Map<Long, List<FieldDefinition>> fieldsByClass = new HashMap<>();
        for (LogSheetEntry entry : entries) {
            if (entry.getClassId() != null) {
                fieldsByClass.computeIfAbsent(entry.getClassId(), fieldDefinitionRepository::findByClassId);
            }
        }
        model.addAttribute("logSheet", sheet);
        model.addAttribute("entries", entries);
        model.addAttribute("fieldsByClass", fieldsByClass);
        return "log-sheet-fill";
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/complete')")
    public String complete(@PathVariable Long id, @RequestParam Map<String, String> params, RedirectAttributes ra) {
        // Params for entry fields are named fd_<entryId>_<fieldKey>.
        Map<String, Map<String, Object>> entryValues = new HashMap<>();
        for (Map.Entry<String, String> p : params.entrySet()) {
            String name = p.getKey();
            if (!name.startsWith("fd_")) continue;
            int sep = name.indexOf('_', 3);
            if (sep < 0) continue;
            String entryId = name.substring(3, sep);
            String fieldKey = name.substring(sep + 1);
            entryValues.computeIfAbsent(entryId, k -> new LinkedHashMap<>()).put(fieldKey, p.getValue());
        }
        logSheetService.completeFromWeb(id, entryValues);
        ra.addFlashAttribute("successMessage", "لاگ‌شیت با موفقیت تکمیل شد.");
        return "redirect:/log-sheets/" + id;
    }

    private long parseLocalDateTime(String value) {
        ZonedDateTime zdt = java.time.LocalDateTime
                .parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                .atZone(ZONE);
        return zdt.toInstant().toEpochMilli();
    }

    private List<User> unitOperators(Long unitId) {
        if (unitId == null) return List.of();
        List<Long> operatorIds = unitOperatorRepository.findByUnitId(unitId).stream()
                .map(o -> o.getUserId()).toList();
        return userRepository.findAllById(operatorIds);
    }
}
