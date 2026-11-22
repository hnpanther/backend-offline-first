package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.FormDefinitionService;
import com.hnp.backendofflinefirst.service.FormAccessService;
import com.hnp.backendofflinefirst.service.FormFieldService;
import com.hnp.backendofflinefirst.service.FormSubmissionService;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Panel VIEW for ad-hoc form submissions — filtered by reporter_unit / subject_unit / author.
 */
@Controller
@RequestMapping("/form-submissions")
@RequiredArgsConstructor
public class FormSubmissionWebController {

    private final FormSubmissionService formSubmissionService;
    private final FormAccessService formAccessService;
    private final FormDefinitionService formDefinitionService;
    private final FormFieldService formFieldService;

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
        model.addAttribute("activePage", "form-submissions");
        model.addAttribute("submissions", result.getContent());
        model.addAttribute("formId", formId);
        model.addAttribute("status", status);
        model.addAttribute("forms", formDefinitionService.page("",
                org.springframework.data.domain.PageRequest.of(0, 500)).getContent());
        WebListSupport.addPagination(model, result, null, page, pageSize);
        return "form-submissions";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('GET:/form-submissions/{id}')")
    public String detail(@PathVariable Long id, Model model) {
        FormSubmission submission = formAccessService.requireVisible(SecurityUtils.currentUserId(), id);
        FormDefinition form = formDefinitionService.require(submission.getFormDefinitionId());
        model.addAttribute("activePage", "form-submissions");
        model.addAttribute("submission", submission);
        model.addAttribute("form", form);
        model.addAttribute("fields", formFieldService.resolveForSubmission(submission));
        return "form-submission-detail";
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
}
