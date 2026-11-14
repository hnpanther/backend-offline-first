package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.LookupColumn;
import com.hnp.backendofflinefirst.domain.LookupImportMode;
import com.hnp.backendofflinefirst.entity.LookupItem;
import com.hnp.backendofflinefirst.entity.LookupList;
import com.hnp.backendofflinefirst.entity.LookupListImport;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.LookupImportService;
import com.hnp.backendofflinefirst.service.LookupListService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * «اطلاعات مرجع» — reference-data lists, their columns and items, and the Excel import with its
 * preview (V22, roadmap §31 Phase 2). Every rule lives in {@link LookupListService} and
 * {@link LookupImportService}; a refusal is an {@code IllegalArgumentException} /
 * {@code IllegalStateException} that {@code WebExceptionHandler} turns into a Persian flash through
 * {@link ErrorTranslator} and a redirect back to the page the request came from.
 *
 * <p>Checkboxes carry a separately-named marker (AGENTS.md #171): a request without the marker
 * keeps the stored state rather than switching something off by omission.
 */
@Controller
@RequestMapping("/lookup-lists")
@RequiredArgsConstructor
public class LookupListWebController {

    /** A declared column's form input is named {@code attr_<key>}. */
    static final String ATTR_PREFIX = "attr_";

    private final LookupListService listService;
    private final LookupImportService importService;
    private final UserRepository userRepository;

    // ---------------------------------------------------------------------------------------
    // Lists
    // ---------------------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/lookup-lists')")
    public String index(@RequestParam(required = false) String q,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(required = false) Integer size,
                        Model model) {
        int pageSize = clampSize(size);
        var result = listService.page(q, WebListSupport.pageable(page, pageSize));
        model.addAttribute("activePage", "lookup-lists");
        model.addAttribute("lists", result.getContent());
        model.addAttribute("itemCounts", listService.itemCounts(result.getContent().stream().map(LookupList::getId).toList()));
        model.addAttribute("parentLists", listService.listsByIds(
                result.getContent().stream().map(LookupList::getParentListId).filter(Objects::nonNull).toList()));
        model.addAttribute("pickerLists", listService.findAllForPicker());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        return "lookup-lists";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/lookup-lists')")
    public String create(@RequestParam String code,
                         @RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) Long parentListId,
                         RedirectAttributes ra) {
        LookupList created = listService.createList(code, name, description, parentListId);
        ra.addFlashAttribute("successMessage", "فهرست مرجع ایجاد شد؛ اکنون ستون‌های آن را تعریف کنید.");
        return "redirect:/lookup-lists/" + created.getId();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('GET:/lookup-lists')")
    public String detail(@PathVariable Long id,
                         @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(required = false) Integer size,
                         @RequestParam(required = false) Long editId,
                         @RequestParam(required = false) String editColumn,
                         Model model) {
        LookupList list = listService.requireList(id);
        int pageSize = clampSize(size);
        var items = listService.pageItems(id, q, WebListSupport.unsortedPageable(page, pageSize));

        List<Long> parentIds = new ArrayList<>();
        for (LookupItem i : items.getContent()) {
            if (i.getParentItemId() != null) {
                parentIds.add(i.getParentItemId());
            }
        }
        LookupItem editItem = editId == null ? null : listService.requireItem(id, editId);
        if (editItem != null && editItem.getParentItemId() != null) {
            parentIds.add(editItem.getParentItemId());
        }
        Map<Long, LookupItem> parents = listService.itemsByIds(parentIds);

        // How many items hold a value for each column — said before a column is removed.
        Map<String, Long> columnValueCounts = new LinkedHashMap<>();
        for (LookupColumn c : columns(list)) {
            columnValueCounts.put(c.getKey(), listService.countWithAttribute(id, c.getKey()));
        }
        LookupColumn editCol = null;
        if (editColumn != null) {
            for (LookupColumn c : columns(list)) {
                if (c.getKey().equalsIgnoreCase(editColumn)) {
                    editCol = c;
                }
            }
        }

        List<LookupListImport> imports = importService.recentImports(id);
        Map<Long, String> actorNames = new HashMap<>();
        List<Long> actorIds = imports.stream().map(LookupListImport::getActorUserId).filter(Objects::nonNull).distinct().toList();
        if (!actorIds.isEmpty()) {
            for (User u : userRepository.findAllById(actorIds)) {
                actorNames.put(u.getId(), u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername());
            }
        }

        model.addAttribute("activePage", "lookup-lists");
        model.addAttribute("list", list);
        model.addAttribute("columns", columns(list));
        model.addAttribute("columnValueCounts", columnValueCounts);
        model.addAttribute("parentList", list.getParentListId() == null ? null
                : listService.requireList(list.getParentListId()));
        model.addAttribute("pickerLists", listService.findAllForPicker().stream()
                .filter(l -> !l.getId().equals(id)).toList());
        model.addAttribute("items", items.getContent());
        model.addAttribute("parents", parents);
        model.addAttribute("itemTotal", listService.itemCount(id));
        model.addAttribute("itemActive", listService.activeItemCount(id));
        model.addAttribute("editItem", editItem);
        model.addAttribute("editColumn", editCol);
        model.addAttribute("imports", imports);
        model.addAttribute("actorNames", actorNames);
        model.addAttribute("basePath", "/lookup-lists/" + id);
        WebListSupport.addPagination(model, items, q, page, pageSize);
        return "lookup-list";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}')")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) String code,
                         @RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) Long parentListId,
                         @RequestParam(name = "_active", required = false) String activeMarker,
                         @RequestParam(required = false) Boolean active,
                         RedirectAttributes ra) {
        Boolean resolved = activeMarker == null ? null : Boolean.TRUE.equals(active);
        listService.updateList(id, code, name, description, parentListId, resolved);
        ra.addFlashAttribute("successMessage", "فهرست مرجع ذخیره شد.");
        return "redirect:/lookup-lists/" + id;
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        listService.deleteList(id);
        ra.addFlashAttribute("successMessage", "فهرست مرجع حذف شد.");
        return "redirect:/lookup-lists";
    }

    // ---------------------------------------------------------------------------------------
    // Columns — a change to the list's shape, so the list's own edit authority
    // ---------------------------------------------------------------------------------------

    @PostMapping("/{id}/columns")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}')")
    public String addColumn(@PathVariable Long id,
                            @RequestParam String key,
                            @RequestParam String label,
                            @RequestParam(required = false) Boolean searchable,
                            @RequestParam(required = false) Boolean display,
                            @RequestParam(required = false) Boolean offline,
                            RedirectAttributes ra) {
        listService.addColumn(id, key, label, Boolean.TRUE.equals(searchable),
                Boolean.TRUE.equals(display), Boolean.TRUE.equals(offline));
        ra.addFlashAttribute("successMessage", "ستون اضافه شد.");
        return "redirect:/lookup-lists/" + id;
    }

    @PostMapping("/{id}/columns/{key}")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}')")
    public String updateColumn(@PathVariable Long id,
                               @PathVariable String key,
                               @RequestParam String label,
                               @RequestParam(required = false) Boolean searchable,
                               @RequestParam(required = false) Boolean display,
                               @RequestParam(required = false) Boolean offline,
                               RedirectAttributes ra) {
        listService.updateColumn(id, key, label, Boolean.TRUE.equals(searchable),
                Boolean.TRUE.equals(display), Boolean.TRUE.equals(offline));
        ra.addFlashAttribute("successMessage", "ستون ذخیره شد.");
        return "redirect:/lookup-lists/" + id;
    }

    @PostMapping("/{id}/columns/{key}/delete")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}')")
    public String removeColumn(@PathVariable Long id, @PathVariable String key, RedirectAttributes ra) {
        listService.removeColumn(id, key);
        ra.addFlashAttribute("successMessage", "ستون و مقدارهای آن حذف شد.");
        return "redirect:/lookup-lists/" + id;
    }

    // ---------------------------------------------------------------------------------------
    // Items
    // ---------------------------------------------------------------------------------------

    @PostMapping("/{id}/items")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}/items')")
    public String createItem(@PathVariable Long id,
                             @RequestParam Map<String, String> params,
                             RedirectAttributes ra) {
        LookupList list = listService.requireList(id);
        listService.createItem(id, params.get("code"), params.get("label"), attributesFrom(list, params),
                parseSort(params.get("sortOrder")), params.get("parentCode"));
        ra.addFlashAttribute("successMessage", "مورد اضافه شد.");
        return "redirect:/lookup-lists/" + id;
    }

    @PostMapping("/{id}/items/{itemId}")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}/items/{itemId}')")
    public String updateItem(@PathVariable Long id,
                             @PathVariable Long itemId,
                             @RequestParam Map<String, String> params,
                             RedirectAttributes ra) {
        LookupList list = listService.requireList(id);
        listService.updateItem(id, itemId, params.get("code"), params.get("label"), attributesFrom(list, params),
                parseSort(params.get("sortOrder")), params.get("parentCode"));
        ra.addFlashAttribute("successMessage", "مورد ذخیره شد.");
        return "redirect:/lookup-lists/" + id;
    }

    /** Deactivate or reactivate — an edit of the item, so the item's edit authority. */
    @PostMapping("/{id}/items/{itemId}/state")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}/items/{itemId}')")
    public String setItemState(@PathVariable Long id,
                               @PathVariable Long itemId,
                               @RequestParam boolean active,
                               RedirectAttributes ra) {
        listService.setItemActive(id, itemId, active);
        ra.addFlashAttribute("successMessage", active ? "مورد دوباره فعال شد." : "مورد غیرفعال شد.");
        return "redirect:/lookup-lists/" + id;
    }

    @PostMapping("/{id}/items/{itemId}/delete")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}/items/{itemId}/delete')")
    public String deleteItem(@PathVariable Long id, @PathVariable Long itemId, RedirectAttributes ra) {
        listService.deleteItem(id, itemId);
        ra.addFlashAttribute("successMessage", "مورد حذف شد.");
        return "redirect:/lookup-lists/" + id;
    }

    @PostMapping("/{id}/items/{itemId}/anonymise")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}/items/{itemId}/delete')")
    public String anonymiseItem(@PathVariable Long id, @PathVariable Long itemId, RedirectAttributes ra) {
        listService.anonymiseItem(id, itemId);
        ra.addFlashAttribute("successMessage", "اطلاعات این مورد ناشناس شد؛ کد آن برای سوابق باقی ماند.");
        return "redirect:/lookup-lists/" + id;
    }

    // ---------------------------------------------------------------------------------------
    // Excel — template, export, and the import with its preview
    // ---------------------------------------------------------------------------------------

    @GetMapping("/{id}/import-template")
    @PreAuthorize("hasAuthority('GET:/lookup-lists')")
    public void template(@PathVariable Long id, HttpServletResponse response) throws IOException {
        importService.writeTemplate(listService.requireList(id), response);
    }

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAuthority('GET:/lookup-lists')")
    public void export(@PathVariable Long id, HttpServletResponse response) throws IOException {
        importService.writeExport(listService.requireList(id), response);
    }

    @PostMapping("/{id}/import")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}/import')")
    public String upload(@PathVariable Long id,
                         @RequestParam(required = false) MultipartFile file,
                         @RequestParam(required = false) String mode) throws IOException {
        listService.requireList(id);
        LookupImportMode m = LookupImportMode.parse(mode);
        String token = importService.store(id, file);
        return "redirect:/lookup-lists/" + id + "/import/" + token + "?mode=" + m.name();
    }

    /**
     * The preview. A file that cannot be read at all (not a workbook, an unknown header) is
     * discarded on the spot and the administrator is sent back to the list with the reason —
     * there is nothing to preview and nothing worth keeping.
     */
    @GetMapping("/{id}/import/{token}")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}/import')")
    public String preview(@PathVariable Long id,
                          @PathVariable String token,
                          @RequestParam(required = false) String mode,
                          Model model,
                          RedirectAttributes ra) {
        LookupList list = listService.requireList(id);
        LookupImportMode m = LookupImportMode.parse(mode);
        LookupImportService.Preview preview;
        try {
            preview = importService.preview(id, token, m);
        } catch (IllegalArgumentException e) {
            importService.discard(id, token);
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
            return "redirect:/lookup-lists/" + id;
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        for (LookupImportService.RowError e : preview.errors()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("row", e.row());
            row.put("message", ErrorTranslator.toFa(e.message()));
            errors.add(row);
        }
        model.addAttribute("activePage", "lookup-lists");
        model.addAttribute("list", list);
        model.addAttribute("preview", preview);
        model.addAttribute("rowErrors", errors);
        model.addAttribute("detailLimit", LookupImportService.PREVIEW_DETAIL_LIMIT);
        return "lookup-list-import";
    }

    @PostMapping("/{id}/import/{token}/apply")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}/import')")
    public String apply(@PathVariable Long id,
                        @PathVariable String token,
                        @RequestParam String mode,
                        @RequestParam String sha256,
                        @RequestParam int added,
                        @RequestParam int updated,
                        @RequestParam int unchanged,
                        @RequestParam int deactivated,
                        @RequestParam int reactivated,
                        @RequestParam(required = false) Integer confirmDeactivations,
                        RedirectAttributes ra) {
        LookupListImport done = importService.apply(id, token, LookupImportMode.parse(mode), sha256,
                new LookupImportService.Counts(added, updated, unchanged, deactivated, reactivated),
                confirmDeactivations, SecurityUtils.currentUserId());
        ra.addFlashAttribute("successMessage", "ایمپورت اعمال شد: " + done.getAdded() + " افزودن، "
                + done.getUpdated() + " ویرایش، " + done.getReactivated() + " فعال‌سازی دوباره، "
                + done.getDeactivated() + " غیرفعال.");
        return "redirect:/lookup-lists/" + id;
    }

    @PostMapping("/{id}/import/{token}/discard")
    @PreAuthorize("hasAuthority('POST:/lookup-lists/{id}/import')")
    public String discard(@PathVariable Long id, @PathVariable String token, RedirectAttributes ra) {
        importService.discard(id, token);
        ra.addFlashAttribute("successMessage", "پیش‌نمایش کنار گذاشته شد؛ چیزی تغییر نکرد.");
        return "redirect:/lookup-lists/" + id;
    }

    // ---------------------------------------------------------------------------------------

    /**
     * The declared columns' values from the form, by key. Only declared columns are read — every
     * one of them is rendered, so a missing parameter is an emptied input, and anything else the
     * request carries is not a column.
     */
    static Map<String, String> attributesFrom(LookupList list, Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>();
        for (LookupColumn c : columns(list)) {
            out.put(c.getKey(), params.get(ATTR_PREFIX + c.getKey()));
        }
        return out;
    }

    private static int clampSize(Integer size) {
        return size != null && size > 0 ? Math.min(size, WebListSupport.MAX_SIZE) : WebListSupport.DEFAULT_SIZE;
    }

    private static Integer parseSort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(com.hnp.backendofflinefirst.domain.PersianSearchNormalizer.latinDigits(value.strip()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Sort order must be a whole number: " + value.strip());
        }
    }

    private static List<LookupColumn> columns(LookupList list) {
        return list.getColumns() == null ? List.of() : list.getColumns();
    }
}
