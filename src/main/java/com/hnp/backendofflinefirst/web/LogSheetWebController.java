package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetVoidSubmissionRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.LogSheetAccessService;
import com.hnp.backendofflinefirst.service.LogSheetActionLogger;
import com.hnp.backendofflinefirst.service.LogSheetAssignmentService;
import com.hnp.backendofflinefirst.service.LogSheetFieldDefinitionsService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.service.LogSheetTemplateService;
import com.hnp.backendofflinefirst.service.LogSheetWebCompletionAccess;
import com.hnp.backendofflinefirst.service.OperationalUnitScopeService;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/log-sheets")
@RequiredArgsConstructor
public class LogSheetWebController {

    private final LogSheetAccessService logSheetAccessService;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final LogSheetAssignmentService assignmentService;
    private final LogSheetGenerationService generationService;
    private final LogSheetService logSheetService;
    private final LogSheetTemplateService templateService;
    private final LogSheetVoidSubmissionRepository voidSubmissionRepository;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;
    private final OperationalUnitScopeService scopeService;
    private final UnitOperatorRepository unitOperatorRepository;
    private final UserRepository userRepository;
    private final LogSheetActionLogger actionLogger;
    private final ExcelExportService excelExportService;
    private final LogSheetWebCompletionAccess webCompletionAccess;
    private final DateUtils dateUtils;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/log-sheets')")
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        var result = logSheetAccessService.findVisibleLogSheets(status, q,
                WebListSupport.pageable(page, pageSize));
        model.addAttribute("activePage", "log-sheets");
        model.addAttribute("logSheets", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        model.addAttribute("filterStatus", status);
        model.addAttribute("templates", templateService.findVisibleAll());
        return "log-sheets";
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/log-sheets')")
    public void export(@RequestParam(required = false) String status, HttpServletResponse response) throws IOException {
        excelExportService.exportLogSheets(status, response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('GET:/log-sheets/{id}')")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "log-sheets");
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(id);
        model.addAttribute("logSheet", sheet);
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(id);
        Map<Long, List<FieldDefinition>> fieldsByClass = fieldDefinitionsService.groupByClass(sheet, entries);
        model.addAttribute("entries", entries);
        model.addAttribute("fieldsByClass", fieldsByClass);
        addAssetCodes(model, entries);
        model.addAttribute("history", actionLogger.history(id));

        Long userId = SecurityUtils.currentUserId();
        boolean isSupervisor = scopeService.isSupervisorOf(userId, sheet.getOperationalUnitId());
        boolean isAdmin = SecurityUtils.isAdmin();
        boolean canCompleteWeb = webCompletionAccess.canCompleteOnWeb(sheet);
        model.addAttribute("isSupervisor", isSupervisor);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("canCompleteWeb", canCompleteWeb);
        model.addAttribute("mobileOnlyCompletion", webCompletionAccess.isMobileOnlyAssignee(sheet));
        model.addAttribute("canOperate", scopeService.isOperatorOf(userId, sheet.getOperationalUnitId()) || isSupervisor || isAdmin);
        model.addAttribute("currentUserId", userId);
        model.addAttribute("unitOperators", unitOperators(sheet.getOperationalUnitId()));
        model.addAttribute("voidSubmissions", voidSubmissionRepository.findByLogSheetId(id));
        return "log-sheet-detail";
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('POST:/log-sheets/generate')")
    public String generate(@RequestParam Long templateId, RedirectAttributes ra) {
        LogSheetTemplate template = templateService.requireVisible(templateId);
        templateService.assertActiveForGeneration(template);
        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, SecurityUtils.currentUserId(), System.currentTimeMillis());
        ra.addFlashAttribute("successMessage", FaMessages.logSheetFromTemplateCreated());
        return "redirect:/log-sheets/" + sheet.getId();
    }

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/claim')")
    public String claim(@PathVariable Long id, RedirectAttributes ra) {
        assignmentService.claim(id, SecurityUtils.currentUserId(), ActionSource.WEB);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetClaimed());
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/release')")
    public String release(@PathVariable Long id, RedirectAttributes ra) {
        assignmentService.release(id, SecurityUtils.currentUserId(), ActionSource.WEB);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetReleased());
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/assign')")
    public String assign(@PathVariable Long id, @RequestParam Long operatorId, RedirectAttributes ra) {
        assignmentService.assign(id, operatorId, SecurityUtils.currentUserId(), ActionSource.WEB);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetAssigned());
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/reassign")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/reassign')")
    public String reassign(@PathVariable Long id, @RequestParam Long operatorId, RedirectAttributes ra) {
        assignmentService.reassign(id, operatorId, SecurityUtils.currentUserId(), ActionSource.WEB);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetReassigned());
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/takeover")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/takeover')")
    public String takeover(@PathVariable Long id, RedirectAttributes ra) {
        assignmentService.takeover(id, SecurityUtils.currentUserId(), ActionSource.WEB);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetTakenOverNotice());
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/extend")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/extend')")
    public String extend(@PathVariable Long id, @RequestParam String dueAt, RedirectAttributes ra) {
        long newDueAt = Objects.requireNonNull(dateUtils.parseInput(dueAt), "invalid dueAt");
        assignmentService.extend(id, SecurityUtils.currentUserId(), newDueAt, ActionSource.WEB);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetExtended());
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/admin-reopen")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/extend')")
    public String adminReopen(@PathVariable Long id, @RequestParam String dueAt, RedirectAttributes ra) {
        long newDueAt = Objects.requireNonNull(dateUtils.parseInput(dueAt), "invalid dueAt");
        assignmentService.adminReopenAndExtend(id, SecurityUtils.currentUserId(), newDueAt, ActionSource.WEB);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetAdminReopened());
        return "redirect:/log-sheets/" + id;
    }

    @GetMapping("/{id}/fill")
    @PreAuthorize("hasAuthority('GET:/log-sheets/{id}/fill')")
    public String fill(@PathVariable Long id, Model model, RedirectAttributes ra) {
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(id);
        if (!webCompletionAccess.canCompleteOnWeb(sheet)) {
            if (webCompletionAccess.isMobileOnlyAssignee(sheet)) {
                ra.addFlashAttribute("errorMessage", FaMessages.mobileAppCompletionOnly());
            } else {
                ra.addFlashAttribute("errorMessage", FaMessages.logSheetWebCompletionDenied());
            }
            return "redirect:/log-sheets/" + id;
        }
        model.addAttribute("activePage", "log-sheets");
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(id);
        Map<Long, List<FieldDefinition>> fieldsByClass = fieldDefinitionsService.groupByClass(sheet, entries);
        model.addAttribute("logSheet", sheet);
        model.addAttribute("entries", entries);
        model.addAttribute("fieldsByClass", fieldsByClass);
        addAssetCodes(model, entries);
        return "log-sheet-fill";
    }

    @PostMapping("/{id}/draft")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/complete')")
    public String draft(@PathVariable Long id, HttpServletRequest request, RedirectAttributes ra) {
        logSheetService.saveDraftFromWeb(id, parseEntryValues(request));
        ra.addFlashAttribute("successMessage", FaMessages.logSheetDraftSaved());
        return "redirect:/log-sheets/" + id + "/fill";
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/complete')")
    public String complete(@PathVariable Long id, HttpServletRequest request, RedirectAttributes ra) {
        logSheetService.completeFromWeb(id, parseEntryValues(request));
        ra.addFlashAttribute("successMessage", FaMessages.logSheetCompleted());
        return "redirect:/log-sheets/" + id;
    }

    private Map<String, Map<String, Object>> parseEntryValues(HttpServletRequest request) {
        Map<String, Map<String, Object>> entryValues = new HashMap<>();
        for (Map.Entry<String, String[]> p : request.getParameterMap().entrySet()) {
            String name = p.getKey();
            if (!name.startsWith("fd_")) continue;
            int sep = name.indexOf('_', 3);
            if (sep < 0) continue;
            String entryId = name.substring(3, sep);
            String fieldKey = name.substring(sep + 1);
            Object value = parseFieldValue(p.getValue());
            if (value == null) continue;
            entryValues.computeIfAbsent(entryId, k -> new LinkedHashMap<>()).put(fieldKey, value);
        }
        return entryValues;
    }

    private Object parseFieldValue(String[] values) {
        if (values == null || values.length == 0) return null;
        if (values.length == 2 && "false".equals(values[0]) && "true".equals(values[1])) {
            return true;
        }
        if (values.length == 1 && "false".equals(values[0])) {
            return false;
        }
        if (values.length > 1) {
            return new ArrayList<>(List.of(values));
        }
        if ("true".equals(values[0])) return true;
        return values[0];
    }

    private List<User> unitOperators(Long unitId) {
        if (unitId == null) return List.of();
        List<Long> operatorIds = unitOperatorRepository.findByUnitId(unitId).stream()
                .map(o -> o.getUserId()).toList();
        return userRepository.findAllById(operatorIds);
    }

    private void addAssetCodes(Model model, List<LogSheetEntry> entries) {
        Set<Long> assetIds = entries.stream()
                .map(LogSheetEntry::getAssetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (assetIds.isEmpty()) {
            model.addAttribute("assetCodeById", Map.of());
            return;
        }
        Map<Long, String> assetCodeById = assetEntryRepository.findAllById(assetIds).stream()
                .filter(a -> a.getAssetCode() != null)
                .collect(Collectors.toMap(AssetEntry::getId, AssetEntry::getAssetCode, (a, b) -> a, LinkedHashMap::new));
        model.addAttribute("assetCodeById", assetCodeById);
    }
}
