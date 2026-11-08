package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssetScanMode;
import com.hnp.backendofflinefirst.domain.FieldDataTypes;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetListFilter;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetField;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.LogSheetVoidSubmission;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.dto.SelectOptionDto;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetFieldRepository;
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
import com.hnp.backendofflinefirst.service.LogSheetFieldService;
import com.hnp.backendofflinefirst.service.LogSheetRevisionService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.service.LogSheetTemplateService;
import com.hnp.backendofflinefirst.service.LogSheetVoidSubmissionViewService;
import com.hnp.backendofflinefirst.service.LogSheetWebCompletionAccess;
import com.hnp.backendofflinefirst.service.MasterDataOptionsService;
import com.hnp.backendofflinefirst.service.MasterDataUniquenessValidator;
import com.hnp.backendofflinefirst.service.NfcFaultReportService;
import com.hnp.backendofflinefirst.service.OperationalUnitScopeService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.DateUtils;
import com.hnp.backendofflinefirst.util.FormDataViewHelper;
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
    private final LogSheetFieldService sheetFieldService;
    private final LogSheetRevisionService sheetRevisionService;
    private final LogSheetFieldRepository logSheetFieldRepository;
    private final MasterDataUniquenessValidator uniquenessValidator;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/log-sheets')")
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String dateField,
                       @RequestParam(required = false) String fromAt,
                       @RequestParam(required = false) String toAt,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        LogSheetListFilter filter = LogSheetListFilter.of(
                status, q, dateField, dateUtils.parseInput(fromAt), dateUtils.parseInput(toAt));
        var result = logSheetAccessService.findVisibleLogSheets(
                filter, WebListSupport.pageable(page, pageSize));
        model.addAttribute("activePage", "log-sheets");
        model.addAttribute("logSheets", result.getContent());
        // The scope column resolved one location per row — 115 queries on a full page. Identical
        // scopes across rows collapse to a single entry, so this is usually a handful.
        model.addAttribute("scopeLabels", referenceLabelService.scopeSummaryLabels(
                result.getContent().stream().map(ls -> ls.getScopeSummary()).toList()));
        // One query for the units this page references, not one per row — the responsible unit is
        // now the column the page leads with, and a per-row lookup is the N+1 the scope column
        // above was fixed for. Scoped to the ids on the page rather than reading every unit:
        // plant-wide there are hundreds, and a page shows at most 250 rows.
        model.addAttribute("unitLabels", referenceLabelService.operationalUnitLabelsFor(
                result.getContent().stream().map(LogSheet::getOperationalUnitId).toList()));
        // One query for the whole page — see LogSheetProgressViewService. A sheet with no
        // entries is absent from the map, so the template reads it through a null check.
        model.addAttribute("progressBySheetId", progressViewService.summarise(result.getContent()));
        WebListSupport.addPagination(model, result, q, page, pageSize);
        model.addAttribute("filterStatus", status);
        // Echoed back so the bar shows what is in force. The dates go back as the picker's own
        // hidden-field format rather than as what was typed, so a filter survives a reload
        // whether it arrived from the form or from a pasted link.
        model.addAttribute("filterDateField", filter.dateField().name());
        model.addAttribute("fromInput", dateUtils.formatInputHidden(filter.fromAt()));
        model.addAttribute("toInput", dateUtils.formatInputHidden(filter.toAt()));
        // How many of the collapsible filters are actually narrowing the list — NOT counting
        // the search box, which is never folded away. The number goes on the toggle, and its
        // being above zero is what forces the bar open: a folded bar hiding an active filter is
        // a list with rows missing and nothing on screen to say why.
        //
        // Read from the parsed filter rather than the raw parameters, so `?status=` — which a
        // cleared select posts — counts as off rather than as a filter nobody can see.
        int activeFilterCount =
                (filter.status() != null ? 1 : 0)
                        + (filter.fromAt() != null ? 1 : 0)
                        + (filter.toAt() != null ? 1 : 0);
        model.addAttribute("activeFilterCount", activeFilterCount);
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

    /**
     * The list as an Excel file, narrowed by <b>exactly</b> what the page is showing.
     *
     * <p>It used to take {@code status} alone. The moment the page grew a second filter that
     * would have made the button export a different set of rows from the one on screen — same
     * filename, same columns, no error — so it takes the same {@link LogSheetListFilter} the
     * list does, built from the same parameters.
     */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/log-sheets')")
    public void export(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String dateField,
                       @RequestParam(required = false) String fromAt,
                       @RequestParam(required = false) String toAt,
                       HttpServletResponse response) throws IOException {
        excelExportService.exportLogSheets(LogSheetListFilter.of(
                status, q, dateField, dateUtils.parseInput(fromAt), dateUtils.parseInput(toAt)), response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('GET:/log-sheets/{id}')")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "log-sheets");
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(id);
        model.addAttribute("logSheet", sheet);
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(id);
        // Keyed by ASSET, not by class: a re-banded pump has to be shown against its own range,
        // and the detail page is where a supervisor checks exactly that.
        Map<Long, List<FieldDefinition>> fieldsByAsset = fieldDefinitionsService.groupByAsset(sheet, entries);
        model.addAttribute("entries", entries);
        model.addAttribute("fieldsByAsset", fieldsByAsset);
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
        // The round's own questions and the corrections to their answers. The questions come off
        // the sheet's frozen snapshot, so the first is free; the second is one query for the
        // whole sheet, not one per row — there is one round-level form, not one per asset.
        model.addAttribute("sheetQuestions", sheetFieldService.resolveForSheet(sheet));
        model.addAttribute("sheetRevisions", sheetRevisionService.findForSheet(id));
        // Whether the questions link should invite a supervisor in or merely offer a look. Costs
        // no query: every condition it reads is already on the sheet in hand.
        model.addAttribute("canEditSheetQuestions",
                customLogSheetService.canEditOwnQuestions(sheet, userId));
        // Counted from the entries already in hand, not queried: the toolbar renders its
        // out-of-range filter only where there is something to filter to, and a button that
        // can only ever empty the table teaches people the page is broken.
        //
        // `max_severity` as stored, never recomputed here. It was written at submit against
        // the bands this sheet froze, so a range re-tuned since cannot retroactively move a
        // row into or out of this count. EntrySeverityEvaluator is the only writer.
        model.addAttribute("breachedEntryCount", entries.stream()
                .filter(e -> FieldValidationSeverity.WARNING.name().equals(e.getMaxSeverity())
                        || FieldValidationSeverity.DANGER.name().equals(e.getMaxSeverity()))
                .count());
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
                                          // Optional since V10: absent means the media answers
                                          // the ROUND's own question rather than an asset's.
                                          @RequestParam(value = "assetId", required = false) Long assetId,
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
        // uploadFromWebFill, not upload: this page writes the file and the reading that names it
        // together. See AttachmentService for why the mobile API deliberately does not.
        return AttachmentDto.from(attachmentService.uploadFromWebFill(
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
        attachmentService.deleteFromWebFill(attachmentId);
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

        List<LogSheetVoidSubmissionViewService.VoidedEntryRow> rows =
                voidSubmissionViewService.toRows(submission);

        model.addAttribute("activePage", "log-sheets");
        model.addAttribute("logSheet", sheet);
        model.addAttribute("submission", submission);
        model.addAttribute("rows", rows);
        // Whether the «فقط دارای مقدار» control has anything to hide, so the page can leave it out
        // when it does not. Unlike the sheet's own page — which renders every parameter the class
        // defines, from the frozen snapshot — this one renders only what the payload literally
        // carried, and a device's `form_data` normally holds a key only where there is an answer.
        // So an empty parameter here is possible (the payload is stored verbatim) but unusual, and
        // a filter that is nearly always a no-op teaches people that the filters do not work.
        model.addAttribute("hasEmptyParameters", rows.stream()
                .flatMap(row -> row.fields().stream())
                .anyMatch(FormDataViewHelper.FormFieldRow::isEmpty));
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
                               @RequestParam(defaultValue = "false") boolean withoutAssets,
                               // Absent from an older client or a hand-made request: the service
                               // reads null as NFC, which is what every round was before V11.
                               @RequestParam(required = false) AssetScanMode scanMode,
                               RedirectAttributes ra) {
        Long parsedDueAt = dateUtils.parseInput(dueAt);
        LogSheet sheet = customLogSheetService.createCustom(unitId, name, parsedDueAt, assetIds,
                withoutAssets, scanMode, SecurityUtils.currentUserId(), System.currentTimeMillis());
        ra.addFlashAttribute("successMessage", FaMessages.customLogSheetCreated());
        return "redirect:/log-sheets/" + sheet.getId();
    }

    // =====================================================================
    // A CUSTOM sheet's own questions
    //
    // A template-raised round inherits its questions from its template and freezes them at
    // generation; a custom sheet has no template, so it owns them directly through
    // `log_sheet_fields.log_sheet_id` — the column V9 created and V10 gave a writer.
    //
    // Every endpoint here delegates its four rules to CustomLogSheetService, which also owns
    // the rules for raising the round itself: a custom sheet, not finished, not yet answered,
    // and in a unit this actor may raise custom rounds for. The page below it is the SAME page
    // the template editor renders — one copy of the markup, two owners.
    // =====================================================================

    @GetMapping("/{id}/fields")
    @PreAuthorize("hasAuthority('GET:/log-sheets/{id}/fields')")
    public String sheetQuestions(@PathVariable Long id,
                                 @RequestParam(required = false) Long editId,
                                 Model model) {
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(id);
        boolean canManage = customLogSheetService.canEditOwnQuestions(sheet, SecurityUtils.currentUserId());
        model.addAttribute("activePage", "log-sheets");
        List<LogSheetField> questions = sheetFieldService.editorRowsForSheet(id);
        model.addAttribute("fields", questions);
        model.addAttribute("dataTypes", FieldDataTypes.labels());
        model.addAttribute("canManage", canManage);
        model.addAttribute("questionsOwnerName", sheet.getTemplateName());
        model.addAttribute("questionsTitle", "پرسش‌های این لاگ شیت");
        model.addAttribute("questionsBackPath", "/log-sheets/" + id);
        model.addAttribute("questionsBackLabel", "لاگ شیت");
        model.addAttribute("questionsBasePath", "/log-sheets/" + id + "/fields");
        model.addAttribute("questionsScopeNote", sheetQuestionsScopeNote(sheet));
        if (editId != null) {
            // Null when the id names a question of another sheet: the page renders the create
            // form instead of an edit form, which is the honest outcome of a tampered link.
            // Picked out of the list already loaded rather than fetched again — the editor and
            // the table must show the same row, and a second query could not guarantee it.
            model.addAttribute("editField", questions.stream()
                    .filter(f -> editId.equals(f.getId()))
                    .findFirst().orElse(null));
        }
        return "log-sheet-questions";
    }

    @PostMapping("/{id}/fields")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/fields')")
    public String addSheetQuestion(@PathVariable Long id,
                                   @ModelAttribute LogSheetField form,
                                   @RequestParam(required = false) String selectOptions,
                                   @RequestParam(required = false) Double warningMin,
                                   @RequestParam(required = false) Double warningMax,
                                   @RequestParam(required = false) Double dangerMin,
                                   @RequestParam(required = false) Double dangerMax,
                                   @RequestParam(required = false) String warningOptions,
                                   @RequestParam(required = false) String dangerOptions,
                                   // How this field may be filled from a label (text/number only). Absent from an
                                   // older form post, which reads as "typed", exactly as every field was before.
                                   @RequestParam(name = "scanSources", required = false) java.util.List<String> scanSources,
                                   @RequestParam(name = "scanManual", required = false) Boolean scanManualChecked,
                                   // Says the checkbox was on the form at all. Its own name, never a second "scanManual":
                                   // Spring reads the FIRST of two values, so that shape could never store a tick.
                                   @RequestParam(name = "_scanManual", required = false) String scanManualMarker,
                                   RedirectAttributes ra) {
        customLogSheetService.assertCanEditOwnQuestions(
                logSheetAccessService.requireVisibleLogSheet(id), SecurityUtils.currentUserId());
        if (!FieldDataTypes.isValid(form.getDataType())) {
            ra.addFlashAttribute("errorMessage",
                    FaMessages.fieldDefinitionInvalidDataType(form.getDataType()));
            return "redirect:/log-sheets/" + id + "/fields";
        }
        String key = form.getKey() == null ? null : form.getKey().trim();
        uniquenessValidator.validateLogSheetOwnField(null, id, key);

        long now = System.currentTimeMillis();
        LogSheetField field = new LogSheetField();
        field.setTemplateId(null);
        field.setLogSheetId(id);
        field.setKey(key);
        field.setLabel(form.getLabel());
        field.setDataType(form.getDataType());
        field.setUnit(trimToNull(form.getUnit()));
        field.setRequired(form.isRequired());
        field.setOrder(form.getOrder());
        field.setValidation(FieldValidationSupport.build(
                form.getDataType(), selectOptions, warningMin, warningMax, dangerMin, dangerMax,
                warningOptions, dangerOptions, scanSources,
                FieldValidationSupport.scanManualFromForm(scanManualMarker, scanManualChecked)));
        field.setVersion(1);
        field.setDeleted(false);
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        logSheetFieldRepository.save(field);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetFieldCreated());
        return "redirect:/log-sheets/" + id + "/fields";
    }

    @PostMapping("/{id}/fields/{fieldId}")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/fields/{fieldId}')")
    public String updateSheetQuestion(@PathVariable Long id,
                                      @PathVariable Long fieldId,
                                      @ModelAttribute LogSheetField form,
                                      @RequestParam(required = false) String selectOptions,
                                      @RequestParam(required = false) Double warningMin,
                                      @RequestParam(required = false) Double warningMax,
                                      @RequestParam(required = false) Double dangerMin,
                                      @RequestParam(required = false) Double dangerMax,
                                      @RequestParam(required = false) String warningOptions,
                                      @RequestParam(required = false) String dangerOptions,
                                      // How this field may be filled from a label (text/number only). Absent from an
                                      // older form post, which reads as "typed", exactly as every field was before.
                                      @RequestParam(name = "scanSources", required = false) java.util.List<String> scanSources,
                                      @RequestParam(name = "scanManual", required = false) Boolean scanManualChecked,
                                      // Says the checkbox was on the form at all. Its own name, never a second "scanManual":
                                      // Spring reads the FIRST of two values, so that shape could never store a tick.
                                      @RequestParam(name = "_scanManual", required = false) String scanManualMarker,
                                      RedirectAttributes ra) {
        customLogSheetService.assertCanEditOwnQuestions(
                logSheetAccessService.requireVisibleLogSheet(id), SecurityUtils.currentUserId());
        var existing = logSheetFieldRepository.findByIdAndLogSheetId(fieldId, id);
        if (existing.isEmpty()) {
            // The path id decides access and the field id decides what is written, so these two
            // have to agree or holding one round is a way into another's questions.
            ra.addFlashAttribute("errorMessage", FaMessages.logSheetFieldNotOnThisSheet());
            return "redirect:/log-sheets/" + id + "/fields";
        }
        LogSheetField field = existing.get();
        if (!FieldDataTypes.isValidFor(form.getDataType(), field.getDataType())) {
            // Refused before `field` is touched: it is attached for the whole request under Open
            // Session In View, so a half-applied edit would still be flushed.
            ra.addFlashAttribute("errorMessage",
                    FaMessages.fieldDefinitionInvalidDataType(form.getDataType()));
            return "redirect:/log-sheets/" + id + "/fields";
        }
        String key = form.getKey() == null ? null : form.getKey().trim();
        uniquenessValidator.validateLogSheetOwnField(fieldId, id, key);
        // Built before the entity is mutated, for the same reason: it throws on min > max.
        Map<String, Object> validation = FieldValidationSupport.build(
                form.getDataType(), selectOptions, warningMin, warningMax, dangerMin, dangerMax,
                warningOptions, dangerOptions, scanSources,
                FieldValidationSupport.scanManualFromForm(scanManualMarker, scanManualChecked));

        field.setKey(key);
        field.setLabel(form.getLabel());
        field.setDataType(form.getDataType());
        field.setUnit(trimToNull(form.getUnit()));
        field.setRequired(form.isRequired());
        field.setOrder(form.getOrder());
        field.setValidation(validation);
        field.setVersion(field.getVersion() == null ? 1 : field.getVersion() + 1);
        field.setUpdatedAt(System.currentTimeMillis());
        logSheetFieldRepository.save(field);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetFieldUpdated());
        return "redirect:/log-sheets/" + id + "/fields";
    }

    /**
     * Switches one of this round's questions off, or back on.
     *
     * <p>Kept even though this list can only be edited before anyone answers it: a question
     * switched off is gone from the form without losing its id or its wording, which is the
     * cheaper undo while a supervisor is still building the round.
     */
    @PostMapping("/{id}/fields/{fieldId}/state")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/fields/{fieldId}/state')")
    public String setSheetQuestionState(@PathVariable Long id,
                                        @PathVariable Long fieldId,
                                        @RequestParam boolean disabled,
                                        RedirectAttributes ra) {
        customLogSheetService.assertCanEditOwnQuestions(
                logSheetAccessService.requireVisibleLogSheet(id), SecurityUtils.currentUserId());
        var existing = logSheetFieldRepository.findByIdAndLogSheetId(fieldId, id);
        if (existing.isEmpty()) {
            ra.addFlashAttribute("errorMessage", FaMessages.logSheetFieldNotOnThisSheet());
            return "redirect:/log-sheets/" + id + "/fields";
        }
        LogSheetField field = existing.get();
        if (field.isDeleted() != disabled) {
            field.setDeleted(disabled);
            field.setVersion(field.getVersion() == null ? 1 : field.getVersion() + 1);
            field.setUpdatedAt(System.currentTimeMillis());
            logSheetFieldRepository.save(field);
        }
        ra.addFlashAttribute("successMessage",
                disabled ? FaMessages.logSheetFieldDisabled() : FaMessages.logSheetFieldEnabled());
        return "redirect:/log-sheets/" + id + "/fields";
    }

    /**
     * Removes one of this round's questions — and still refuses once it has been answered.
     *
     * <p>The authority check above already refuses every write to an answered round, so this
     * guard is the second lock rather than the first. It reads the answers themselves instead
     * of the freeze column, so a sheet whose snapshot was somehow never written still cannot
     * have the definition under a stored answer pulled out from beneath it (AGENTS.md #157).
     */
    @PostMapping("/{id}/fields/{fieldId}/delete")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/fields/{fieldId}/delete')")
    public String deleteSheetQuestion(@PathVariable Long id,
                                      @PathVariable Long fieldId,
                                      RedirectAttributes ra) {
        customLogSheetService.assertCanEditOwnQuestions(
                logSheetAccessService.requireVisibleLogSheet(id), SecurityUtils.currentUserId());
        var existing = logSheetFieldRepository.findByIdAndLogSheetId(fieldId, id);
        if (existing.isEmpty()) {
            ra.addFlashAttribute("errorMessage", FaMessages.logSheetFieldNotOnThisSheet());
            return "redirect:/log-sheets/" + id + "/fields";
        }
        LogSheetField field = existing.get();
        if (logSheetFieldRepository.isSheetFieldKeyAnswered(id, field.getKey())) {
            ra.addFlashAttribute("errorMessage", FaMessages.logSheetFieldInUseUseDisable());
            return "redirect:/log-sheets/" + id + "/fields";
        }
        logSheetFieldRepository.delete(field);
        ra.addFlashAttribute("successMessage", FaMessages.logSheetFieldDeleted());
        return "redirect:/log-sheets/" + id + "/fields";
    }

    /**
     * The one sentence under the heading — it has to say which of the four rules is in force,
     * because «no buttons» on its own looks like a missing permission.
     */
    private String sheetQuestionsScopeNote(LogSheet sheet) {
        if (sheet.getTemplateId() != null) {
            return "این لاگ شیت از یک قالب ساخته شده است؛ پرسش‌هایش از همان قالب آمده‌اند و "
                    + "اینجا فقط برای مشاهده‌اند.";
        }
        if (sheet.getSheetFieldDefinitionsSnapshot() != null) {
            return "به پرسش‌های این لاگ شیت پاسخ داده شده و فهرست آن‌ها قطعی شده است؛ از این پس "
                    + "فقط قابل مشاهده‌اند.";
        }
        if (sheet.getStatus() != null && sheet.getStatus().isTerminal()) {
            return "کار این لاگ شیت تمام شده است؛ پرسش‌هایش فقط قابل مشاهده‌اند.";
        }
        return "این پرسش‌ها فقط به همین لاگ شیت تعلق دارند و تا پیش از ثبت نخستین پاسخ قابل "
                + "ویرایش‌اند.";
    }

    /**
     * An empty text input posts {@code ""}, and "no unit" has to be one value rather than two.
     *
     * <p>Not cosmetic: Thymeleaf calls a non-null empty String <em>true</em>, so a page guarding
     * on {@code th:if="${q.unit}"} prints an empty «()» (AGENTS.md #162).
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
        // The fill form itself. Resolving by class here is the defect this feature exists to
        // remove: the operator would be shown — and judged against, in the colouring — the
        // class's range while the server stored severity against the asset's own.
        Map<Long, List<FieldDefinition>> fieldsByAsset = fieldDefinitionsService.groupByAsset(sheet, entries);
        model.addAttribute("logSheet", sheet);
        model.addAttribute("entries", entries);
        model.addAttribute("fieldsByAsset", fieldsByAsset);
        addAssetCodes(model, entries);
        model.addAttribute("attachmentsById", attachmentService.findForLogSheet(id).stream()
                .collect(Collectors.toMap(Attachment::getId, a -> a, (a, b) -> a, LinkedHashMap::new)));
        model.addAttribute("attachmentLimits", appSettingsService.getAttachmentLimits());
        // The round's own questions. Free when the sheet froze them, which every sheet
        // raised from a template with questions does; one query otherwise.
        model.addAttribute("sheetQuestions", sheetFieldService.resolveForSheet(sheet));
        return "log-sheet-fill";
    }

    @PostMapping("/{id}/draft")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/complete')")
    public String draft(@PathVariable Long id, HttpServletRequest request, RedirectAttributes ra) {
        logSheetService.saveDraftFromWeb(id, parseEntryValues(request), request.getParameter("notes"),
                parseSheetFormValues(request));
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

        // One entry's schema, resolved from the sheet's frozen snapshot plus this asset's frozen
        // delta. The whole-sheet read this replaced existed only to index a map by class id.
        LogSheetEntry saved = logSheetEntryRepository.findById(entryId).orElse(entry);
        model.addAttribute("entry", saved);
        model.addAttribute("fieldDefs",
                fieldDefinitionsService.resolveForEntry(sheet, saved));
        model.addAttribute("attachmentsById", attachmentService.findForLogSheet(id).stream()
                .collect(Collectors.toMap(Attachment::getId, a -> a, (a, b) -> a, LinkedHashMap::new)));
        model.addAttribute("logSheetId", id);
        return "fragments/fill-entry-summary :: summaryFromModel";
    }

    /**
     * Saves the round's own answers, and nothing else.
     *
     * <h2>Why it is its own endpoint</h2>
     *
     * <p>The per-asset dialog taught the shape: one small save per section beats one submission
     * held in the browser until the end, because a closed tab then costs a field rather than an
     * afternoon, and because what each section shows afterwards is what the server actually
     * stored. The round-level questions are a section like any other.
     *
     * <p><b>Gated on {@code POST:/log-sheets/{id}/complete}</b>, like the per-asset draft and the
     * attachment upload beside it: saving part of a sheet is part of filling it, so whoever may
     * fill it may do this, and nobody else. A new authority would have to be granted everywhere
     * the old one already is, for no new decision (AGENTS.md #9).
     *
     * <p>Returns the section re-rendered by the server rather than JSON, so the browser never
     * formats a value — the same contract {@code fill-entry-summary} documents, and the reason
     * a unit and a multiselect's separator are written once instead of in two languages.
     */
    @PostMapping("/{id}/sheet-form/draft")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/complete')")
    public String saveSheetFormDraft(@PathVariable Long id, HttpServletRequest request, Model model) {
        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(id);
        if (!webCompletionAccess.canCompleteOnWeb(sheet)) {
            throw new AccessDeniedException(FaMessages.logSheetWebCompletionDenied());
        }
        // Only the round-level parameters, whatever else the request happens to carry: this
        // endpoint must not become a second way to write an asset's readings.
        //
        // `true` — and this is the ONLY caller that passes it. A required question is enforced
        // here because here is where somebody is answering these questions and nothing else, so
        // "you have not answered this" is help. Everywhere else it is false on purpose: see the
        // parameter's own javadoc on saveDraftFromWeb.
        try {
            logSheetService.saveDraftFromWeb(id, Map.of(), null, parseSheetFormValues(request), true);
        } catch (IllegalArgumentException e) {
            // Re-thrown as a fragment refusal so the operator is told WHAT was wrong. Left to
            // the usual handler it would become a redirect, and the caller — a fetch using
            // `redirect: 'manual'` — would show its message about an expired session instead.
            throw new FragmentRequestException(ErrorTranslator.toFa(e.getMessage()), e);
        }

        LogSheet saved = logSheetAccessService.requireVisibleLogSheet(id);
        model.addAttribute("logSheet", saved);
        model.addAttribute("sheetQuestions", sheetFieldService.resolveForSheet(saved));
        // The SAME two attributes the page gave the fragment on the full render. Without them a
        // round-level question of a media type blew up here with a 500 — `fill-field-input`
        // reads `attachmentLimits.maxCountFor(...)`, which was null on this path, and the
        // operator saw «ذخیره نشد» on a save the server had already applied. It appeared only
        // once V10 allowed a photo question, which is why the endpoint shipped without them.
        model.addAttribute("attachmentLimits", appSettingsService.getAttachmentLimits());
        model.addAttribute("attachmentsById", attachmentService.findForLogSheet(id).stream()
                .collect(Collectors.toMap(Attachment::getId, a -> a, (a, b) -> a, LinkedHashMap::new)));
        return "fragments/sheet-form :: inputsFromModel";
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('POST:/log-sheets/{id}/complete')")
    public String complete(@PathVariable Long id, HttpServletRequest request, RedirectAttributes ra) {
        logSheetService.completeFromWeb(id, parseEntryValues(request), request.getParameter("notes"),
                parseSheetFormValues(request));
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

    /**
     * Says "this asset was submitted", independently of whether it carried any values.
     *
     * <p>Unambiguous against a field parameter because a field is {@code fd_<entryId>_<key>} and
     * an entry id is always numeric — nothing else can produce the literal segment
     * {@code present} in that position.
     */
    private static final String ENTRY_SUBMITTED_MARKER = "fd_present_";

    /**
     * Turns a web form's {@code fd_*} parameters into {@code entryId -> field -> value}.
     *
     * <h2>Why a parameter exists purely to say "I was submitted"</h2>
     *
     * <p>A form does not post a {@code <select multiple>} with nothing selected. For nearly every
     * asset that is invisible — an empty text input still posts {@code ""} and a checkbox always
     * posts its hidden {@code false}, so the entry appears in the request either way. But an asset
     * whose fields are <b>all</b> multiselects, with all of them cleared, produces a request
     * carrying no parameters for that entry at all.
     *
     * <p>That is indistinguishable from an asset the request never mentioned, and the two need
     * opposite treatment: one means "store nothing for this asset", the other means "leave this
     * asset exactly as it is". Inferring it from absence, this method returned no map, {@code
     * applyWebEntryValues} skipped the entry, and the dialog reported success having written
     * nothing — the operator cleared a reading, was told it saved, and the old value stayed.
     *
     * <p>So the dialog states it instead. {@code fd_present_<entryId>} seeds an <b>empty</b> map
     * for that entry, which every reader downstream already handles correctly: it is a submission
     * whose answers are all gone, and it clears the entry, records a revision for what it
     * replaced, and validates as unanswered at final submission.
     *
     * <p><b>Absence still means "not submitted", and that is load-bearing.</b> A dialog save names
     * one asset out of possibly hundreds; reading a missing marker as "cleared" would blank every
     * other asset on the sheet.
     */
    private Map<String, Map<String, Object>> parseEntryValues(HttpServletRequest request) {
        Map<String, Map<String, Object>> entryValues = new HashMap<>();
        for (Map.Entry<String, String[]> p : request.getParameterMap().entrySet()) {
            String name = p.getKey();
            if (!name.startsWith("fd_")) continue;
            if (name.startsWith(ENTRY_SUBMITTED_MARKER)) {
                String entryId = name.substring(ENTRY_SUBMITTED_MARKER.length());
                // computeIfAbsent, never put: the marker and this entry's field parameters arrive
                // in whatever order the container iterated them, and a plain put would discard
                // values already parsed.
                if (!entryId.isBlank()) {
                    entryValues.computeIfAbsent(entryId, k -> new LinkedHashMap<>());
                }
                continue;
            }
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

    /** The marker saying the round-level form was submitted, even when it posted nothing. */
    private static final String SHEET_FORM_MARKER = "sfd_present";

    /** The prefix of every round-level answer: {@code sfd_<fieldKey>}. */
    private static final String SHEET_FORM_PREFIX = "sfd_";

    /**
     * Turns a web form's {@code sfd_*} parameters into {@code fieldKey -> value}.
     *
     * <h2>Its own prefix, and not by preference</h2>
     *
     * <p>{@link #parseEntryValues} splits a name at the first {@code _} after index 3, so
     * {@code fd_sheet_Pipes} would parse as entry id {@code "sheet"} and then be dropped by
     * {@code applyWebEntryValues} because no entry has that id — silently, with a success
     * message. {@code sfd_} does not start with {@code fd_}, so the two parsers cannot see each
     * other's parameters.
     *
     * <p>{@code sfd_present} is the twin of {@code fd_present_<id>} and exists for the same
     * reason: a cleared {@code <select multiple>} posts nothing at all, so without it a form
     * whose questions were all multiselects, all emptied, would be indistinguishable from a
     * request that never carried the section — and clearing an answer would silently do nothing.
     *
     * @return {@code null} when the request did not carry the section, which every caller reads
     *         as "not submitted" rather than "cleared"
     */
    private Map<String, Object> parseSheetFormValues(HttpServletRequest request) {
        Map<String, String[]> parameters = request.getParameterMap();
        if (!parameters.containsKey(SHEET_FORM_MARKER)) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> parameter : parameters.entrySet()) {
            String name = parameter.getKey();
            if (name.equals(SHEET_FORM_MARKER) || !name.startsWith(SHEET_FORM_PREFIX)) {
                continue;
            }
            String fieldKey = name.substring(SHEET_FORM_PREFIX.length());
            if (fieldKey.isBlank()) {
                continue;
            }
            Object value = parseFieldValue(parameter.getValue());
            if (value == null) {
                continue;
            }
            values.put(fieldKey, value);
        }
        return values;
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
