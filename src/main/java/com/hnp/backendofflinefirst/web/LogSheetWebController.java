package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.LogSheetVoidSubmission;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.dto.SelectOptionDto;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetVoidSubmissionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.dto.AttachmentDto;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import com.hnp.backendofflinefirst.service.AttachmentService;
import com.hnp.backendofflinefirst.service.LogSheetEntryRevisionService;
import com.hnp.backendofflinefirst.service.LogSheetProgressViewService;
import com.hnp.backendofflinefirst.service.CustomLogSheetService;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.LogSheetAccessService;
import com.hnp.backendofflinefirst.service.LogSheetActionLogger;
import com.hnp.backendofflinefirst.service.LogSheetAssignmentService;
import com.hnp.backendofflinefirst.service.LogSheetFieldDefinitionsService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.service.LogSheetTemplateService;
import com.hnp.backendofflinefirst.service.LogSheetVoidSubmissionViewService;
import com.hnp.backendofflinefirst.service.LogSheetWebCompletionAccess;
import com.hnp.backendofflinefirst.service.MasterDataOptionsService;
import com.hnp.backendofflinefirst.service.NfcFaultReportService;
import com.hnp.backendofflinefirst.service.OperationalUnitScopeService;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.DateUtils;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

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
    private final OperationalUnitRepository operationalUnitRepository;
    private final UserRepository userRepository;
    private final LogSheetActionLogger actionLogger;
    private final ExcelExportService excelExportService;
    private final LogSheetWebCompletionAccess webCompletionAccess;
    private final CustomLogSheetService customLogSheetService;
    private final MasterDataOptionsService masterDataOptionsService;
    private final DateUtils dateUtils;
    private final LogSheetVoidSubmissionViewService voidSubmissionViewService;
    private final NfcFaultReportService nfcFaultReportService;
    private final AttachmentService attachmentService;
    private final AppSettingsService appSettingsService;
    private final LogSheetEntryRevisionService revisionService;
    private final LogSheetProgressViewService progressViewService;
    private final ReferenceLabelService referenceLabelService;

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
        // The scope column resolved one location per row — 115 queries on a full page. Identical
        // scopes across rows collapse to a single entry, so this is usually a handful.
        model.addAttribute("scopeLabels", referenceLabelService.scopeSummaryLabels(
                result.getContent().stream().map(ls -> ls.getScopeSummary()).toList()));
        // One query for the whole page — see LogSheetProgressViewService. A sheet with no
        // entries is absent from the map, so the template reads it through a null check.
        model.addAttribute("progressBySheetId", progressViewService.summarise(result.getContent()));
        WebListSupport.addPagination(model, result, q, page, pageSize);
        model.addAttribute("filterStatus", status);
        model.addAttribute("templates", templateService.findVisibleAll());
        model.addAttribute("customUnits", customCreatableUnits());
        return "log-sheets";
    }

    /**
     * Unit ids the current user may create custom log sheets for. {@code null} means
     * unrestricted (ADMIN / HIGH_USER); otherwise every id in the set is offered.
     *
     * <p>Uses the supervisor's <strong>whole branch</strong>, not just directly supervised units:
     * supervising A means supervising its sub-units too. This has to match the server-side guard
     * in {@code CustomLogSheetService.createCustom}, which already accepted the full branch.
     */
    private Set<Long> customCreatableUnitIds() {
        if (SecurityUtils.isUnitScopedOnly()) {
            return scopeService.getSupervisorScopeUnitIds(SecurityUtils.currentUserId());
        }
        return null;
    }

    /** Full entities for the initial (non-searched) render of the modal's unit select. */
    private List<OperationalUnit> customCreatableUnits() {
        Set<Long> unitIds = customCreatableUnitIds();
        if (unitIds == null) {
            return operationalUnitRepository.findAll();
        }
        if (unitIds.isEmpty()) {
            return List.of();
        }
        return operationalUnitRepository.findAllById(unitIds).stream()
                .sorted(java.util.Comparator.comparing(OperationalUnit::getName,
                        java.util.Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /**
     * Searchable version of {@link #customCreatableUnits()} for the remote-select picker —
     * there can be hundreds of operational units, far too many for a plain dropdown.
     */
    @GetMapping("/options/units")
    @PreAuthorize("hasAuthority('GET:/log-sheets/options/units')")
    @ResponseBody
    public List<SelectOptionDto> customUnitOptions(@RequestParam(required = false) String q,
                                                   @RequestParam(defaultValue = "30") int limit) {
        Set<Long> unitIds = customCreatableUnitIds();
        return unitIds == null
                ? masterDataOptionsService.searchOperationalUnits(q, limit)
                : masterDataOptionsService.searchOperationalUnitsInIds(q, unitIds, limit);
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
        // "May act on this sheet as its supervisor" — either by actually supervising the unit,
        // or by holding the capability that reaches across units. The template asks that one
        // question everywhere, so it is answered once here rather than as `isSupervisor or
        // isAdmin` repeated in fifteen places.
        boolean canSupervise = scopeService.isSupervisorOf(userId, sheet.getOperationalUnitId())
                || SecurityUtils.hasCapability(Capabilities.SUPERVISE_ANY_UNIT);
        boolean canCompleteWeb = webCompletionAccess.canCompleteOnWeb(sheet);
        model.addAttribute("canSupervise", canSupervise);
        model.addAttribute("canCompleteWeb", canCompleteWeb);
        model.addAttribute("mobileOnlyCompletion", webCompletionAccess.isMobileOnlyAssignee(sheet));
        model.addAttribute("canOperate", scopeService.isOperatorOf(userId, sheet.getOperationalUnitId()) || canSupervise);
        model.addAttribute("currentUserId", userId);
        model.addAttribute("unitOperators", unitOperators(sheet.getOperationalUnitId()));
        model.addAttribute("voidSubmissions", voidSubmissionRepository.findByLogSheetId(id));
        model.addAttribute("nfcFaultReports", nfcFaultReportService.findByLogSheet(id));
        model.addAttribute("attachmentsById", attachmentService.findForLogSheet(id).stream()
                .collect(Collectors.toMap(Attachment::getId, a -> a, (a, b) -> a, LinkedHashMap::new)));
        model.addAttribute("assetNameById", entries.stream()
                .filter(e -> e.getAssetId() != null)
                .collect(Collectors.toMap(LogSheetEntry::getAssetId, LogSheetEntry::getAssetName, (a, b) -> a)));
        // One query for the whole sheet, not one per asset: a round can carry 300 entries and
        // almost none of them will have a correction. Entries with no history are absent from
        // the map, so the template reads it as a plain null check.
        model.addAttribute("revisionsByEntryId", revisionService.findForSheetByEntryId(id));
        model.addAttribute("progress", progressViewService.summariseOne(id));
        return "log-sheet-detail";
    }

    /**
     * Streams one attachment's bytes to the admin panel.
     *
     * <p>Separate from {@code GET /api/attachments/{id}} because the two live on different
     * security chains: the API chain is stateless and JWT-only, so a browser holding a web
     * session cannot load it from an {@code <img>} tag. This route is on the web chain and is
     * authenticated by that session.
     *
     * <p>The sheet id is part of the path and the attachment is verified to belong to it, so
     * the sheet's own visibility check governs access here exactly as it does everywhere else —
     * knowing an attachment id is still not a way in. It reuses {@code GET:/log-sheets/{id}}
     * deliberately: anyone who may open the sheet may see the evidence attached to it.
     */
    @GetMapping("/{id}/attachments/{attachmentId}")
    @PreAuthorize("hasAuthority('GET:/log-sheets/{id}')")
    public ResponseEntity<byte[]> attachment(@PathVariable Long id,
                                             @PathVariable String attachmentId) throws IOException {
        logSheetAccessService.requireVisibleLogSheet(id);
        AttachmentService.DownloadedAttachment result = attachmentService.download(attachmentId);
        if (!Objects.equals(result.attachment().getLogSheetId(), id)) {
            throw new IllegalArgumentException("Attachment does not belong to this log sheet.");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.attachment().getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(result.attachment().getId()).build().toString())
                // Immutable once uploaded (a correction is a new id), so it caches hard.
                // Private: scoped to one viewer's access, never a shared cache.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .body(result.content());
    }

    /**
     * Uploads one file from the web fill page.
     *
     * <p>Async rather than part of the form POST: the fill form carries every asset on the
     * sheet, and putting binaries in it would mean a 20 MB submission that fails as a whole.
     * The page uploads each file on its own, then keeps the returned id in a hidden input the
     * ordinary form submit carries — exactly the split the mobile app uses, for the same reason.
     *
     * <p>Gated on {@code POST:/log-sheets/{id}/complete}: attaching evidence is part of filling
     * the sheet, so anyone who may fill it may attach to it, and nobody else.
     */
    @PostMapping("/{id}/attachments")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/complete')")
    @ResponseBody
    public AttachmentDto uploadAttachment(@PathVariable Long id,
                                          @RequestParam("assetId") Long assetId,
                                          @RequestParam("fieldKey") String fieldKey,
                                          @RequestParam(value = "durationMs", required = false) Long durationMs,
                                          @RequestParam(value = "width", required = false) Integer width,
                                          @RequestParam(value = "height", required = false) Integer height,
                                          @RequestParam("file") MultipartFile file) throws IOException {
        // The sheet must be one this actor may complete on the web, not merely one they can see.
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(id);
        if (!webCompletionAccess.canCompleteOnWeb(sheet)) {
            throw new AccessDeniedException(FaMessages.logSheetWebCompletionDenied());
        }
        // Streamed for the same reason as the mobile endpoint: refuse an oversized upload while
        // reading it rather than after buffering it.
        return AttachmentDto.from(attachmentService.upload(
                UUID.randomUUID().toString(), id, assetId, fieldKey, file.getInputStream(),
                width, height, durationMs));
    }

    /**
     * Removes a file the operator attached and then changed their mind about.
     *
     * <p><b>The attachment must belong to the sheet in the path.</b> Without that check the two
     * ids answer different questions: {@code id} decides whether the actor may act, and
     * {@code attachmentId} decides what is acted on. Somebody who may fill sheet A could then
     * delete a file from sheet B by naming A here — {@code AttachmentService.delete} resolves the
     * owning sheet from the attachment itself, so it would never see the mismatch. Checking it
     * here keeps the URL meaning what it says.
     */
    @PostMapping("/{id}/attachments/{attachmentId}/delete")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/complete')")
    @ResponseBody
    public Map<String, Object> deleteAttachment(@PathVariable Long id,
                                                @PathVariable String attachmentId) {
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(id);
        if (!webCompletionAccess.canCompleteOnWeb(sheet)) {
            throw new AccessDeniedException(FaMessages.logSheetWebCompletionDenied());
        }
        requireAttachmentBelongsToSheet(id, attachmentId);
        attachmentService.delete(attachmentId);
        return Map.of("deleted", true);
    }

    /**
     * The readings carried by one voided offline submission. The sheet's own entries show the
     * authoritative state; this page shows what the late operator actually sent, so a supervisor
     * can compare the two instead of only seeing that "a submission was voided".
     * <p>Reuses {@code GET:/log-sheets/{id}} — anyone who may open the sheet may inspect its
     * voided submissions, and the sheet's own visibility check is applied first.
     */
    @GetMapping("/{id}/void-submissions/{voidId}")
    @PreAuthorize("hasAuthority('GET:/log-sheets/{id}')")
    public String voidSubmissionDetail(@PathVariable Long id, @PathVariable Long voidId, Model model) {
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(id);
        LogSheetVoidSubmission submission = voidSubmissionRepository.findById(voidId)
                .filter(v -> Objects.equals(v.getLogSheetId(), id))
                .orElseThrow(() -> new IllegalArgumentException("Voided submission not found."));

        model.addAttribute("activePage", "log-sheets");
        model.addAttribute("logSheet", sheet);
        model.addAttribute("submission", submission);
        model.addAttribute("rows", voidSubmissionViewService.toRows(submission));
        return "log-sheet-void-submission";
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

    /** Manual creation of a custom (template-less) sheet from a hand-picked, possibly multi-class asset set. */
    @PostMapping("/custom")
    @PreAuthorize("hasAuthority('POST:/log-sheets/custom')")
    public String createCustom(@RequestParam Long unitId,
                               @RequestParam String name,
                               @RequestParam(required = false) String dueAt,
                               @RequestParam(name = "assetIds", required = false) List<Long> assetIds,
                               RedirectAttributes ra) {
        Long parsedDueAt = dateUtils.parseInput(dueAt);
        LogSheet sheet = customLogSheetService.createCustom(unitId, name, parsedDueAt, assetIds,
                SecurityUtils.currentUserId(), System.currentTimeMillis());
        ra.addFlashAttribute("successMessage", FaMessages.customLogSheetCreated());
        return "redirect:/log-sheets/" + sheet.getId();
    }

    /** Searchable asset options within an operational unit (custom log-sheet asset picker). */
    @GetMapping("/options/assets")
    @PreAuthorize("hasAuthority('GET:/log-sheets/options/assets')")
    @ResponseBody
    public List<SelectOptionDto> assetOptions(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) Long unitId,
                                              @RequestParam(defaultValue = "30") int limit) {
        if (unitId == null) {
            return List.of();
        }
        if (SecurityUtils.isUnitScopedOnly()
                && !scopeService.getSupervisorScopeUnitIds(SecurityUtils.currentUserId()).contains(unitId)) {
            return List.of();
        }
        return masterDataOptionsService.searchAssetsForUnit(q, unitId, limit);
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
    public String extend(@PathVariable Long id, @RequestParam String dueAt,
                         @RequestParam(required = false) String comment, RedirectAttributes ra) {
        long newDueAt = Objects.requireNonNull(dateUtils.parseInput(dueAt), "invalid dueAt");
        assignmentService.extend(id, SecurityUtils.currentUserId(), newDueAt, ActionSource.WEB, comment);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetExtended());
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/void')")
    public String voidSubmitted(@PathVariable Long id,
                                @RequestParam(required = false) String comment, RedirectAttributes ra) {
        assignmentService.voidSubmitted(id, SecurityUtils.currentUserId(), ActionSource.WEB, comment);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetVoided());
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/cancel')")
    public String cancel(@PathVariable Long id,
                         @RequestParam(required = false) String comment, RedirectAttributes ra) {
        assignmentService.cancel(id, SecurityUtils.currentUserId(), ActionSource.WEB, comment);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetCancelled());
        return "redirect:/log-sheets/" + id;
    }

    @PostMapping("/{id}/unvoid")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/unvoid')")
    public String unvoid(@PathVariable Long id,
                         @RequestParam(required = false) String comment, RedirectAttributes ra) {
        assignmentService.restoreVoided(id, SecurityUtils.currentUserId(), ActionSource.WEB, comment);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetUnvoided());
        return "redirect:/log-sheets/" + id;
    }

    /**
     * A supervisor accepts the completed round.
     *
     * <p>Its own permission rather than a reuse of void's, so a site can grant reviewing without
     * granting invalidation. See {@code LogSheetAssignmentService.approve} for why the approver
     * may be the same person who completed it.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/approve')")
    public String approve(@PathVariable Long id,
                          @RequestParam(required = false) String comment, RedirectAttributes ra) {
        assignmentService.approve(id, SecurityUtils.currentUserId(), ActionSource.WEB, comment);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetApproved());
        return "redirect:/log-sheets/" + id;
    }

    /** Withdraws an approval — the only way out of {@code APPROVED}. */
    @PostMapping("/{id}/unapprove")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/unapprove')")
    public String unapprove(@PathVariable Long id,
                            @RequestParam(required = false) String comment, RedirectAttributes ra) {
        assignmentService.unapprove(id, SecurityUtils.currentUserId(), ActionSource.WEB, comment);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetUnapproved());
        return "redirect:/log-sheets/" + id;
    }

    /** Legacy path kept for bookmarks; same as {@link #reopen}. */
    @PostMapping("/{id}/admin-reopen")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/reopen')")
    public String adminReopen(@PathVariable Long id, @RequestParam String dueAt,
                              @RequestParam(required = false) String comment, RedirectAttributes ra) {
        return reopen(id, dueAt, comment, ra);
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/reopen')")
    public String reopen(@PathVariable Long id, @RequestParam String dueAt,
                         @RequestParam(required = false) String comment, RedirectAttributes ra) {
        long newDueAt = Objects.requireNonNull(dateUtils.parseInput(dueAt), "invalid dueAt");
        assignmentService.reopenSubmittedWithExtend(id, SecurityUtils.currentUserId(), newDueAt,
                ActionSource.WEB, comment);
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
        model.addAttribute("attachmentsById", attachmentService.findForLogSheet(id).stream()
                .collect(Collectors.toMap(Attachment::getId, a -> a, (a, b) -> a, LinkedHashMap::new)));
        model.addAttribute("attachmentLimits", appSettingsService.getAttachmentLimits());
        return "log-sheet-fill";
    }

    @PostMapping("/{id}/draft")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/complete')")
    public String draft(@PathVariable Long id, HttpServletRequest request, RedirectAttributes ra) {
        logSheetService.saveDraftFromWeb(id, parseEntryValues(request), request.getParameter("notes"));
        ra.addFlashAttribute("successMessage", FaMessages.logSheetDraftSaved());
        return "redirect:/log-sheets/" + id + "/fill";
    }

    /**
     * Saves one asset's readings from the fill page's dialog, and returns that asset's read-only
     * summary re-rendered.
     *
     * <h2>Why one entry rather than the whole form</h2>
     *
     * <p>The fill page no longer edits in place: each asset opens a dialog, and confirming it
     * saves that asset alone. A round of 47 assets is therefore 47 small saves rather than one
     * submission held in the browser until the end — which is what stops a closed tab from
     * discarding an afternoon's work, and what lets the summary under each card be the values
     * that are actually stored rather than the ones someone typed.
     *
     * <p><b>Nothing in the service layer changed to allow this.</b> {@code applyWebEntryValues}
     * already walks the sheet's entries and skips any the submitted map does not mention, and
     * {@code applyWebNotes(sheet, null)} is already a no-op — so a map holding a single entry, and
     * no notes, was always a supported shape. This method's own work is narrowing the request to
     * one entry and refusing anything else.
     *
     * <h2>History</h2>
     *
     * <p>Recorded by the same code as every other web save: {@code applyWebEntryValues} calls
     * {@code recordSupersededValue} when the value actually changes, and that in turn writes a
     * revision only when something meaningful was replaced. Correcting a reading through this
     * dialog therefore leaves the same trail as correcting it through the old full-page form, and
     * filling an empty field for the first time leaves none — which is the intended difference.
     *
     * <p>Gated on {@code POST:/log-sheets/{id}/complete}, like the attachment upload beside it:
     * saving one asset's readings is part of filling the sheet, so whoever may fill it may do
     * this, and nobody else.
     */
    @PostMapping("/{id}/entries/{entryId}/draft")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/complete')")
    public String saveEntryDraft(@PathVariable Long id,
                                 @PathVariable Long entryId,
                                 HttpServletRequest request,
                                 Model model) {
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(id);
        if (!webCompletionAccess.canCompleteOnWeb(sheet)) {
            throw new AccessDeniedException(FaMessages.logSheetWebCompletionDenied());
        }
        // The entry must belong to THIS sheet. Without this the path id decides access while the
        // entry id decides what is written, and a caller holding a sheet they may fill could
        // name an entry from one they may not.
        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(id).stream()
                .filter(e -> entryId.equals(e.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(FaMessages.logSheetEntryNotFound()));

        // Only this entry's fields, whatever else the request happens to carry.
        Map<String, Map<String, Object>> submitted = parseEntryValues(request);
        Map<String, Object> values = submitted.get(String.valueOf(entryId));
        Map<String, Map<String, Object>> justThisEntry = values == null
                ? Map.of()
                : Map.of(String.valueOf(entryId), values);

        // notes = null: this dialog does not show the sheet-level notes field, and applyWebNotes
        // treats null as "not submitted" rather than "cleared".
        logSheetService.saveDraftFromWeb(id, justThisEntry, null);

        LogSheetEntry saved = logSheetEntryRepository.findById(entryId).orElse(entry);
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(id);
        model.addAttribute("entry", saved);
        model.addAttribute("fieldDefs",
                fieldDefinitionsService.groupByClass(sheet, entries).get(saved.getClassId()));
        model.addAttribute("attachmentsById", attachmentService.findForLogSheet(id).stream()
                .collect(Collectors.toMap(Attachment::getId, a -> a, (a, b) -> a, LinkedHashMap::new)));
        model.addAttribute("logSheetId", id);
        return "fragments/fill-entry-summary :: summaryFromModel";
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/complete')")
    public String complete(@PathVariable Long id, HttpServletRequest request, RedirectAttributes ra) {
        logSheetService.completeFromWeb(id, parseEntryValues(request), request.getParameter("notes"));
        ra.addFlashAttribute("successMessage", FaMessages.logSheetCompleted());
        return "redirect:/log-sheets/" + id;
    }

    /**
     * Refuses an attachment id that does not belong to the sheet named in the path.
     *
     * <p>Silent on the difference between "no such attachment" and "belongs to another sheet":
     * both answer the same way, because telling a caller which of the two it was turns this
     * endpoint into a way to discover that an id exists somewhere they cannot see.
     */
    private void requireAttachmentBelongsToSheet(Long sheetId, String attachmentId) {
        boolean onThisSheet = attachmentService.findForLogSheet(sheetId).stream()
                .anyMatch(a -> a.getId().equals(attachmentId));
        if (!onThisSheet) {
            throw new IllegalArgumentException(FaMessages.attachmentNotOnThisLogSheet());
        }
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
