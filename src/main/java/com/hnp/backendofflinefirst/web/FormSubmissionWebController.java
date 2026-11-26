package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FormSubmissionActionType;
import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import com.hnp.backendofflinefirst.dto.AttachmentDto;
import com.hnp.backendofflinefirst.dto.FormDraftRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitResult;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import com.hnp.backendofflinefirst.service.AttachmentService;
import com.hnp.backendofflinefirst.service.FormAccessService;
import com.hnp.backendofflinefirst.service.FormDefinitionService;
import com.hnp.backendofflinefirst.service.FormFieldService;
import com.hnp.backendofflinefirst.service.FormSubmissionActionLogger;
import com.hnp.backendofflinefirst.service.FormSubmissionRevisionService;
import com.hnp.backendofflinefirst.service.FormSubmissionService;
import com.hnp.backendofflinefirst.service.LookupGrantService;
import com.hnp.backendofflinefirst.service.LookupListService;
import com.hnp.backendofflinefirst.service.MasterDataOptionsService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Panel VIEW / FILL / review for ad-hoc form submissions.
 *
 * <p>List visibility keys off {@code subject_unit_id} (mirrors log-sheet {@code operational_unit_id}).
 * On FILL the panel only collects the filing unit («واحد ثبت‌کننده»); the service mirrors it onto
 * {@code subject_unit_id}.
 *
 * <p>The unfiltered list («همهٔ ارسال‌های فرم») is {@code GET:/form-submissions/all}, ADMIN by
 * default. A form menu ({@code ?formId=}) stays on {@code GET:/form-submissions} so unit VIEW
 * is unchanged, and «ثبت جدید» on that page files only that form.
 */
@Controller
@RequestMapping("/form-submissions")
@RequiredArgsConstructor
public class FormSubmissionWebController {

    private final FormSubmissionService formSubmissionService;
    private final FormAccessService formAccessService;
    private final FormDefinitionService formDefinitionService;
    private final FormFieldService formFieldService;
    private final FormSubmissionActionLogger actionLogger;
    private final FormSubmissionRevisionService revisionService;
    private final LookupGrantService lookupGrantService;
    private final LookupListService lookupListService;
    private final ReferenceLabelService labels;
    private final MasterDataOptionsService optionsService;
    private final AttachmentRepository attachmentRepository;
    private final AttachmentService attachmentService;
    private final AppSettingsService appSettingsService;

    private static final String FORM_PRESENT_MARKER = "fd_present";
    private static final String FORM_FIELD_PREFIX = "fd_";

    /**
     * «همهٔ ارسال‌های فرم» — every form, and «ثبت جدید» may pick any of them.
     * Absent {@code formId} is the whole point of this mapping: a present one is the menu page.
     */
    @GetMapping(params = "!formId")
    @PreAuthorize("hasAuthority('GET:/form-submissions/all')")
    public String listAll(@RequestParam(required = false) String status,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(required = false) Integer size,
                          Model model) {
        return renderList(null, status, page, size, model);
    }

    /**
     * One form's menu. The filter and «ثبت جدید» stay on this form; unit VIEW is this authority,
     * not the all-submissions one.
     */
    @GetMapping(params = "formId")
    @PreAuthorize("hasAuthority('GET:/form-submissions')")
    public String listForForm(@RequestParam Long formId,
                              @RequestParam(required = false) String status,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(required = false) Integer size,
                              Model model) {
        return renderList(formId, status, page, size, model);
    }

    private String renderList(Long formId, String status, int page, Integer size, Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        FormSubmissionStatus parsed = parseStatus(status);
        var result = formSubmissionService.pageVisible(
                SecurityUtils.currentUserId(), formId, parsed,
                WebListSupport.pageable(page, pageSize));
        List<FormSubmission> rows = result.getContent();

        Set<Long> formIds = rows.stream().map(FormSubmission::getFormDefinitionId).collect(Collectors.toSet());
        Set<Long> unitIds = new HashSet<>();
        for (FormSubmission s : rows) {
            unitIds.add(s.getReporterUnitId());
            unitIds.add(s.getSubjectUnitId());
        }
        Map<Long, String> formTitles = new LinkedHashMap<>();
        if (!formIds.isEmpty()) {
            for (FormDefinition def : formDefinitionService.findByIds(formIds)) {
                formTitles.put(def.getId(), def.getTitle());
            }
            for (Long id : formIds) {
                formTitles.putIfAbsent(id, "#" + id);
            }
        }

        FormDefinition lockedForm = formId == null ? null : formDefinitionService.require(formId);
        model.addAttribute("activePage", formId != null ? "form-submissions-" + formId : "form-submissions");
        model.addAttribute("submissions", rows);
        model.addAttribute("formId", formId);
        model.addAttribute("lockedForm", lockedForm);
        model.addAttribute("status", status);
        model.addAttribute("formTitles", formTitles);
        model.addAttribute("unitLabels", labels.operationalUnitLabelsFor(unitIds));
        // Filter dropdown + «ثبت جدید» reuse FormNavAdvice's navForms (already on the model).
        model.addAttribute("canFill", SecurityUtils.hasPermission("POST:/form-submissions/draft"));
        WebListSupport.addPagination(model, result, null, page, pageSize);
        return "form-submissions";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('POST:/form-submissions/draft')")
    public String newForm(@RequestParam Long formId, Model model) {
        Long userId = SecurityUtils.currentUserId();
        FormDefinition form = formDefinitionService.require(formId);
        if (!form.isActive()) {
            throw new AccessDeniedException("Form is not available for filling.");
        }
        List<FieldDefinition> fields = formFieldService.activeDefinitions(formId);
        // Filing unit only — intersect caller's accessible units with the form's linked units
        // (same axis as log-sheet operational unit pick when the operator has several).
        Set<Long> reporterIds = filingUnitOptions(userId, formId);
        model.addAttribute("activePage", "form-submissions-" + formId);
        model.addAttribute("form", form);
        model.addAttribute("fields", fields);
        model.addAttribute("formData", Map.of());
        model.addAttribute("submission", null);
        model.addAttribute("formSubmission", null);
        model.addAttribute("localId", "web-" + UUID.randomUUID());
        model.addAttribute("submitClientActionId", "web-submit-" + UUID.randomUUID());
        model.addAttribute("reporterUnitOptions", optionsService.operationalUnitOptionsByIds(reporterIds));
        model.addAttribute("attachmentLimits", appSettingsService.getAttachmentLimits());
        return "form-submission-fill";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('POST:/form-submissions/draft')")
    public String editDraft(@PathVariable Long id, Model model) {
        Long userId = SecurityUtils.currentUserId();
        FormSubmission submission = formAccessService.requireVisible(userId, id);
        formAccessService.assertCanEditDraft(userId, submission);
        FormDefinition form = formDefinitionService.require(submission.getFormDefinitionId());
        List<FieldDefinition> fields = formFieldService.resolveForSubmission(submission);
        Set<Long> reporterIds = filingUnitOptions(userId, form.getId());
        Map<String, Attachment> attachmentsById = attachmentsById(submission.getId());
        model.addAttribute("activePage", "form-submissions-" + form.getId());
        model.addAttribute("form", form);
        model.addAttribute("fields", fields);
        model.addAttribute("formData", submission.getFormData() != null ? submission.getFormData() : Map.of());
        model.addAttribute("submission", submission);
        model.addAttribute("formSubmission", submission);
        model.addAttribute("localId", submission.getLocalId() != null
                ? submission.getLocalId()
                : "web-" + UUID.randomUUID());
        model.addAttribute("submitClientActionId", "web-submit-" + UUID.randomUUID());
        model.addAttribute("attachmentsById", attachmentsById);
        model.addAttribute("reporterUnitOptions", optionsService.operationalUnitOptionsByIds(reporterIds));
        model.addAttribute("attachmentLimits", appSettingsService.getAttachmentLimits());
        model.addAttribute("revisions", revisionService.findForSubmission(submission.getId()));
        return "form-submission-fill";
    }

    @PostMapping("/draft")
    @PreAuthorize("hasAuthority('POST:/form-submissions/draft')")
    public String saveDraft(@RequestParam Long formDefinitionId,
                            @RequestParam(required = false) Long submissionId,
                            @RequestParam(required = false) Long expectedUpdatedAt,
                            @RequestParam(required = false) String localId,
                            @RequestParam Long reporterUnitId,
                            HttpServletRequest httpRequest,
                            RedirectAttributes ra) {
        FormDraftRequest request = new FormDraftRequest();
        request.setFormDefinitionId(formDefinitionId);
        request.setSubmissionId(submissionId);
        request.setExpectedUpdatedAt(expectedUpdatedAt);
        request.setLocalId(blankToNull(localId));
        request.setReporterUnitId(reporterUnitId);
        // subject_unit_id is set by the service (= reporter); body field ignored if present.
        request.setFormData(extractFormData(formDefinitionId, submissionId, httpRequest));
        request.setClientActionId("web-draft-" + UUID.randomUUID());
        FormSubmitResult result = formSubmissionService.upsertDraft(
                SecurityUtils.currentUserId(), request, ActionSource.WEB);
        if (!FormSubmissionService.OUTCOME_OK.equals(result.getOutcome())) {
            if (FormSubmissionService.OUTCOME_CONFLICT.equals(result.getOutcome()) && result.getSubmissionId() != null) {
                ra.addFlashAttribute("conflictingAnswers", request.getFormData());
                ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(result.getMessage()));
                return "redirect:/form-submissions/" + result.getSubmissionId();
            }
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(
                    result.getMessage() != null ? result.getMessage() : "Draft save failed."));
            if (submissionId != null) {
                return "redirect:/form-submissions/" + submissionId + "/edit";
            }
            return "redirect:/form-submissions/new?formId=" + formDefinitionId;
        }
        ra.addFlashAttribute("successMessage", FaMessages.formDraftSaved());
        return "redirect:/form-submissions/" + result.getSubmissionId() + "/edit";
    }

    @PostMapping("/submit")
    @PreAuthorize("hasAuthority('POST:/form-submissions/submit')")
    public String submit(@RequestParam Long formDefinitionId,
                         @RequestParam(required = false) Long submissionId,
                         @RequestParam(required = false) Long expectedUpdatedAt,
                         @RequestParam(required = false) String localId,
                         @RequestParam(required = false) String clientActionId,
                         @RequestParam Long reporterUnitId,
                         HttpServletRequest httpRequest,
                         RedirectAttributes ra) {
        FormSubmitRequest request = new FormSubmitRequest();
        request.setFormDefinitionId(formDefinitionId);
        request.setSubmissionId(submissionId);
        request.setExpectedUpdatedAt(expectedUpdatedAt);
        request.setLocalId(blankToNull(localId));
        request.setReporterUnitId(reporterUnitId);
        request.setFormData(extractFormData(formDefinitionId, submissionId, httpRequest));
        // Prefer the page-stable key so a double-click is idempotent (DUPLICATE), not two rows.
        String actionId = blankToNull(clientActionId);
        request.setClientActionId(actionId != null ? actionId : "web-submit-" + UUID.randomUUID());
        FormSubmitResult result = formSubmissionService.submitFinal(
                SecurityUtils.currentUserId(), request, ActionSource.WEB);
        if (!FormSubmissionService.OUTCOME_OK.equals(result.getOutcome())
                && !FormSubmissionService.OUTCOME_DUPLICATE.equals(result.getOutcome())) {
            if (FormSubmissionService.OUTCOME_CONFLICT.equals(result.getOutcome()) && result.getSubmissionId() != null) {
                ra.addFlashAttribute("conflictingAnswers", request.getFormData());
                ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(result.getMessage()));
                return "redirect:/form-submissions/" + result.getSubmissionId();
            }
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(
                    result.getMessage() != null ? result.getMessage() : "Submit failed."));
            if (submissionId != null) {
                return "redirect:/form-submissions/" + submissionId + "/edit";
            }
            return "redirect:/form-submissions/new?formId=" + formDefinitionId;
        }
        ra.addFlashAttribute("successMessage", FaMessages.formSubmitted());
        return "redirect:/form-submissions/" + result.getSubmissionId();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('GET:/form-submissions/{id}')")
    public String detail(@PathVariable Long id, Model model) {
        FormSubmission submission = formAccessService.requireVisible(SecurityUtils.currentUserId(), id);
        FormDefinition form = formDefinitionService.require(submission.getFormDefinitionId());
        List<FieldDefinition> fields = formFieldService.resolveForSubmission(submission);
        Map<String, Attachment> attachmentsById = attachmentsById(id);
        model.addAttribute("activePage", "form-submissions-" + form.getId());
        model.addAttribute("submission", submission);
        model.addAttribute("form", form);
        model.addAttribute("fields", fields);
        model.addAttribute("attachmentsById", attachmentsById);
        // One label: subject mirrors reporter on every write path.
        model.addAttribute("reporterUnitLabel", labels.operationalUnitCodeAndName(submission.getReporterUnitId()));
        model.addAttribute("approverLabel", submission.getApprovedByUserId() != null
                ? labels.userDisplayName(submission.getApprovedByUserId()) : null);
        model.addAttribute("actionHistory", actionLogger.history(id));
        model.addAttribute("revisions", revisionService.findForSubmission(id));
        Long me = SecurityUtils.currentUserId();
        boolean draft = submission.getStatus() == FormSubmissionStatus.DRAFT;
        boolean mayEdit = false;
        if (draft && SecurityUtils.hasPermission("POST:/form-submissions/draft")) {
            try {
                formAccessService.assertCanEditDraft(me, submission);
                mayEdit = true;
            } catch (AccessDeniedException | IllegalStateException ignored) {
                mayEdit = false;
            }
        }
        model.addAttribute("canEditDraft", mayEdit);
        model.addAttribute("canReopen", submission.getStatus() == FormSubmissionStatus.SUBMITTED
                && SecurityUtils.hasPermission("POST:/form-submissions/{id}/reopen"));
        return "form-submission-detail";
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('POST:/form-submissions/{id}/reopen')")
    public String reopen(@PathVariable Long id,
                         @RequestParam(required = false) Long expectedUpdatedAt,
                         @RequestParam(required = false) String comment,
                         RedirectAttributes ra) {
        FormSubmission reopened = formSubmissionService.review(id, SecurityUtils.currentUserId(), ActionSource.WEB,
                FormSubmissionActionType.REOPEN, comment, expectedUpdatedAt);
        ra.addFlashAttribute("successMessage", FaMessages.formSubmissionReopened());
        return "redirect:/form-submissions/" + reopened.getId() + "/edit";
    }

    /**
     * Panel lookup picker for a draft/editable filing — same grant shape as log-sheet fill.
     */
    @GetMapping("/{id}/lookups/{listCode}")
    @PreAuthorize("hasAuthority('POST:/form-submissions/draft')")
    @ResponseBody
    public ResponseEntity<List<LookupListService.PickerItem>> searchLookup(
            @PathVariable Long id,
            @PathVariable String listCode,
            @RequestParam(required = false) String q,
            @RequestParam(name = "parent", required = false) List<String> parentCodes) {
        FormSubmission submission = formAccessService.requireVisible(SecurityUtils.currentUserId(), id);
        formAccessService.assertCanEditDraft(SecurityUtils.currentUserId(), submission);
        if (!lookupGrantService.formSubmissionUsesList(submission, listCode)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lookupListService.searchForPicker(listCode, q == null ? "" : q, parentCodes));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('POST:/form-submissions/{id}/approve')")
    public String approve(@PathVariable Long id,
                          @RequestParam(required = false) Long expectedUpdatedAt,
                          @RequestParam(required = false) String comment,
                          RedirectAttributes ra) {
        formSubmissionService.review(id, SecurityUtils.currentUserId(), ActionSource.WEB,
                FormSubmissionActionType.APPROVE, comment, expectedUpdatedAt);
        ra.addFlashAttribute("successMessage", FaMessages.formSubmissionApproved());
        return "redirect:/form-submissions/" + id;
    }

    @PostMapping("/{id}/unapprove")
    @PreAuthorize("hasAuthority('POST:/form-submissions/{id}/unapprove')")
    public String unapprove(@PathVariable Long id,
                            @RequestParam(required = false) Long expectedUpdatedAt,
                            @RequestParam(required = false) String comment,
                            RedirectAttributes ra) {
        formSubmissionService.review(id, SecurityUtils.currentUserId(), ActionSource.WEB,
                FormSubmissionActionType.UNAPPROVE, comment, expectedUpdatedAt);
        ra.addFlashAttribute("successMessage", FaMessages.formSubmissionUnapproved());
        return "redirect:/form-submissions/" + id;
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('POST:/form-submissions/{id}/void')")
    public String voidSubmission(@PathVariable Long id,
                                 @RequestParam(required = false) Long expectedUpdatedAt,
                                 @RequestParam(required = false) String comment,
                                 RedirectAttributes ra) {
        formSubmissionService.review(id, SecurityUtils.currentUserId(), ActionSource.WEB,
                FormSubmissionActionType.VOID, comment, expectedUpdatedAt);
        ra.addFlashAttribute("successMessage", FaMessages.formSubmissionVoided());
        return "redirect:/form-submissions/" + id;
    }

    @PostMapping("/{id}/unvoid")
    @PreAuthorize("hasAuthority('POST:/form-submissions/{id}/unvoid')")
    public String unvoid(@PathVariable Long id,
                         @RequestParam(required = false) Long expectedUpdatedAt,
                         @RequestParam(required = false) String comment,
                         RedirectAttributes ra) {
        formSubmissionService.review(id, SecurityUtils.currentUserId(), ActionSource.WEB,
                FormSubmissionActionType.UNVOID, comment, expectedUpdatedAt);
        ra.addFlashAttribute("successMessage", FaMessages.formSubmissionUnvoided());
        return "redirect:/form-submissions/" + id;
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    @PreAuthorize("hasAuthority('GET:/form-submissions/{id}')")
    public ResponseEntity<Resource> attachment(@PathVariable Long id,
                                               @PathVariable String attachmentId) throws IOException {
        formAccessService.requireVisible(SecurityUtils.currentUserId(), id);
        AttachmentService.DownloadedAttachment result = attachmentService.download(attachmentId);
        if (!Objects.equals(result.attachment().getFormSubmissionId(), id)) {
            throw new IllegalArgumentException("Attachment does not belong to this form submission.");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.attachment().getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(result.attachment().getId()).build().toString())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .body(result.content());
    }

    @PostMapping("/{id}/attachments")
    @PreAuthorize("hasAuthority('POST:/form-submissions/draft')")
    @ResponseBody
    public AttachmentDto uploadAttachment(@PathVariable Long id,
                                          @RequestParam("fieldKey") String fieldKey,
                                          @RequestParam(value = "durationMs", required = false) Long durationMs,
                                          @RequestParam(value = "width", required = false) Integer width,
                                          @RequestParam(value = "height", required = false) Integer height,
                                          @RequestParam("file") MultipartFile file) throws IOException {
        return AttachmentDto.from(attachmentService.uploadForFormSubmission(
                UUID.randomUUID().toString(), id, fieldKey, file.getInputStream(),
                width, height, durationMs));
    }

    /**
     * Removes a file the operator attached on this draft. Same path shape as log-sheet web fill
     * ({@code …/attachments/{id}/delete}) so {@code attachments.js} needs no form-specific branch.
     * The id in the path is the owner: a file from another filing is refused before delete.
     */
    @PostMapping("/{id}/attachments/{attachmentId}/delete")
    @PreAuthorize("hasAuthority('POST:/form-submissions/draft')")
    @ResponseBody
    public Map<String, Object> deleteAttachment(@PathVariable Long id,
                                                @PathVariable String attachmentId) {
        FormSubmission submission = formAccessService.requireVisible(SecurityUtils.currentUserId(), id);
        formAccessService.assertCanEditDraft(SecurityUtils.currentUserId(), submission);
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Attachment does not belong to this form submission."));
        if (!Objects.equals(attachment.getFormSubmissionId(), id)) {
            throw new IllegalArgumentException("Attachment does not belong to this form submission.");
        }
        attachmentService.delete(attachmentId);
        return Map.of("deleted", true);
    }

    /**
     * Units the caller may file as: intersection of session-accessible units and the form's
     * linked units. Plant-wide callers see every linked unit (same as log-sheet custom create
     * offering every unit when unrestricted).
     */
    private Set<Long> filingUnitOptions(Long userId, Long formId) {
        Set<Long> linked = formDefinitionService.unitIds(formId);
        if (!SecurityUtils.isUnitScopedOnly()) {
            return linked;
        }
        Set<Long> accessible = formAccessService.reporterUnitCandidates(userId);
        Set<Long> out = new HashSet<>();
        for (Long id : linked) {
            if (accessible.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private Map<String, Attachment> attachmentsById(Long submissionId) {
        Map<String, Attachment> map = new LinkedHashMap<>();
        for (Attachment a : attachmentRepository.findByFormSubmissionIdOrderByUploadedAtAsc(submissionId)) {
            map.put(a.getId(), a);
        }
        return map;
    }

    /**
     * Parses {@code fd_*} answers the same way log-sheet sheet-form does — multi-value lookup
     * chips and checkbox false/true pairs need the raw {@code String[]} map, not a collapsed
     * single-string {@code @RequestParam Map}.
     */
    private Map<String, Object> extractFormData(Long formDefinitionId, Long submissionId,
                                                HttpServletRequest request) {
        List<FieldDefinition> fields;
        if (submissionId != null) {
            FormSubmission existing = formAccessService.requireVisible(
                    SecurityUtils.currentUserId(), submissionId);
            fields = formFieldService.resolveForSubmission(existing);
        } else {
            fields = formFieldService.activeDefinitions(formDefinitionId);
        }
        Set<String> knownKeys = fields.stream()
                .map(FieldDefinition::getKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, String[]> parameters = request.getParameterMap();
        for (Map.Entry<String, String[]> parameter : parameters.entrySet()) {
            String name = parameter.getKey();
            if (FORM_PRESENT_MARKER.equals(name) || !name.startsWith(FORM_FIELD_PREFIX)) {
                continue;
            }
            String fieldKey = name.substring(FORM_FIELD_PREFIX.length());
            if (fieldKey.isBlank() || !knownKeys.contains(fieldKey)) {
                continue;
            }
            Object value = parseFieldValue(parameter.getValue());
            if (value == null) {
                continue;
            }
            data.put(fieldKey, value);
        }
        return data;
    }

    private static Object parseFieldValue(String[] values) {
        if (values == null || values.length == 0) {
            return null;
        }
        if (values.length == 2 && "false".equals(values[0]) && "true".equals(values[1])) {
            return true;
        }
        if (values.length == 1 && "false".equals(values[0])) {
            return false;
        }
        if (values.length > 1) {
            return new ArrayList<>(List.of(values));
        }
        if ("true".equals(values[0])) {
            return true;
        }
        String raw = values[0];
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static FormSubmissionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return FormSubmissionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown form submission status: " + status);
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
