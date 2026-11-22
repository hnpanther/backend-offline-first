package com.hnp.backendofflinefirst.web.advice;

import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.FormAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * Puts active form definitions on every panel page for the operational-data submenu.
 *
 * <p><b>Navigation only — never access control.</b> Seeing a link does not grant VIEW; the list
 * and detail endpoints still filter by subject-unit scope. See docs/form-menu-design.md in the
 * Project store.
 */
@ControllerAdvice(basePackages = "com.hnp.backendofflinefirst.web")
@RequiredArgsConstructor
public class FormNavAdvice {

    private final FormAccessService formAccessService;

    @ModelAttribute("navForms")
    public List<FormDefinition> navForms() {
        if (!SecurityUtils.hasPermission("GET:/form-submissions")) {
            return List.of();
        }
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return List.of();
        }
        return formAccessService.navForms(userId);
    }
}
