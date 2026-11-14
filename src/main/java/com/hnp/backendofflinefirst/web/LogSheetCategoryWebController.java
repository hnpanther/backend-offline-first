package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.entity.LogSheetCategory;
import com.hnp.backendofflinefirst.service.LogSheetCategoryService;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The «دسته‌های لاگ‌شیت» page (V19, roadmap §28) — the thinnest master-data page in the panel:
 * code, name, active. Every rule lives in {@link LogSheetCategoryService}; a refusal is an
 * {@code IllegalArgumentException} / {@code IllegalStateException} that {@code WebExceptionHandler}
 * turns into a Persian flash message through {@code ErrorTranslator}.
 */
@Controller
@RequestMapping("/log-sheet-categories")
@RequiredArgsConstructor
public class LogSheetCategoryWebController {

    private final LogSheetCategoryService categoryService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/log-sheet-categories')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        var result = categoryService.page(q, WebListSupport.pageable(page, pageSize));
        model.addAttribute("activePage", "log-sheet-categories");
        model.addAttribute("categories", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        if (editId != null) {
            LogSheetCategory edit = categoryService.require(editId);
            model.addAttribute("editEntity", edit);
        }
        return "log-sheet-categories";
    }

    /** A new category is always active — retiring one is an edit, never a way to create it. */
    @PostMapping
    @PreAuthorize("hasAuthority('POST:/log-sheet-categories')")
    public String create(@RequestParam String code,
                         @RequestParam String name,
                         RedirectAttributes ra) {
        categoryService.create(code, name, true);
        ra.addFlashAttribute("successMessage", "دسته لاگ‌شیت ایجاد شد.");
        return "redirect:/log-sheet-categories";
    }

    /**
     * The «فعال» checkbox carries a marker of its own name ({@code _active}), never a hidden input
     * of the same name (AGENTS.md #171: Spring reads the FIRST of two same-named values, so a
     * hidden {@code false} before the checkbox always wins). Marker present and box unticked means
     * the administrator really did untick it; marker absent means a request that never showed the
     * control, which keeps the stored state rather than retiring the category by omission.
     */
    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/log-sheet-categories/{id}')")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) String code,
                         @RequestParam String name,
                         @RequestParam(name = "_active", required = false) String activeMarker,
                         @RequestParam(required = false) Boolean active,
                         RedirectAttributes ra) {
        Boolean resolved = activeMarker == null ? null : Boolean.TRUE.equals(active);
        categoryService.update(id, code, name, resolved);
        ra.addFlashAttribute("successMessage", "دسته لاگ‌شیت ذخیره شد.");
        return "redirect:/log-sheet-categories";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/log-sheet-categories/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        categoryService.delete(id);
        ra.addFlashAttribute("successMessage", "دسته لاگ‌شیت حذف شد.");
        return "redirect:/log-sheet-categories";
    }
}
