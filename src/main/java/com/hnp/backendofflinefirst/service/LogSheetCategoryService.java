package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetCategory;
import com.hnp.backendofflinefirst.repository.ApiKeyCategoryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetCategoryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Log-sheet categories (V19, roadmap §28): the master data, and the one place a category is
 * frozen onto a round.
 *
 * <h2>Three rules, each refused with a sentence</h2>
 *
 * <ul>
 *   <li><b>The code is fixed once created.</b> It is what a report and an integration group by;
 *       a code that can change silently splits one group into two.</li>
 *   <li><b>A category still named cannot be deleted</b> — by a template, by a round, or by an
 *       integration key limited to it (V20: a key whose last category vanished would silently
 *       widen to every category). All three are checked before anything is written (AGENTS.md #167: a guard that checks
 *       most of the referencing columns does irreversible work before the database says no).
 *       Deactivating is the answer instead: it keeps the history and leaves the pickers.</li>
 *   <li><b>An inactive category cannot be newly chosen</b>, but a template that already names
 *       one keeps it through an unrelated edit — refusing the rename of a template because its
 *       category was retired last month would be a rule about something the user did not touch.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogSheetCategoryService {

    /** Same alphabet as a field key, plus nothing a URL or a CSV would need escaping for. */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,50}");
    private static final int MAX_NAME = 255;

    private final LogSheetCategoryRepository categoryRepository;
    private final LogSheetTemplateRepository templateRepository;
    private final LogSheetRepository logSheetRepository;
    private final ApiKeyCategoryRepository apiKeyCategoryRepository;

    @Transactional(readOnly = true)
    public Page<LogSheetCategory> page(String q, Pageable pageable) {
        return WebListSupport.pagedList(q, pageable, categoryRepository::findAll, categoryRepository::search);
    }

    /** Every category, active first — for the list filter, where retired ones still group history. */
    @Transactional(readOnly = true)
    public List<LogSheetCategory> findAllForPicker() {
        return categoryRepository.findAllForPicker();
    }

    @Transactional(readOnly = true)
    public LogSheetCategory require(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Log sheet category not found."));
    }

    /** «code — name» by id, for one batched lookup on a page that shows many. */
    @Transactional(readOnly = true)
    public Map<Long, LogSheetCategory> byIds(Collection<Long> ids) {
        Map<Long, LogSheetCategory> out = new LinkedHashMap<>();
        List<Long> wanted = ids == null ? List.of()
                : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return out;
        }
        for (LogSheetCategory c : categoryRepository.findAllById(wanted)) {
            out.put(c.getId(), c);
        }
        return out;
    }

    @Transactional
    public LogSheetCategory create(String code, String name, Boolean active) {
        String c = normaliseCode(code);
        String n = normaliseName(name);
        if (categoryRepository.findByCodeIgnoreCase(c).isPresent()) {
            throw new IllegalArgumentException("Duplicate log sheet category code: " + c);
        }
        long now = System.currentTimeMillis();
        LogSheetCategory category = new LogSheetCategory();
        category.setCode(c);
        category.setName(n);
        category.setActive(active == null || active);
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        LogSheetCategory saved = categoryRepository.save(category);
        log.info("Log sheet category {} created: {}", saved.getId(), saved.getCode());
        return saved;
    }

    /**
     * Renames or (de)activates. The code cannot change — see the class comment; a form that
     * posts a different one is refused rather than ignored, so nobody leaves believing it did.
     *
     * @param active the new state, or null to keep the stored one
     */
    @Transactional
    public LogSheetCategory update(Long id, String code, String name, Boolean active) {
        LogSheetCategory category = require(id);
        if (code != null && !code.isBlank() && !code.trim().equalsIgnoreCase(category.getCode())) {
            throw new IllegalArgumentException("A log sheet category's code cannot be changed.");
        }
        // Validated before the managed entity is touched (AGENTS.md §3).
        String newName = normaliseName(name);
        category.setName(newName);
        if (active != null) {
            category.setActive(active);
        }
        category.setUpdatedAt(System.currentTimeMillis());
        return categoryRepository.save(category);
    }

    @Transactional
    public void delete(Long id) {
        LogSheetCategory category = require(id);
        // Every referencing column, before anything is written (AGENTS.md #167).
        if (templateRepository.existsByCategoryId(id)) {
            throw new IllegalStateException("This log sheet category is used by a template.");
        }
        if (logSheetRepository.existsByCategoryId(id)) {
            throw new IllegalStateException("This log sheet category is used by log sheets.");
        }
        if (apiKeyCategoryRepository.existsByCategoryId(id)) {
            throw new IllegalStateException("This log sheet category is used by an integration API key.");
        }
        categoryRepository.delete(category);
        log.info("Log sheet category {} deleted: {}", id, category.getCode());
    }

    /**
     * The category a template (or a custom-sheet form) may store.
     *
     * @param requested the id the form posted, or null for «none»
     * @param current   the id already stored, or null — an inactive category is allowed only
     *                  when it is this one, unchanged
     * @return the id to store, or null
     */
    @Transactional(readOnly = true)
    public Long requireAssignable(Long requested, Long current) {
        if (requested == null) {
            return null;
        }
        LogSheetCategory category = categoryRepository.findById(requested)
                .orElseThrow(() -> new IllegalArgumentException("Log sheet category not found."));
        if (!category.isActive() && !requested.equals(current)) {
            throw new IllegalArgumentException("This log sheet category is inactive.");
        }
        return category.getId();
    }

    /**
     * Freezes a category onto a round being raised: the id <b>and</b> today's name, together.
     *
     * <p>An inactive category is still frozen when the template names it: the template was
     * saved with it, and a scheduled round raised at 03:00 is not the moment to discover the
     * category was retired — the round would otherwise lose its grouping silently. Retiring a
     * category is a statement about the pickers, not about the templates already using it.
     *
     * <p>A category that no longer exists leaves the round uncategorised rather than failing the
     * generation; the foreign key makes that unreachable today, and a scheduler tick is the wrong
     * place to find out it became reachable.
     */
    @Transactional(readOnly = true)
    public void freezeOnto(LogSheet sheet, Long categoryId) {
        if (categoryId == null) {
            sheet.setCategoryId(null);
            sheet.setCategoryName(null);
            return;
        }
        categoryRepository.findById(categoryId).ifPresentOrElse(
                c -> {
                    sheet.setCategoryId(c.getId());
                    sheet.setCategoryName(c.getName());
                },
                () -> {
                    log.warn("Category {} named by a template no longer exists; round left uncategorised",
                            categoryId);
                    sheet.setCategoryId(null);
                    sheet.setCategoryName(null);
                });
    }

    private static String normaliseCode(String code) {
        String c = code == null ? "" : code.trim();
        if (!CODE.matcher(c).matches()) {
            throw new IllegalArgumentException("Invalid log sheet category code.");
        }
        return c.toUpperCase();
    }

    private static String normaliseName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Log sheet category name is required.");
        }
        if (n.length() > MAX_NAME) {
            throw new IllegalArgumentException("Log sheet category name is too long.");
        }
        return n;
    }
}
