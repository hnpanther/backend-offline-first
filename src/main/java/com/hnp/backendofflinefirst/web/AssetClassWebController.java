package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.FieldDataTypes;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.entity.AssetClass;
import java.util.Set;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.MasterDataUniquenessValidator;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/asset-classes")
@RequiredArgsConstructor
public class AssetClassWebController {

    private final AssetClassRepository assetClassRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final ExcelExportService excelExportService;
    private final MasterDataUniquenessValidator uniquenessValidator;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/asset-classes')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = WebListSupport.pagedList(q, pageable,
                assetClassRepository::findAll,
                assetClassRepository::search);
        model.addAttribute("activePage", "asset-classes");
        model.addAttribute("assetClasses", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        // Only top-level classes may be a parent — one level, enforced by
        // trg_asset_classes_single_level. Offering a sub-class here would produce a form whose
        // only outcome is an error message.
        List<AssetClass> parentCandidates = assetClassRepository.findByParentIdIsNullOrderByIdDesc();
        model.addAttribute("parentCandidates", parentCandidates);
        // The list's "parent" column resolves an id to a name without a query per row — and
        // without a second unbounded read, because a parent is by definition top-level and is
        // therefore already in the list above.
        model.addAttribute("assetClassLabelById", parentCandidates.stream()
                .collect(java.util.stream.Collectors.toMap(
                        AssetClass::getId, AssetClass::getName, (a, b) -> a)));
        if (editId != null) {
            assetClassRepository.findById(editId).ifPresent(e -> {
                model.addAttribute("editEntity", e);
                // A class that already has children cannot itself become one, so the picker is
                // disabled rather than offered and then refused.
                model.addAttribute("editHasChildren", assetClassRepository.existsByParentId(e.getId()));
            });
        }
        model.addAttribute("editHasChildren",
                model.getAttribute("editHasChildren") != null && (boolean) model.getAttribute("editHasChildren"));
        return "asset-classes";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/asset-classes')")
    public String create(@ModelAttribute AssetClass form, RedirectAttributes ra) {
        String name = form.getName() == null ? null : form.getName().trim();
        form.setName(name);
        uniquenessValidator.validateAssetClass(null, name);
        try {
            assertParentIsTopLevel(null, form.getParentId());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
            return "redirect:/asset-classes";
        }
        long now = System.currentTimeMillis();
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        assetClassRepository.save(form);
        ra.addFlashAttribute("successMessage", FaMessages.assetClassCreated());
        return "redirect:/asset-classes";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{id}')")
    public String update(@PathVariable Long id, @ModelAttribute AssetClass form, RedirectAttributes ra) {
        try {
            assertParentIsTopLevel(id, form.getParentId());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
            return "redirect:/asset-classes";
        }
        assetClassRepository.findById(id).ifPresent(e -> {
            String name = form.getName() == null ? null : form.getName().trim();
            uniquenessValidator.validateAssetClass(id, name);
            e.setName(name);
            e.setParentId(form.getParentId());
            e.setUpdatedAt(System.currentTimeMillis());
            assetClassRepository.save(e);
        });
        ra.addFlashAttribute("successMessage", FaMessages.assetClassUpdated());
        return "redirect:/asset-classes";
    }

    /**
     * The one-level rule, checked here so the operator gets a sentence rather than a 500.
     *
     * <p>{@code trg_asset_classes_single_level} enforces it in the database as well, and that
     * is the one that counts — the Excel import writes this table too. This check exists only
     * so the admin panel can say which class is the problem.
     */
    private void assertParentIsTopLevel(Long id, Long parentId) {
        if (parentId == null) {
            return;
        }
        if (id != null && parentId.equals(id)) {
            throw new IllegalArgumentException("A class cannot be its own parent.");
        }
        AssetClass parent = assetClassRepository.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("The selected parent class no longer exists."));
        if (parent.getParentId() != null) {
            throw new IllegalArgumentException(
                    "Asset class nesting is limited to one level: '" + parent.getName()
                            + "' is itself a sub-class.");
        }
        if (id != null && assetClassRepository.existsByParentId(id)) {
            throw new IllegalArgumentException(
                    "This class already has sub-classes and cannot itself become one.");
        }
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        assetClassRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", FaMessages.assetClassDeleted());
        return "redirect:/asset-classes";
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/asset-classes')")
    public void export(HttpServletResponse response) throws IOException {
        excelExportService.exportAssetClasses(response);
    }

    @GetMapping("/{classId}/fields")
    @PreAuthorize("hasAuthority('GET:/asset-classes/{classId}/fields')")
    public String fields(@PathVariable Long classId,
                         @RequestParam(required = false) Long editId,
                         @RequestParam(required = false) String inheritKey,
                         @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(required = false) Integer size,
                         Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = WebListSupport.pagedList(q, pageable,
                p -> fieldDefinitionRepository.findByClassId(classId, p),
                (term, p) -> fieldDefinitionRepository.searchByClassId(classId, term, p));
        model.addAttribute("activePage", "asset-classes");
        assetClassRepository.findById(classId).ifPresent(c -> model.addAttribute("assetClass", c));
        model.addAttribute("fieldDefs", result.getContent());
        // One list for both modals. They used to carry their own hardcoded options and drifted:
        // the edit modal was missing image/audio/video/location, so editing such a field found no
        // matching option, fell back to the first one, and silently retyped the field to number.
        model.addAttribute("dataTypeLabels", FieldDataTypes.labels());
        WebListSupport.addPagination(model, result, q, page, pageSize);

        // --- what a SUB-CLASS inherits -------------------------------------------------------
        //
        // Inheritance happens when a schema is RESOLVED, not in the table: a sub-class holds
        // only the fields it overrides or adds, and a brand-new one holds none. This page read
        // `findByClassId` alone, so it opened EMPTY for a sub-class that is in fact already
        // carrying every parameter of its parent — the log sheets were right and the admin
        // screen was lying.
        //
        // Shown read-only, and deliberately NOT copied into the table on creation: a copy would
        // sever the inheritance, and a parameter added to the parent afterwards would never
        // reach the sub-class. Overriding one is an explicit act, which the page offers per row.
        List<FieldDefinition> inherited = List.of();
        List<FieldDefinition> inheritedDisabled = List.of();
        Long parentId = assetClassRepository.findById(classId)
                .map(AssetClass::getParentId).orElse(null);
        if (parentId != null) {
            Set<String> ownKeys = fieldDefinitionRepository.findByClassId(classId).stream()
                    .filter(fd -> !fd.isDeleted() && fd.getKey() != null)
                    .map(fd -> fd.getKey().toLowerCase(java.util.Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            List<FieldDefinition> fromParent = fieldDefinitionRepository.findByClassId(parentId).stream()
                    .filter(fd -> fd.getKey() != null)
                    // A key the sub-class already defines is not inherited — its own row wins,
                    // and showing both would say the field exists twice. A sub-class row that is
                    // switched OFF is not in `ownKeys`, so the parent's row reappears here: that
                    // is correct, and it is what the administrator needs to see. Switching an
                    // override off means "stop overriding", not "suppress the parent's field".
                    .filter(fd -> !ownKeys.contains(fd.getKey().toLowerCase(java.util.Locale.ROOT)))
                    .sorted(java.util.Comparator
                            .comparing((FieldDefinition fd) -> fd.getOrder() != null ? fd.getOrder() : Integer.MAX_VALUE)
                            .thenComparing(FieldDefinition::getKey,
                                    java.util.Comparator.nullsLast(String::compareTo)))
                    .toList();
            inherited = fromParent.stream().filter(fd -> !fd.isDeleted()).toList();
            // Shown, greyed, with no «override» action: the parent switched this one off, so it
            // is not on this sub-class's forms either. Listing it is the only way the
            // administrator learns that from here rather than by opening the parent's page.
            inheritedDisabled = fromParent.stream().filter(FieldDefinition::isDeleted).toList();
            assetClassRepository.findById(parentId)
                    .ifPresent(parent -> model.addAttribute("parentClass", parent));
        }
        model.addAttribute("inheritedFields", inherited);
        model.addAttribute("inheritedDisabledFields", inheritedDisabled);

        // «بازنویسی» on an inherited row: open the create form already filled with the parent's
        // values, so overriding one parameter does not mean retyping its unit, type, order and
        // bands from memory — which is how a sub-class ends up subtly different by accident.
        if (inheritKey != null && !inheritKey.isBlank()) {
            inherited.stream()
                    .filter(fd -> inheritKey.equalsIgnoreCase(fd.getKey()))
                    .findFirst()
                    .ifPresent(fd -> {
                        model.addAttribute("prefillEntity", fd);
                        model.addAttribute("prefillSelectOptions", formatSelectOptions(fd.getValidation()));
                        model.addAttribute("prefillDataTypeLabels",
                                FieldDataTypes.labelsIncluding(fd.getDataType()));
                    });
        }
        if (editId != null) {
            fieldDefinitionRepository.findByIdAndClassId(editId, classId).ifPresent(e -> {
                model.addAttribute("editEntity", e);
                model.addAttribute("editSelectOptions", formatSelectOptions(e.getValidation()));
                // The edit dropdown carries the field's own type even when it is one this build
                // no longer offers, so reopening the field can never silently retype it.
                model.addAttribute("editDataTypeLabels", FieldDataTypes.labelsIncluding(e.getDataType()));
            });
        }
        return "field-definitions";
    }

    @GetMapping("/{classId}/fields/export")
    @PreAuthorize("hasAuthority('GET:/asset-classes/{classId}/fields')")
    public void exportFields(@PathVariable Long classId, HttpServletResponse response) throws IOException {
        excelExportService.exportFieldDefinitions(classId, response);
    }

    @PostMapping("/{classId}/fields")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{classId}/fields')")
    public String addField(@PathVariable Long classId,
                           @ModelAttribute FieldDefinition form,
                           @RequestParam(required = false) String selectOptions,
                           @RequestParam(required = false) Double warningMin,
                           @RequestParam(required = false) Double warningMax,
                           @RequestParam(required = false) Double dangerMin,
                           @RequestParam(required = false) Double dangerMax,
                           @RequestParam(required = false) String warningOptions,
                           @RequestParam(required = false) String dangerOptions,
                           RedirectAttributes ra) {
        long now = System.currentTimeMillis();
        form.setClassId(classId);
        String key = form.getKey() == null ? null : form.getKey().trim();
        form.setKey(key);
        if (!FieldDataTypes.isValid(form.getDataType())) {
            // A dropdown is not a boundary. Storing a type nothing can render would make the
            // field unanswerable on the tablet and unvalidatable here.
            ra.addFlashAttribute("errorMessage",
                    FaMessages.fieldDefinitionInvalidDataType(form.getDataType()));
            return "redirect:/asset-classes/" + classId + "/fields";
        }
        uniquenessValidator.validateFieldDefinition(null, classId, key);
        form.setVersion(1);
        form.setDeleted(false);
        form.setSynced(false);
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        form.setValidation(FieldValidationSupport.build(
                form.getDataType(), selectOptions, warningMin, warningMax, dangerMin, dangerMax,
                warningOptions, dangerOptions));
        fieldDefinitionRepository.save(form);
        ra.addFlashAttribute("successMessage", FaMessages.fieldDefinitionCreated());
        return "redirect:/asset-classes/" + classId + "/fields";
    }

    @PostMapping("/{classId}/fields/{fieldId}")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{classId}/fields/{fieldId}')")
    public String updateField(@PathVariable Long classId,
                              @PathVariable Long fieldId,
                              @ModelAttribute FieldDefinition form,
                              @RequestParam(required = false) String selectOptions,
                              @RequestParam(required = false) Double warningMin,
                              @RequestParam(required = false) Double warningMax,
                              @RequestParam(required = false) Double dangerMin,
                              @RequestParam(required = false) Double dangerMax,
                              @RequestParam(required = false) String warningOptions,
                              @RequestParam(required = false) String dangerOptions,
                              RedirectAttributes ra) {
        var field = fieldDefinitionRepository.findByIdAndClassId(fieldId, classId);
        if (field.isEmpty()) {
            ra.addFlashAttribute("errorMessage", FaMessages.fieldDefinitionNotInClass());
            return "redirect:/asset-classes/" + classId + "/fields";
        }
        FieldDefinition e = field.get();
        String key = form.getKey() == null ? null : form.getKey().trim();
        if (!FieldDataTypes.isValidFor(form.getDataType(), e.getDataType())) {
            // Refused before `e` is touched: it is attached for the whole request under Open
            // Session In View, so a half-applied edit would still be flushed. Keeping the type a
            // legacy field already has is allowed — see `labelsIncluding`.
            ra.addFlashAttribute("errorMessage",
                    FaMessages.fieldDefinitionInvalidDataType(form.getDataType()));
            return "redirect:/asset-classes/" + classId + "/fields";
        }
        uniquenessValidator.validateFieldDefinition(fieldId, classId, key);
        // Validate (may throw on min > max) before touching the managed entity — `e` is
        // attached for the whole request under Open Session In View, so mutating it first
        // would leave a partially-updated entity dirty in the persistence context if this
        // throws. Same precaution as the uniqueness check above.
        Map<String, Object> validation = FieldValidationSupport.build(
                form.getDataType(), selectOptions, warningMin, warningMax, dangerMin, dangerMax,
                warningOptions, dangerOptions);
        e.setKey(key);
        e.setLabel(form.getLabel());
        e.setDataType(form.getDataType());
        e.setUnit(form.getUnit());
        e.setRequired(form.isRequired());
        e.setOrder(form.getOrder());
        e.setValidation(validation);
        e.setVersion(e.getVersion() == null ? 1 : e.getVersion() + 1);
        e.setUpdatedAt(System.currentTimeMillis());
        fieldDefinitionRepository.save(e);
        ra.addFlashAttribute("successMessage", FaMessages.fieldDefinitionUpdated());
        return "redirect:/asset-classes/" + classId + "/fields";
    }

    /**
     * Switches a parameter off, or back on.
     *
     * <p><strong>Off is not gone, and that is the entire point.</strong> The row stays, with
     * its bands, its vocabulary and its id. A sheet raised while the parameter existed froze
     * its own copy of the schema and keeps rendering it; a reading already stored keeps the
     * definition that names it, so the reports can still label it, give it a unit and judge
     * its severity. What changes is only what happens <em>next</em>: the parameter is not
     * captured into the next sheet's snapshot, so it is not offered on a new round and never
     * reaches a tablet.
     *
     * <p><strong>A target state, not a toggle.</strong> Two administrators clicking at once
     * both get the state they asked for; a toggle would hand the second one the opposite of
     * what their screen showed. Idempotent for the same reason.
     *
     * <p>{@code version} is bumped and {@code synced} cleared exactly as an edit does — this
     * <em>is</em> an edit of the definition, and the sync bookkeeping must not be able to tell
     * the two apart.
     */
    @PostMapping("/{classId}/fields/{fieldId}/state")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{classId}/fields/{fieldId}/state')")
    public String setFieldState(@PathVariable Long classId,
                                @PathVariable Long fieldId,
                                @RequestParam boolean disabled,
                                RedirectAttributes ra) {
        var field = fieldDefinitionRepository.findByIdAndClassId(fieldId, classId);
        if (field.isEmpty()) {
            ra.addFlashAttribute("errorMessage", FaMessages.fieldDefinitionNotInClass());
            return "redirect:/asset-classes/" + classId + "/fields";
        }
        FieldDefinition e = field.get();
        if (e.isDeleted() != disabled) {
            e.setDeleted(disabled);
            e.setVersion(e.getVersion() == null ? 1 : e.getVersion() + 1);
            e.setSynced(false);
            e.setUpdatedAt(System.currentTimeMillis());
            fieldDefinitionRepository.save(e);
        }
        ra.addFlashAttribute("successMessage", disabled
                ? FaMessages.fieldDefinitionDisabled()
                : FaMessages.fieldDefinitionEnabled());
        return "redirect:/asset-classes/" + classId + "/fields";
    }

    /**
     * Removes a parameter outright — and only one that nothing refers to.
     *
     * <p>This used to be an unguarded {@code delete()}, and it is how three attachment
     * readings on the operational database came to belong to no definition: the values stay
     * in {@code form_data} under a key nothing can resolve, so the reports print a bare key,
     * no unit, and {@code OK} for whatever it was. Now anything that would be stranded — a
     * reading, a superseded reading, a band an administrator set on one machine — refuses the
     * delete and points at «disable», which keeps all of it and still stops the parameter
     * appearing on new rounds.
     *
     * <p>The guard asks about this class <strong>and its sub-classes</strong>: a parent's
     * parameter is inherited, so its readings are stored on entries carrying the sub-class's
     * id, and asking about the parent alone would answer "unreferenced" for a parameter with
     * a thousand readings.
     *
     * <p>The narrow case it still allows — a parameter created, never answered, and removed
     * again — is the one a tombstone would only clutter the list with.
     */
    @PostMapping("/{classId}/fields/{fieldId}/delete")
    @PreAuthorize("hasAuthority('POST:/asset-classes/{classId}/fields/{fieldId}/delete')")
    public String deleteField(@PathVariable Long classId,
                              @PathVariable Long fieldId,
                              RedirectAttributes ra) {
        var field = fieldDefinitionRepository.findByIdAndClassId(fieldId, classId);
        if (field.isEmpty()) {
            ra.addFlashAttribute("errorMessage", FaMessages.fieldDefinitionNotInClass());
            return "redirect:/asset-classes/" + classId + "/fields";
        }
        FieldDefinition e = field.get();
        if (e.getKey() != null && fieldDefinitionRepository.isFieldKeyReferenced(
                classAndSubClassIds(classId), e.getKey())) {
            ra.addFlashAttribute("errorMessage", FaMessages.fieldDefinitionInUseUseDisable());
            return "redirect:/asset-classes/" + classId + "/fields";
        }
        fieldDefinitionRepository.delete(e);
        ra.addFlashAttribute("successMessage", FaMessages.fieldDefinitionDeleted());
        return "redirect:/asset-classes/" + classId + "/fields";
    }

    /** This class plus every class that inherits from it. One level deep, so one query. */
    private Set<Long> classAndSubClassIds(Long classId) {
        Set<Long> ids = new java.util.LinkedHashSet<>();
        ids.add(classId);
        assetClassRepository.findByParentIdOrderByNameAsc(classId)
                .forEach(child -> ids.add(child.getId()));
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static String formatSelectOptions(Map<String, Object> validation) {
        if (validation == null || !validation.containsKey(FieldValidationSupport.KEY_OPTIONS)) return "";
        Object opts = validation.get(FieldValidationSupport.KEY_OPTIONS);
        if (opts instanceof List<?> list) {
            return list.stream().map(String::valueOf).reduce((a, b) -> a + "\n" + b).orElse("");
        }
        return "";
    }
}
