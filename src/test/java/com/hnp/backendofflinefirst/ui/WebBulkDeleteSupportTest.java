package com.hnp.backendofflinefirst.ui;

import com.hnp.backendofflinefirst.dto.BulkDeleteResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;

class WebBulkDeleteSupportTest {

    @Test
    void applyResultAllSuccessSetsSuccessFlashOnly() {
        BulkDeleteResult result = new BulkDeleteResult();
        result.addSuccess();
        result.addSuccess();
        RedirectAttributes ra = new RedirectAttributesModelMap();

        WebBulkDeleteSupport.applyResult(result, ra, "مکان");

        assertThat(ra.getFlashAttributes()).containsKey("successMessage");
        assertThat(ra.getFlashAttributes()).doesNotContainKey("errorMessage");
        assertThat(ra.getFlashAttributes().get("successMessage").toString()).contains("2");
    }

    @Test
    void applyResultPartialSuccessSetsBothMessages() {
        BulkDeleteResult result = new BulkDeleteResult();
        result.addSuccess();
        result.addError(2L, "این مکان دارای زیرمکان است.");
        RedirectAttributes ra = new RedirectAttributesModelMap();

        WebBulkDeleteSupport.applyResult(result, ra, "مکان");

        assertThat(ra.getFlashAttributes()).containsKeys("successMessage", "errorMessage");
        assertThat(ra.getFlashAttributes().get("errorMessage").toString()).contains("شناسه 2");
    }

    @Test
    void applyResultEmptySelectionSetsErrorOnly() {
        RedirectAttributes ra = new RedirectAttributesModelMap();

        WebBulkDeleteSupport.applyResult(new BulkDeleteResult(), ra, "دارایی");

        assertThat(ra.getFlashAttributes().get("errorMessage")).isEqualTo("موردی برای حذف انتخاب نشده است.");
        assertThat(ra.getFlashAttributes()).doesNotContainKey("successMessage");
    }

    @Test
    void applyResultTruncatesErrorDetailsAfterFiveItems() {
        BulkDeleteResult result = new BulkDeleteResult();
        for (long i = 1; i <= 7; i++) {
            result.addError(i, "خطا " + i);
        }
        RedirectAttributes ra = new RedirectAttributesModelMap();

        WebBulkDeleteSupport.applyResult(result, ra, "سیستم");

        String error = ra.getFlashAttributes().get("errorMessage").toString();
        assertThat(error).contains("شناسه 5");
        assertThat(error).doesNotContain("شناسه 6");
        assertThat(error).contains("2 مورد دیگر");
    }

    @Test
    void listRedirectPreservesSearchAndPage() {
        assertThat(WebBulkDeleteSupport.listRedirect("/locations", "pump", 2))
                .isEqualTo("redirect:/locations?q=pump&page=2");
    }

    @Test
    void listRedirectOmitsBlankQuery() {
        assertThat(WebBulkDeleteSupport.listRedirect("/asset-entries", "  ", 0))
                .isEqualTo("redirect:/asset-entries");
    }
}
