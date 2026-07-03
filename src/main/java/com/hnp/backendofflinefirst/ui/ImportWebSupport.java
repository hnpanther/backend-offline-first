package com.hnp.backendofflinefirst.ui;

import com.hnp.backendofflinefirst.dto.ImportResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Applies import results to flash attributes with Persian messages. */
public final class ImportWebSupport {

    private ImportWebSupport() {}

    public static void applyImportResult(ImportResult result, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("successMessage", ImportDisplay.summary(result));
        if (result.hasErrors()) {
            redirectAttributes.addFlashAttribute("importErrors", ImportDisplay.errors(result));
        }
    }

    public static void applyFileError(Exception e, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", FaMessages.fileProcessingError(e));
    }
}
