package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.dto.FormDefinitionDto;
import com.hnp.backendofflinefirst.dto.FormDraftRequest;
import com.hnp.backendofflinefirst.dto.FormHistoryPage;
import com.hnp.backendofflinefirst.dto.FormSubmissionDto;
import com.hnp.backendofflinefirst.dto.FormSubmitRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitResult;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.FormAccessService;
import com.hnp.backendofflinefirst.service.FormCatalogService;
import com.hnp.backendofflinefirst.service.FormHistoryService;
import com.hnp.backendofflinefirst.service.FormSubmissionService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * Mobile API for ad-hoc field forms (carrier 3). Separate from log-sheet sync.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FormApiController {

    private final FormCatalogService catalog;
    private final FormHistoryService history;
    private final FormAccessService formAccessService;
    private final FormSubmissionService formSubmissionService;

    @GetMapping("/forms")
    @PreAuthorize("hasAuthority('GET:/api/forms')")
    public List<FormDefinitionDto> listFillable() {
        return catalog.fillable(SecurityUtils.currentUserId(), null);
    }

    @GetMapping("/forms/{id}")
    @PreAuthorize("hasAuthority('GET:/api/forms/{id}')")
    public FormDefinitionDto getFillable(@PathVariable Long id) {
        return catalog.fillable(SecurityUtils.currentUserId(), id).stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("Form is not available for filling."));
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
    public void listOwn(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        history.writeLegacyHistory(SecurityUtils.currentUserId(), response.getOutputStream());
    }

    @GetMapping("/form-submissions/changes")
    @PreAuthorize("hasAuthority('GET:/api/form-submissions')")
    public FormHistoryPage changes(@RequestParam(defaultValue = "0") long after,
                                   @RequestParam(required = false) Long through,
                                   @RequestParam(defaultValue = "0") long since,
                                   @RequestParam(defaultValue = "50") int size) {
        return history.historyPage(SecurityUtils.currentUserId(), after, through, since, size);
    }

    @GetMapping("/form-submissions/{id}")
    @PreAuthorize("hasAuthority('GET:/api/form-submissions/{id}')")
    public FormSubmissionDto getOne(@PathVariable Long id) {
        FormSubmission submission = formAccessService.requireVisible(SecurityUtils.currentUserId(), id);
        return FormSubmissionDto.from(submission);
    }

    private FormSubmitResult localise(FormSubmitResult result) {
        if (result.getMessage() != null) {
            result.setMessage(ErrorTranslator.toFa(result.getMessage()));
        }
        return result;
    }
}
