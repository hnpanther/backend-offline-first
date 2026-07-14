package com.hnp.backendofflinefirst.ui;

import com.hnp.backendofflinefirst.dto.BulkDeleteResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

public final class WebBulkDeleteSupport {

    private WebBulkDeleteSupport() {}

    public static void applyResult(BulkDeleteResult result, RedirectAttributes ra, String entityLabelFa) {
        if (result.getSuccessCount() > 0 && result.getErrorCount() == 0) {
            ra.addFlashAttribute("successMessage", FaMessages.bulkDeleted(result.getSuccessCount(), entityLabelFa));
            return;
        }
        if (result.getSuccessCount() > 0) {
            ra.addFlashAttribute("successMessage",
                    FaMessages.bulkDeletedPartial(result.getSuccessCount(), result.getErrorCount(), entityLabelFa));
        }
        if (result.getErrorCount() > 0) {
            String detail = result.getErrors().stream()
                    .limit(5)
                    .map(e -> "شناسه " + e.id() + ": " + e.message())
                    .reduce((a, b) -> a + " — " + b)
                    .orElse(FaMessages.genericError());
            if (result.getErrors().size() > 5) {
                detail += " — و " + (result.getErrors().size() - 5) + " مورد دیگر";
            }
            ra.addFlashAttribute("errorMessage", detail);
        }
        if (result.getSuccessCount() == 0 && result.getErrorCount() == 0) {
            ra.addFlashAttribute("errorMessage", "موردی برای حذف انتخاب نشده است.");
        }
    }

    public static String listRedirect(String basePath, String q, int page) {
        String redirect = "redirect:" + basePath;
        String sep = "?";
        if (q != null && !q.isBlank()) {
            redirect += sep + "q=" + q;
            sep = "&";
        }
        if (page > 0) {
            redirect += sep + "page=" + page;
        }
        return redirect;
    }
}
