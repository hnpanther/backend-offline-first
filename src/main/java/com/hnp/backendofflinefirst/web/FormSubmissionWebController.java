package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.ActionSource;
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
import com.hnp.backendofflinefirst.service.AttachmentService;
import com.hnp.backendofflinefirst.service.FormAccessService;
import com.hnp.backendofflinefirst.service.FormDefinitionService;
import com.hnp.backendofflinefirst.service.FormFieldService;
import com.hnp.backendofflinefirst.service.FormSubmissionActionLogger;
import com.hnp.backendofflinefirst.service.FormSubmissionService;
import com.hnp.backendofflinefirst.service.MasterDataOptionsService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
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
import java.util.HashMap;
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
 * <p>List visibility is subject-unit scoped (mirrors log-sheet {@code operational_unit_id}).
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
    private final ReferenceLabelService labels;
    private final MasterDataOptionsService optionsService;
    private final AttachmentRepository attachmentRepository;
    private final AttachmentService attachmentService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/form-submissions')")
    public String list(@RequestParam(required = false) Long formId,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
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

        model.addAttribute("activePage", formId != null ? "form-submissions-" + formId : "form-submissions");
        model.addAttribute("submissions", rows);
        model.addAttribute("formId", formId);
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
        Set<Long> reporterIds = formAccessService.reporterUnitCandidates(userId);
        // All form-linked subjects — FILL allows cross-unit subject (security.md); do not
        // narrow to accessible units here or the panel silently blocks what the API allows.
        Set<Long> subjectIds = formDefinitionService.unitIds(formId);
        model.addAttribute("activePage", "form-submissions-" + formId);
        model.addAttribute("form", form);
        model.addAttribute("fields", fields);
        model.addAttribute("formData", Map.of());
        model.addAttribute("submission", null);
        model.addAttribute("localId", "web-" + UUID.randomUUID());
        model.addAttribute("submitClientActionId", "web-submit-" + UUID.randomUUID());
        model.addAttribute("reporterUnitOptions", optionsService.operationalUnitOptionsByIds(reporterIds));
        model.addAttribute("subjectUnitOptions", optionsService.operationalUnitOptionsByIds(subjectIds));
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
        Set<Long> reporterIds = formAccessService.reporterUnitCandidates(userId);
        Set<Long> subjectIds = formDefinitionService.unitIds(form.getId());
        Map<String, Attachment> attachmentsById = attachmentsById(submission.getId());
        model.addAttribute("activePage", "form-submissions-" + form.getId());
        model.addAttribute("form", form);
        model.addAttribute("fields", fields);
        model.addAttribute("formData", submission.getFormData() != null ? submission.getFormData() : Map.of());
        model.addAttribute("submission", submission);
        model.addAttribute("localId", submission.getLocalId() != null
                ? submission.getLocalId()
                : "web-" + UUID.randomUUID());
        model.addAttribute("submitClientActionId", "web-submit-" + UUID.randomUUID());
        model.addAttribute("attachmentsById", attachmentsById);
        model.addAttribute("reporterUnitOptions", optionsService.operationalUnitOptionsByIds(reporterIds));
        model.addAttribute("subjectUnitOptions", optionsService.operationalUnitOptionsByIds(subjectIds));
        return "form-submission-fill";
    }

    @PostMapping("/draft")
    @PreAuthorize("hasAuthority('POST:/form-submissions/draft')")
    public String saveDraft(@RequestParam Long formDefinitionId,
                            @RequestParam(required = false) Long submissionId,
                            @RequestParam(required = false) String localId,
                            @RequestParam Long reporterUnitId,
                            @RequestParam Long subjectUnitId,
                            @RequestParam Map<String, String> params,
                            RedirectAttributes ra) {
        FormDraftRequest request = new FormDraftRequest();
        request.setFormDefinitionId(formDefinitionId);
        request.setSubmissionId(submissionId);
        request.setLocalId(blankToNull(localId));
        request.setReporterUnitId(reporterUnitId);
        request.setSubjectUnitId(subjectUnitId);
        request.setFormData(extractFormData(formDefinitionId, submissionId, params));
        request.setClientActionId("web-draft-" + UUID.randomUUID());
        FormSubmitResult result = formSubmissionService.upsertDraft(
                SecurityUtils.currentUserId(), request, ActionSource.WEB);
        if (!FormSubmissionService.OUTCOME_OK.equals(result.getOutcome())) {
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
                         @RequestParam(required = false) String localId,
                         @RequestParam(required = false) String clientActionId,
                         @RequestParam Long reporterUnitId,
                         @RequestParam Long subjectUnitId,
                         @RequestParam Map<String, String> params,
                         RedirectAttributes ra) {
        FormSubmitRequest request = new FormSubmitRequest();
        request.setFormDefinitionId(formDefinitionId);
        request.setSubmissionId(submissionId);
        request.setLocalId(blankToNull(localId));
        request.setReporterUnitId(reporterUnitId);
        request.setSubjectUnitId(subjectUnitId);
        request.setFormData(extractFormData(formDefinitionId, submissionId, params));
        // Prefer the page-stable key so a double-click is idempotent (DUPLICATE), not two rows.
        String actionId = blankToNull(clientActionId);
        request.setClientActionId(actionId != null ? actionId : "web-submit-" + UUID.randomUUID());
        FormSubmitResult result = formSubmissionService.submitFinal(
                SecurityUtils.currentUserId(), request, ActionSource.WEB);
        if (!FormSubmissionService.OUTCOME_OK.equals(result.getOutcome())
                && !FormSubmissionService.OUTCOME_DUPLICATE.equals(result.getOutcome())) {
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
        model.addAttribute("reporterUnitLabel", labels.operationalUnitCodeAndName(submission.getReporterUnitId()));
        model.addAttribute("subjectUnitLabel", labels.operationalUnitCodeAndName(submission.getSubjectUnitId()));
        model.addAttribute("approverLabel", submission.getApprovedByUserId() != null
                ? labels.userDisplayName(submission.getApprovedByUserId()) : null);
        model.addAttribute("actionHistory", actionLogger.history(id));
        model.addAttribute("canEditDraft", submission.getStatus() == FormSubmissionStatus.DRAFT
                && Objects.equals(submission.getReportedByUserId(), SecurityUtils.currentUserId())
                && SecurityUtils.hasPermission("POST:/form-submissions/draft"));
        return "form-submission-detail";
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('POST:/form-submissions/{id}/approve')")
    public String approve(@PathVariable Long id,
                          @RequestParam(required = false) String comment,
                          RedirectAttributes ra) {
        formSubmissionService.approve(id, SecurityUtils.currentUserId(), ActionSource.WEB, comment);
        ra.addFlashAttribute("successMessage", FaMessages.formSubmissionApproved());
        return "redirect:/form-submissions/" + id;
    }

    @PostMapping("/{id}/unapprove")
    @PreAuthorize("hasAuthority('POST:/form-submissions/{id}/unapprove')")
    public String unapprove(@PathVariable Long id,
                            @RequestParam(required = false) String comment,
                            RedirectAttributes ra) {
        formSubmissionService.unapprove(id, SecurityUtils.currentUserId(), ActionSource.WEB, comment);
        ra.addFlashAttribute("successMessage", FaMessages.formSubmissionUnapproved());
        return "redirect:/form-submissions/" + id;
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('POST:/form-submissions/{id}/void')")
    public String voidSubmission(@PathVariable Long id,
                                 @RequestParam(required = false) String comment,
                                 RedirectAttributes ra) {
        formSubmissionService.voidSubmitted(id, SecurityUtils.currentUserId(), ActionSource.WEB, comment);
        ra.addFlashAttribute("successMessage", FaMessages.formSubmissionVoided());
        return "redirect:/form-submissions/" + id;
    }

    @PostMapping("/{id}/unvoid")
    @PreAuthorize("hasAuthority('POST:/form-submissions/{id}/unvoid')")
    public String unvoid(@PathVariable Long id,
                         @RequestParam(required = false) String comment,
                         RedirectAttributes ra) {
        formSubmissionService.restoreVoided(id, SecurityUtils.currentUserId(), ActionSource.WEB, comment);
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

    private Map<String, Attachment> attachmentsById(Long submissionId) {
        Map<String, Attachment> map = new LinkedHashMap<>();
        for (Attachment a : attachmentRepository.findByFormSubmissionIdOrderByUploadedAtAsc(submissionId)) {
            map.put(a.getId(), a);
        }
        return map;
    }

    private Map<String, Object> extractFormData(Long formDefinitionId, Long submissionId,
                                                Map<String, String> params) {
        List<FieldDefinition> fields;
        if (submissionId != null) {
            // Visibility first — never resolve another user's draft by id alone.
            FormSubmission existing = formAccessService.requireVisible(
                    SecurityUtils.currentUserId(), submissionId);
            fields = formFieldService.resolveForSubmission(existing);
        } else {
            fields = formFieldService.activeDefinitions(formDefinitionId);
        }
        Map<String, Object> data = new HashMap<>();
        for (FieldDefinition field : fields) {
            if (field.getKey() == null) {
                continue;
            }
            String param = "fd_" + field.getKey();
            if (!params.containsKey(param)) {
                continue;
            }
            String raw = params.get(param);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            data.put(field.getKey(), coerce(field, raw.trim()));
        }
        return data;
    }

    private static Object coerce(FieldDefinition field, String raw) {
        String type = field.getDataType() == null ? "text" : field.getDataType().toLowerCase();
        if ("number".equals(type) || "integer".equals(type)) {
            try {
                if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                    return Double.parseDouble(raw);
                }
                return Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                return raw;
            }
        }
        if ("boolean".equals(type) || "checkbox".equals(type)) {
            return "true".equalsIgnoreCase(raw) || "on".equalsIgnoreCase(raw) || "1".equals(raw);
        }
        return raw;
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
