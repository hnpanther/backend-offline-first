package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.LookupColumn;
import com.hnp.backendofflinefirst.domain.PersianSearchNormalizer;
import com.hnp.backendofflinefirst.entity.LookupItem;
import com.hnp.backendofflinefirst.entity.LookupList;
import com.hnp.backendofflinefirst.repository.LookupItemRepository;
import com.hnp.backendofflinefirst.repository.LookupListRepository;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reference data — lookup lists, their declared columns, and their items (V22, roadmap §31
 * Phase 2). Every rule lives here; the web controller only binds and redirects, and the Excel
 * import ({@link LookupImportService}) validates every row through the same static normalisers
 * below, so a value the form refuses the file refuses too.
 *
 * <h2>The rules, each refused with a sentence</h2>
 * <ul>
 *   <li><b>Codes are identities.</b> A list's code and an item's code are fixed once created; an
 *       item's code is unique in its list case-insensitively, has its Persian and Arabic digits
 *       made Latin (so «۱۲۳۴» and «1234» are one person, not two), and may hold no space and no
 *       invisible character — the same rule makes «A12» and «A 12» impossible as two codes.</li>
 *   <li><b>«Delete» is deactivation.</b> Deactivating stamps {@code deactivated_at}; a hard
 *       delete is refused while anything names the item. Today that is a child item in another
 *       list; when a form field can store an item (the next phase), the stored answers and the
 *       devices that fetched the list must join this guard — see {@link #deleteItem}.</li>
 *   <li><b>Anonymisation is the erasure.</b> The code stays, so a record that stored it still
 *       resolves to «an entry existed»; the label and every declared value are replaced, and the
 *       item can never be edited or reactivated again.</li>
 *   <li><b>A parent lives in another list</b>, and lists never form a cycle — so an item can never
 *       be its own ancestor and no item-level cycle check exists anywhere.</li>
 *   <li><b>Every change moves the list's revision</b>, through one atomic SQL increment
 *       ({@link LookupListRepository#bumpRevision}): a device decides whether its copy is behind by
 *       comparing that one number.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LookupListService {

    /** A list code: the same alphabet as a log-sheet category's, upper-cased. */
    private static final Pattern LIST_CODE = Pattern.compile("[A-Za-z0-9_-]{1,50}");
    /** A column key: what an import header and an attribute are named by. */
    private static final Pattern COLUMN_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,49}");
    /** An item code: anything visible, nothing that spaces or hides. */
    private static final Pattern ITEM_CODE = Pattern.compile("(?U)[^\\s\\p{Cc}\\p{Cf}]{1,100}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");

    public static final int MAX_NAME = 255;
    public static final int MAX_DESCRIPTION = 1000;
    public static final int MAX_COLUMNS = 30;
    public static final int MAX_COLUMN_LABEL = 100;
    public static final int MAX_ITEM_CODE = 100;
    public static final int MAX_ITEM_LABEL = 255;
    public static final int MAX_ATTRIBUTE_VALUE = 500;

    /** What an anonymised item is called from then on. */
    public static final String ANONYMISED_LABEL = "ناشناس‌شده";

    /**
     * Headers an import file already uses for the item's own fields. A column may not take one of
     * these keys, or a file could not say which of the two a header meant.
     */
    public static final Set<String> RESERVED_COLUMN_KEYS =
            Set.of("code", "label", "parentcode", "sortorder", "active");

    private final LookupListRepository listRepository;
    private final LookupItemRepository itemRepository;

    // ---------------------------------------------------------------------------------------
    // Lists
    // ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<LookupList> page(String q, Pageable pageable) {
        return WebListSupport.pagedList(q, pageable, listRepository::findAll, listRepository::search);
    }

    @Transactional(readOnly = true)
    public List<LookupList> findAllForPicker() {
        return listRepository.findAllForPicker();
    }

    @Transactional(readOnly = true)
    public LookupList requireList(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Lookup list not found.");
        }
        return listRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lookup list not found."));
    }

    @Transactional(readOnly = true)
    public Map<Long, LookupList> listsByIds(Collection<Long> ids) {
        Map<Long, LookupList> out = new LinkedHashMap<>();
        List<Long> wanted = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (!wanted.isEmpty()) {
            for (LookupList l : listRepository.findAllById(wanted)) {
                out.put(l.getId(), l);
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public long itemCount(Long listId) {
        return itemRepository.countByListId(listId);
    }

    @Transactional(readOnly = true)
    public long activeItemCount(Long listId) {
        return itemRepository.countByListIdAndActiveTrue(listId);
    }

    /** {@code listId → [all, active]}; a list with no item is absent. */
    @Transactional(readOnly = true)
    public Map<Long, long[]> itemCounts(Collection<Long> listIds) {
        Map<Long, long[]> out = new LinkedHashMap<>();
        List<Long> wanted = listIds == null ? List.of() : listIds.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return out;
        }
        for (Object[] row : itemRepository.countsByListIds(wanted)) {
            out.put((Long) row[0], new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        return out;
    }

    @Transactional
    public LookupList createList(String code, String name, String description, Long parentListId) {
        String c = normaliseListCode(code);
        String n = normaliseListName(name);
        String d = normaliseDescription(description);
        if (listRepository.findByCodeIgnoreCase(c).isPresent()) {
            throw new IllegalArgumentException("Duplicate lookup list code: " + c);
        }
        if (parentListId != null) {
            requireList(parentListId);
        }
        long now = System.currentTimeMillis();
        LookupList list = new LookupList();
        list.setCode(c);
        list.setName(n);
        list.setDescription(d);
        list.setParentListId(parentListId);
        list.setColumns(new ArrayList<>());
        list.setActive(true);
        list.setCreatedAt(now);
        list.setUpdatedAt(now);
        LookupList saved = listRepository.save(list);
        log.info("Lookup list {} created: {}", saved.getId(), saved.getCode());
        return saved;
    }

    /**
     * Renames, re-describes, (de)activates or re-parents a list. The code cannot change; a form
     * that posts a different one is refused rather than ignored.
     *
     * <p>The parent list may change only while no item names a parent — otherwise every such item
     * would point into a list that is no longer its parent list.
     *
     * @param active the new state, or null to keep the stored one
     */
    @Transactional
    public LookupList updateList(Long id, String code, String name, String description,
                                 Long parentListId, Boolean active) {
        LookupList list = requireList(id);
        if (code != null && !code.isBlank() && !code.trim().equalsIgnoreCase(list.getCode())) {
            throw new IllegalArgumentException("A lookup list's code cannot be changed.");
        }
        // Everything validated before the managed entity is touched (AGENTS.md §3).
        String n = normaliseListName(name);
        String d = normaliseDescription(description);
        if (!Objects.equals(parentListId, list.getParentListId())) {
            assertAssignableParent(list.getId(), parentListId);
            if (itemRepository.existsByListIdAndParentItemIdIsNotNull(list.getId())) {
                throw new IllegalStateException("The parent list cannot change while items name a parent.");
            }
        }
        long now = System.currentTimeMillis();
        list.setName(n);
        list.setDescription(d);
        list.setParentListId(parentListId);
        if (active != null) {
            list.setActive(active);
        }
        list.setUpdatedAt(now);
        listRepository.save(list);
        listRepository.bumpRevision(list.getId(), now);
        return list;
    }

    /**
     * Only an empty list goes, and only when no other list names it as its parent. A list with
     * items is deactivated instead — deleting it would delete every person in it, and the
     * {@code {code, label}} a record stored would then name a list that never existed.
     */
    @Transactional
    public void deleteList(Long id) {
        LookupList list = requireList(id);
        // Every referencing column, before anything is written (AGENTS.md #167). Import history
        // cascades on purpose — see V22.
        if (itemRepository.existsByListId(id)) {
            throw new IllegalStateException("This lookup list still has items.");
        }
        if (listRepository.existsByParentListId(id)) {
            throw new IllegalStateException("This lookup list is the parent of another list.");
        }
        listRepository.delete(list);
        log.info("Lookup list {} deleted: {}", id, list.getCode());
    }

    private void assertAssignableParent(Long listId, Long parentListId) {
        if (parentListId == null) {
            return;
        }
        if (parentListId.equals(listId)) {
            throw new IllegalArgumentException("A lookup list cannot be its own parent.");
        }
        // Walk up from the candidate; reaching this list means the new link closes a loop.
        Set<Long> seen = new HashSet<>();
        Long cursor = parentListId;
        while (cursor != null) {
            if (cursor.equals(listId) || !seen.add(cursor)) {
                throw new IllegalArgumentException("This parent list would make a cycle of lookup lists.");
            }
            cursor = requireList(cursor).getParentListId();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Columns
    // ---------------------------------------------------------------------------------------

    @Transactional
    public LookupList addColumn(Long listId, String key, String label,
                                boolean searchable, boolean display, boolean offline) {
        LookupList list = requireList(listId);
        String k = normaliseColumnKey(key);
        String l = normaliseColumnLabel(label);
        List<LookupColumn> columns = new ArrayList<>(list.getColumns() == null ? List.of() : list.getColumns());
        if (columns.size() >= MAX_COLUMNS) {
            throw new IllegalArgumentException("A lookup list may declare at most " + MAX_COLUMNS + " columns.");
        }
        for (LookupColumn existing : columns) {
            if (existing.getKey().equalsIgnoreCase(k)) {
                throw new IllegalArgumentException("Duplicate lookup column key: " + k);
            }
        }
        columns.add(LookupColumn.of(k, l, searchable, display, offline));
        long now = System.currentTimeMillis();
        list.setColumns(columns);
        list.setUpdatedAt(now);
        listRepository.save(list);
        listRepository.bumpRevision(list.getId(), now);
        log.info("Lookup list {} column added: {}", listId, k);
        return list;
    }

    /**
     * Relabels a column or changes its three flags. The key is how the column is found and is
     * never changed. A change of {@code searchable} rebuilds every item's search text, because the
     * value is in it or out of it.
     */
    @Transactional
    public LookupList updateColumn(Long listId, String key, String label,
                                   boolean searchable, boolean display, boolean offline) {
        LookupList list = requireList(listId);
        String l = normaliseColumnLabel(label);
        List<LookupColumn> columns = new ArrayList<>();
        boolean found = false;
        boolean searchChanged = false;
        for (LookupColumn c : list.getColumns() == null ? List.<LookupColumn>of() : list.getColumns()) {
            if (key != null && c.getKey().equalsIgnoreCase(key.trim())) {
                found = true;
                searchChanged = c.isSearchable() != searchable;
                columns.add(LookupColumn.of(c.getKey(), l, searchable, display, offline));
            } else {
                columns.add(c);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Lookup column not found.");
        }
        long now = System.currentTimeMillis();
        list.setColumns(columns);
        list.setUpdatedAt(now);
        listRepository.save(list);
        if (searchChanged) {
            rebuildSearchText(list, now);
        }
        listRepository.bumpRevision(list.getId(), now);
        return list;
    }

    /**
     * Removes a column <b>and its values from every item</b>. Values are pruned rather than kept
     * hidden: a column removed because it should not have held personal data must not go on
     * holding it where nobody can see. The page states how many items hold a value before the
     * administrator confirms.
     */
    @Transactional
    public LookupList removeColumn(Long listId, String key) {
        LookupList list = requireList(listId);
        String target = key == null ? "" : key.trim();
        List<LookupColumn> columns = new ArrayList<>();
        String removedKey = null;
        for (LookupColumn c : list.getColumns() == null ? List.<LookupColumn>of() : list.getColumns()) {
            if (c.getKey().equalsIgnoreCase(target)) {
                removedKey = c.getKey();
            } else {
                columns.add(c);
            }
        }
        if (removedKey == null) {
            throw new IllegalArgumentException("Lookup column not found.");
        }
        long now = System.currentTimeMillis();
        list.setColumns(columns);
        list.setUpdatedAt(now);
        listRepository.save(list);
        // Item by item, through save, so each change leaves an audit row (AGENTS.md #193) and
        // moves the item's updated_at — the device cursor must see that its values changed.
        for (LookupItem item : itemRepository.findAllByListId(list.getId())) {
            Map<String, String> attrs = item.getAttributes() == null ? Map.of() : item.getAttributes();
            if (!attrs.containsKey(removedKey)) {
                continue;
            }
            Map<String, String> next = new LinkedHashMap<>(attrs);
            next.remove(removedKey);
            item.setAttributes(next);
            item.setSearchText(searchText(list, item.getCode(), item.getLabel(), next));
            item.setUpdatedAt(now);
            itemRepository.save(item);
        }
        listRepository.bumpRevision(list.getId(), now);
        log.info("Lookup list {} column removed: {}", listId, removedKey);
        return list;
    }

    @Transactional(readOnly = true)
    public long countWithAttribute(Long listId, String key) {
        return itemRepository.countWithAttribute(listId, key);
    }

    private void rebuildSearchText(LookupList list, long now) {
        for (LookupItem item : itemRepository.findAllByListId(list.getId())) {
            String rebuilt = searchText(list, item.getCode(), item.getLabel(), item.getAttributes());
            if (!rebuilt.equals(item.getSearchText())) {
                item.setSearchText(rebuilt);
                itemRepository.save(item);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Items
    // ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<LookupItem> pageItems(Long listId, String q, Pageable pageable) {
        String term = PersianSearchNormalizer.normalize(q);
        return term.isEmpty()
                ? itemRepository.pageByList(listId, pageable)
                : itemRepository.searchByList(listId, term, pageable);
    }

    /** The item, refused unless it belongs to the list named beside it (AGENTS.md #121). */
    @Transactional(readOnly = true)
    public LookupItem requireItem(Long listId, Long itemId) {
        LookupItem item = itemId == null ? null : itemRepository.findById(itemId).orElse(null);
        if (item == null || !Objects.equals(item.getListId(), listId)) {
            throw new IllegalArgumentException("Lookup item not found.");
        }
        return item;
    }

    /** Parent items by id, for showing «parent code — label» beside a page of children. */
    @Transactional(readOnly = true)
    public Map<Long, LookupItem> itemsByIds(Collection<Long> ids) {
        Map<Long, LookupItem> out = new LinkedHashMap<>();
        List<Long> wanted = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (!wanted.isEmpty()) {
            for (LookupItem i : itemRepository.findAllByIdIn(wanted)) {
                out.put(i.getId(), i);
            }
        }
        return out;
    }

    @Transactional
    public LookupItem createItem(Long listId, String code, String label, Map<String, String> attributes,
                                 Integer sortOrder, String parentCode) {
        LookupList list = requireList(listId);
        String c = normaliseItemCode(code);
        String l = normaliseItemLabel(label);
        Map<String, String> attrs = normaliseAttributes(list, attributes);
        Long parentId = resolveParent(list, parentCode);
        if (itemRepository.findByListIdAndCodeIgnoreCase(list.getId(), c).isPresent()) {
            throw new IllegalArgumentException("Duplicate lookup item code: " + c);
        }
        long now = System.currentTimeMillis();
        LookupItem item = new LookupItem();
        item.setListId(list.getId());
        item.setCode(c);
        item.setLabel(l);
        item.setAttributes(attrs);
        item.setParentItemId(parentId);
        item.setSortOrder(sortOrder == null ? 0 : sortOrder);
        item.setActive(true);
        item.setSearchText(searchText(list, c, l, attrs));
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        LookupItem saved = itemRepository.save(item);
        listRepository.bumpRevision(list.getId(), now);
        return saved;
    }

    /**
     * Edits an item's label, values, order and parent. The code cannot change. Every declared
     * column is replaced by what the form posted — the form renders all of them, so a missing one
     * is an emptied one.
     */
    @Transactional
    public LookupItem updateItem(Long listId, Long itemId, String code, String label,
                                 Map<String, String> attributes, Integer sortOrder, String parentCode) {
        LookupList list = requireList(listId);
        LookupItem item = requireItem(listId, itemId);
        if (item.isAnonymised()) {
            throw new IllegalStateException("An anonymised lookup item cannot be changed.");
        }
        if (code != null && !code.isBlank()
                && !PersianSearchNormalizer.latinDigits(code.trim()).equalsIgnoreCase(item.getCode())) {
            throw new IllegalArgumentException("A lookup item's code cannot be changed.");
        }
        String l = normaliseItemLabel(label);
        Map<String, String> attrs = normaliseAttributes(list, attributes);
        Long parentId = resolveParent(list, parentCode);
        long now = System.currentTimeMillis();
        item.setLabel(l);
        item.setAttributes(attrs);
        item.setParentItemId(parentId);
        item.setSortOrder(sortOrder == null ? item.getSortOrder() : sortOrder);
        item.setSearchText(searchText(list, item.getCode(), l, attrs));
        item.setUpdatedAt(now);
        LookupItem saved = itemRepository.save(item);
        listRepository.bumpRevision(list.getId(), now);
        return saved;
    }

    /**
     * Deactivates or reactivates. Deactivating stamps the instant ({@code deactivated_at}) and
     * reactivating clears it; an anonymised item can never come back.
     */
    @Transactional
    public LookupItem setItemActive(Long listId, Long itemId, boolean active) {
        requireList(listId);
        LookupItem item = requireItem(listId, itemId);
        if (item.isAnonymised()) {
            throw new IllegalStateException("An anonymised lookup item cannot be changed.");
        }
        if (item.isActive() == active) {
            return item;
        }
        long now = System.currentTimeMillis();
        applyActive(item, active, now);
        item.setUpdatedAt(now);
        LookupItem saved = itemRepository.save(item);
        listRepository.bumpRevision(listId, now);
        return saved;
    }

    /**
     * A hard delete — for an entry added by mistake and caught at once. Refused while anything
     * names the item.
     *
     * <p><b>What names an item today</b> is only a child item in another list. When a form field
     * can store one (roadmap §31, the next phase), two more questions join this guard before it
     * may answer yes: does any stored answer hold this code, and has any device fetched a revision
     * of the list that contained it — a device may hold an offline draft naming any item it ever
     * received, which the server cannot see. Until then nothing outside this table can hold it.
     */
    @Transactional
    public void deleteItem(Long listId, Long itemId) {
        requireList(listId);
        LookupItem item = requireItem(listId, itemId);
        if (itemRepository.existsByParentItemId(item.getId())) {
            throw new IllegalStateException("This lookup item is the parent of other items.");
        }
        itemRepository.delete(item);
        listRepository.bumpRevision(listId, System.currentTimeMillis());
        log.info("Lookup item {} deleted from list {}: {}", itemId, listId, item.getCode());
    }

    /**
     * Erasure (roadmap §31, «Erasure is a different delete»). The code stays, so a record that
     * stored it still resolves to «an entry existed»; the label becomes {@link #ANONYMISED_LABEL},
     * every declared value is removed, and the item is deactivated for good.
     *
     * <p>What this does <b>not</b> reach: the audit trail keeps the before-values of every change
     * ever made to the row, and a record that stored this item keeps the label it stored. Both
     * are governed by their own retention, which is a decision for the security unit.
     */
    @Transactional
    public LookupItem anonymiseItem(Long listId, Long itemId) {
        LookupList list = requireList(listId);
        LookupItem item = requireItem(listId, itemId);
        if (item.isAnonymised()) {
            throw new IllegalStateException("This lookup item is already anonymised.");
        }
        long now = System.currentTimeMillis();
        item.setLabel(ANONYMISED_LABEL);
        item.setAttributes(new LinkedHashMap<>());
        if (item.isActive()) {
            applyActive(item, false, now);
        }
        item.setAnonymisedAt(now);
        item.setSearchText(searchText(list, item.getCode(), ANONYMISED_LABEL, Map.of()));
        item.setUpdatedAt(now);
        LookupItem saved = itemRepository.save(item);
        listRepository.bumpRevision(listId, now);
        log.info("Lookup item {} anonymised in list {}", itemId, listId);
        return saved;
    }

    static void applyActive(LookupItem item, boolean active, long now) {
        item.setActive(active);
        item.setDeactivatedAt(active ? null : now);
    }

    private Long resolveParent(LookupList list, String parentCode) {
        String pc = parentCode == null ? "" : PersianSearchNormalizer.latinDigits(parentCode.trim());
        if (pc.isEmpty()) {
            return null;
        }
        if (list.getParentListId() == null) {
            throw new IllegalArgumentException("This lookup list has no parent list.");
        }
        return itemRepository.findByListIdAndCodeIgnoreCase(list.getParentListId(), pc)
                .map(LookupItem::getId)
                .orElseThrow(() -> new IllegalArgumentException("Parent item not found in the parent list: " + pc));
    }

    // ---------------------------------------------------------------------------------------
    // The normalisers — shared with the Excel import, so the form and the file agree
    // ---------------------------------------------------------------------------------------

    public static String normaliseListCode(String code) {
        String c = code == null ? "" : code.trim();
        if (!LIST_CODE.matcher(c).matches()) {
            throw new IllegalArgumentException("Invalid lookup list code.");
        }
        return c.toUpperCase(Locale.ROOT);
    }

    static String normaliseListName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Lookup list name is required.");
        }
        if (n.length() > MAX_NAME) {
            throw new IllegalArgumentException("Lookup list name is too long.");
        }
        return n;
    }

    static String normaliseDescription(String description) {
        String d = description == null ? "" : description.strip();
        if (d.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("Lookup list description is too long.");
        }
        return d.isEmpty() ? null : d;
    }

    static String normaliseColumnKey(String key) {
        String k = key == null ? "" : key.trim();
        if (!COLUMN_KEY.matcher(k).matches()) {
            throw new IllegalArgumentException("Invalid lookup column key.");
        }
        if (RESERVED_COLUMN_KEYS.contains(k.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("This lookup column key is reserved.");
        }
        return k;
    }

    static String normaliseColumnLabel(String label) {
        String l = label == null ? "" : label.strip();
        if (l.isEmpty()) {
            throw new IllegalArgumentException("Lookup column label is required.");
        }
        if (l.length() > MAX_COLUMN_LABEL) {
            throw new IllegalArgumentException("Lookup column label is too long.");
        }
        return l;
    }

    /** Latin digits, trimmed, nothing that spaces or hides — see the class comment. */
    public static String normaliseItemCode(String code) {
        String c = code == null ? "" : PersianSearchNormalizer.latinDigits(code.strip());
        if (c.isEmpty()) {
            throw new IllegalArgumentException("Lookup item code is required.");
        }
        if (c.length() > MAX_ITEM_CODE) {
            throw new IllegalArgumentException("Lookup item code is too long.");
        }
        if (!ITEM_CODE.matcher(c).matches()) {
            throw new IllegalArgumentException("Invalid lookup item code.");
        }
        return c;
    }

    public static String normaliseItemLabel(String label) {
        String l = label == null ? "" : label.strip();
        if (l.isEmpty()) {
            throw new IllegalArgumentException("Lookup item label is required.");
        }
        if (l.length() > MAX_ITEM_LABEL) {
            throw new IllegalArgumentException("Lookup item label is too long.");
        }
        if (CONTROL.matcher(l).find()) {
            throw new IllegalArgumentException("Lookup item values may not contain control characters.");
        }
        return l;
    }

    /**
     * One declared column's value: stripped, at most {@link #MAX_ATTRIBUTE_VALUE} characters, no
     * control character. Empty means absent, so null is returned rather than "".
     */
    public static String normaliseAttributeValue(String key, String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            return null;
        }
        if (v.length() > MAX_ATTRIBUTE_VALUE) {
            throw new IllegalArgumentException("Lookup item value is too long: " + key);
        }
        if (CONTROL.matcher(v).find()) {
            throw new IllegalArgumentException("Lookup item values may not contain control characters.");
        }
        return v;
    }

    /**
     * Keeps only the list's declared columns, in the list's order, each through
     * {@link #normaliseAttributeValue}. A key the list does not declare is refused, not dropped:
     * a value somebody typed must not disappear without a word.
     */
    public static Map<String, String> normaliseAttributes(LookupList list, Map<String, String> attributes) {
        Map<String, String> in = attributes == null ? Map.of() : attributes;
        Map<String, String> byLowerKey = new LinkedHashMap<>();
        for (LookupColumn c : list.getColumns() == null ? List.<LookupColumn>of() : list.getColumns()) {
            byLowerKey.put(c.getKey().toLowerCase(Locale.ROOT), c.getKey());
        }
        for (String k : in.keySet()) {
            if (k == null || !byLowerKey.containsKey(k.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Unknown lookup column: " + k);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (LookupColumn c : list.getColumns() == null ? List.<LookupColumn>of() : list.getColumns()) {
            String raw = null;
            for (Map.Entry<String, String> e : in.entrySet()) {
                if (e.getKey().equalsIgnoreCase(c.getKey())) {
                    raw = e.getValue();
                }
            }
            String v = normaliseAttributeValue(c.getKey(), raw);
            if (v != null) {
                out.put(c.getKey(), v);
            }
        }
        return out;
    }

    /** The derived search column: code, label and every searchable value, normalised. */
    public static String searchText(LookupList list, String code, String label, Map<String, String> attributes) {
        StringBuilder sb = new StringBuilder();
        sb.append(code == null ? "" : code).append(' ').append(label == null ? "" : label);
        Map<String, String> attrs = attributes == null ? Map.of() : attributes;
        for (LookupColumn c : list.getColumns() == null ? List.<LookupColumn>of() : list.getColumns()) {
            if (c.isSearchable()) {
                String v = attrs.get(c.getKey());
                if (v != null) {
                    sb.append(' ').append(v);
                }
            }
        }
        return PersianSearchNormalizer.normalize(sb.toString());
    }
}
