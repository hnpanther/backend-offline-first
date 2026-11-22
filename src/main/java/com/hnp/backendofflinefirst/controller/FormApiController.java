package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.LookupValues;
import com.hnp.backendofflinefirst.dto.FormDefinitionDto;
import com.hnp.backendofflinefirst.dto.FormDraftRequest;
import com.hnp.backendofflinefirst.dto.FormSubmissionDto;
import com.hnp.backendofflinefirst.dto.FormSubmitRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitResult;
import com.hnp.backendofflinefirst.dto.LogSheetContextDto;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.entity.LookupList;
import com.hnp.backendofflinefirst.repository.LookupListRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.FormAccessService;
import com.hnp.backendofflinefirst.service.FormDefinitionService;
import com.hnp.backendofflinefirst.service.FormFieldService;
import com.hnp.backendofflinefirst.service.FormSubmissionService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mobile API for ad-hoc field forms (carrier 3). Separate from log-sheet sync.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FormApiController {

    private final FormAccessService formAccessService;
    private final FormDefinitionService formDefinitionService;
    private final FormFieldService formFieldService;
    private final FormSubmissionService formSubmissionService;
    private final LookupListRepository lookupListRepository;

    @GetMapping("/forms")
    @PreAuthorize("hasAuthority('GET:/api/forms')")
    public List<FormDefinitionDto> listFillable() {
        Long userId = SecurityUtils.currentUserId();
        List<FormDefinitionDto> out = new ArrayList<>();
        for (FormDefinition form : formAccessService.fillableForms(userId)) {
            out.add(toDto(form));
        }
        return out;
    }

    @GetMapping("/forms/{id}")
    @PreAuthorize("hasAuthority('GET:/api/forms/{id}')")
    public FormDefinitionDto getFillable(@PathVariable Long id) {
        Long userId = SecurityUtils.currentUserId();
        FormDefinition form = formDefinitionService.require(id);
        boolean allowed = formAccessService.fillableForms(userId).stream()
                .anyMatch(f -> f.getId().equals(id));
        if (!allowed) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Form is not available for filling.");
        }
        return toDto(form);
    }

    @PostMapping("/forms/drafts")
    @PreAuthorize("hasAuthority('POST:/api/forms/drafts')")
    public FormSubmitResult upsertDraft(@RequestBody FormDraftRequest request) {
        FormSubmitResult result = formSubmissionService.upsertDraft(
                SecurityUtils.currentUserId(), request, ActionSource.MOBILE);
        return localise(result);
    }

    @PostMapping("/forms/submit")
    @PreAuthorize("hasAuthority('POST:/api/forms/submit')")
    public FormSubmitResult submit(@RequestBody FormSubmitRequest request) {
        FormSubmitResult result = formSubmissionService.submitFinal(
                SecurityUtils.currentUserId(), request, ActionSource.MOBILE);
        return localise(result);
    }

    @GetMapping("/form-submissions")
    @PreAuthorize("hasAuthority('GET:/api/form-submissions')")
    public List<FormSubmissionDto> listOwn() {
        return formSubmissionService.ownSubmissions(SecurityUtils.currentUserId()).stream()
                .map(FormSubmissionDto::from)
                .toList();
    }

    @GetMapping("/form-submissions/{id}")
    @PreAuthorize("hasAuthority('GET:/api/form-submissions/{id}')")
    public FormSubmissionDto getOne(@PathVariable Long id) {
        FormSubmission submission = formAccessService.requireVisible(SecurityUtils.currentUserId(), id);
        return FormSubmissionDto.from(submission);
    }

    private FormDefinitionDto toDto(FormDefinition form) {
        List<FieldDefinitionSnapshot> fields = formFieldService.captureSnapshot(form.getId());
        return FormDefinitionDto.from(
                form,
                formDefinitionService.unitIds(form.getId()),
                fields,
                requiredLookups(fields));
    }

    private List<LogSheetContextDto.RequiredLookup> requiredLookups(List<FieldDefinitionSnapshot> fields) {
        Set<String> codes = new LinkedHashSet<>();
        if (fields != null) {
            for (FieldDefinitionSnapshot field : fields) {
                if (!LookupValues.isLookupField(field.getDataType())) {
                    continue;
                }
                String list = FieldValidationSupport.lookupList(field.getValidation());
                if (list != null) {
                    codes.add(list.toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<String, LookupList> resolved = new LinkedHashMap<>();
        for (String code : codes) {
            lookupListRepository.findByCodeIgnoreCase(code).ifPresent(l -> resolved.put(code, l));
        }
        return resolved.values().stream()
                .map(l -> new LogSheetContextDto.RequiredLookup(l.getCode(), l.getName(), l.getRevision()))
                .toList();
    }

    private FormSubmitResult localise(FormSubmitResult result) {
        if (result.getMessage() != null) {
            result.setMessage(ErrorTranslator.toFa(result.getMessage()));
        }
        return result;
    }
}
