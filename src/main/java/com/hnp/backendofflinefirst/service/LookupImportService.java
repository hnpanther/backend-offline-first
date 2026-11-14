package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.config.ImportStorageProperties;
import com.hnp.backendofflinefirst.domain.LookupColumn;
import com.hnp.backendofflinefirst.domain.LookupImportMode;
import com.hnp.backendofflinefirst.domain.PersianSearchNormalizer;
import com.hnp.backendofflinefirst.entity.LookupItem;
import com.hnp.backendofflinefirst.entity.LookupList;
import com.hnp.backendofflinefirst.entity.LookupListImport;
import com.hnp.backendofflinefirst.repository.LookupItemRepository;
import com.hnp.backendofflinefirst.repository.LookupListImportRepository;
import com.hnp.backendofflinefirst.repository.LookupListRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Excel import of a lookup list — the riskiest writer of reference data (roadmap §31,
 * «The Excel import is the riskiest writer»), so it is built to be looked at before it acts.
 *
 * <h2>Three steps, and why the file is kept between them</h2>
 * <ol>
 *   <li><b>Upload</b> — the file is stored on the server under a random token
 *       ({@link #store}). Nothing is applied.</li>
 *   <li><b>Preview</b> — the stored file is read and compared against the list <i>as it is now</i>
 *       ({@link #preview}): how many items it adds, updates, leaves alone, deactivates and
 *       reactivates; every row error; every label it would change; every item it would retire.
 *       Recomputed on every view, so a preview left open is never stale by more than a reload.</li>
 *   <li><b>Apply</b> — the same analysis, run again inside one transaction holding the list row
 *       locked ({@link #apply}). It refuses if the file's hash is not the one previewed, if
 *       anything about the list changed since (the numbers differ), if any row has an error, or if
 *       the deactivations cross the confirmation threshold without their number being confirmed.
 *       Then it writes every change, moves the revision <b>once</b>, records the import, and
 *       deletes the stored file.</li>
 * </ol>
 * A browser cannot re-send a file from a preview page, which is why the server keeps it; a token,
 * not the file name, addresses it, and it lives in its own folder under the import storage path.
 *
 * <h2>The file's rules</h2>
 * <ul>
 *   <li><b>Headers, not positions.</b> Columns are found by their header — {@code code},
 *       {@code label}, {@code parentCode}, {@code sortOrder}, {@code active} and the list's
 *       column keys — case-insensitively. An unknown header refuses the file: a typo in a header
 *       would otherwise drop a whole column of values in silence (AGENTS.md #40 is the positional
 *       version of that failure).</li>
 *   <li><b>A column absent from the file is untouched; a column present with an empty cell is
 *       cleared.</b> So a file of codes and labels updates names and leaves every other value
 *       alone, and an export edited and re-imported says exactly what it shows.</li>
 *   <li><b>{@code active}</b>: absent or empty means «the mode decides» — a new item is active;
 *       in {@link LookupImportMode#FULL_REPLACE} an item the file names becomes active (the file
 *       <i>is</i> the list); in {@link LookupImportMode#ADD_UPDATE} an existing item keeps its
 *       state. Anything but a recognised yes/no is a row error, never a guess.</li>
 *   <li><b>All or nothing.</b> One row error and nothing is applied. In full-replace mode a row
 *       with an error would otherwise count as «absent» and retire the very item it tried to
 *       update.</li>
 *   <li><b>Formulas are refused</b>, and numbers are read as Excel displays them — a code typed
 *       into a number-formatted cell has already lost its leading zeros, which is why the template
 *       and the export mark every column as text.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LookupImportService {

    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String PREVIEW_DIR = "lookup-previews";
    /** A stored file nobody applied or discarded is removed after this long. */
    private static final long PREVIEW_TTL_MS = 24L * 60 * 60 * 1000;
    /** Rows of each detail table the preview page shows; the totals are always complete. */
    public static final int PREVIEW_DETAIL_LIMIT = 200;
    private static final int MAX_FILE_NAME = 255;

    private static final String H_CODE = "code";
    private static final String H_LABEL = "label";
    private static final String H_PARENT = "parentcode";
    private static final String H_SORT = "sortorder";
    private static final String H_ACTIVE = "active";

    private final LookupListRepository listRepository;
    private final LookupItemRepository itemRepository;
    private final LookupListImportRepository importRepository;
    private final ImportStorageProperties storageProperties;

    @Value("${app.import.max-rows:10000}")
    private int maxRows;

    /** Deactivating more than this share of the active items needs its number confirmed. */
    @Value("${app.lookup.import.confirm-deactivate-percent:10}")
    private int confirmDeactivatePercent;

    // ---------------------------------------------------------------------------------------
    // What a preview says
    // ---------------------------------------------------------------------------------------

    public record RowError(int row, String message) {}

    public record LabelChange(String code, String from, String to) {}

    public record CodeLabel(String code, String label) {}

    /** The numbers that must be identical at apply time for the apply to go ahead. */
    public record Counts(int added, int updated, int unchanged, int deactivated, int reactivated) {}

    public record Preview(Long listId, String token, String fileName, String sha256, LookupImportMode mode,
                          int rowsRead, Counts counts, long activeBefore, int thresholdPercent,
                          boolean confirmationRequired, List<RowError> errors, int errorCount,
                          List<LabelChange> labelChanges, int labelChangeCount,
                          List<CodeLabel> deactivations, List<CodeLabel> additions) {
        public boolean applicable() {
            return errorCount == 0;
        }

        public boolean changesNothing() {
            return counts.added() + counts.updated() + counts.deactivated() + counts.reactivated() == 0;
        }
    }

    /**
     * One existing item's next state. Held apart from the item itself so that a <b>preview</b>
     * never touches a managed entity: under Open Session In View a dirty entity left in the
     * persistence context is one unrelated write away from being flushed.
     *
     * @param nextActive the new state, or null when it does not change
     */
    private record PendingUpdate(LookupItem target, String label, Map<String, String> attributes,
                                 Long parentItemId, int sortOrder, Boolean nextActive, String searchText) {
        void applyTo(long now) {
            target.setLabel(label);
            target.setAttributes(attributes);
            target.setParentItemId(parentItemId);
            target.setSortOrder(sortOrder);
            if (nextActive != null) {
                LookupListService.applyActive(target, nextActive, now);
            }
            target.setSearchText(searchText);
            target.setUpdatedAt(now);
        }
    }

    /** Analysis = what the preview shows + the writes the apply would make. */
    private record Analysis(Preview preview, List<LookupItem> inserts, List<PendingUpdate> updates) {}

    // ---------------------------------------------------------------------------------------
    // Upload, preview, apply, discard
    // ---------------------------------------------------------------------------------------

    /**
     * Stores an uploaded file under a fresh token and returns the token. Nothing is read beyond
     * the size check; the preview reads it.
     */
    public String store(Long listId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose an Excel file to import.");
        }
        Path dir = previewDirectory();
        Files.createDirectories(dir);
        sweepExpired(dir);
        String token = UUID.randomUUID().toString();
        Path target = dir.resolve(listId + "-" + token + ".xlsx");
        try (var in = file.getInputStream()) {
            Files.copy(in, target);
        }
        String original = file.getOriginalFilename();
        Files.writeString(dir.resolve(listId + "-" + token + ".name"),
                original == null ? "" : truncate(original.strip(), MAX_FILE_NAME), StandardCharsets.UTF_8);
        log.info("Lookup import file stored for list {}: token={} size={}", listId, token, file.getSize());
        return token;
    }

    @Transactional(readOnly = true)
    public Preview preview(Long listId, String token, LookupImportMode mode) {
        LookupList list = listRepository.findById(listId)
                .orElseThrow(() -> new IllegalArgumentException("Lookup list not found."));
        byte[] bytes = readStored(listId, token);
        return analyse(list, token, storedName(listId, token), bytes, mode, System.currentTimeMillis()).preview();
    }

    /**
     * Applies a previewed file. Every refusal leaves the list exactly as it was.
     *
     * @param expectedSha256         the hash the preview showed
     * @param expected               the counts the preview showed
     * @param confirmedDeactivations the number the administrator confirmed, or null when the
     *                               preview did not ask for one
     */
    @Transactional
    public LookupListImport apply(Long listId, String token, LookupImportMode mode, String expectedSha256,
                                  Counts expected, Integer confirmedDeactivations, Long actorUserId) {
        LookupList list = listRepository.findForUpdate(listId)
                .orElseThrow(() -> new IllegalArgumentException("Lookup list not found."));
        byte[] bytes = readStored(listId, token);
        long now = System.currentTimeMillis();
        Analysis analysis = analyse(list, token, storedName(listId, token), bytes, mode, now);
        Preview p = analysis.preview();
        if (expectedSha256 == null || !expectedSha256.equalsIgnoreCase(p.sha256())) {
            throw new IllegalStateException("The import file is not the one that was previewed.");
        }
        if (!p.applicable()) {
            throw new IllegalStateException("The import file has errors; nothing was applied.");
        }
        if (!p.counts().equals(expected)) {
            throw new IllegalStateException("The lookup list changed since the preview. Preview the file again.");
        }
        if (p.confirmationRequired()
                && (confirmedDeactivations == null || confirmedDeactivations != p.counts().deactivated())) {
            throw new IllegalStateException("Confirm the number of items this import deactivates.");
        }
        if (p.changesNothing()) {
            discard(listId, token);
            throw new IllegalStateException("The import file changes nothing in this lookup list.");
        }

        // Inserts first, then updates — an update never depends on an insert in this list
        // (a parent lives in another list), so the order is only for readable logs.
        for (LookupItem item : analysis.inserts()) {
            itemRepository.save(item);
        }
        for (PendingUpdate update : analysis.updates()) {
            update.applyTo(now);
            itemRepository.save(update.target());
        }
        listRepository.bumpRevision(list.getId(), now);
        long revisionAfter = listRepository.currentRevision(list.getId());

        LookupListImport record = new LookupListImport();
        record.setListId(list.getId());
        record.setMode(mode);
        record.setFileName(p.fileName());
        record.setFileSha256(p.sha256());
        record.setRowsRead(p.rowsRead());
        record.setAdded(p.counts().added());
        record.setUpdated(p.counts().updated());
        record.setUnchanged(p.counts().unchanged());
        record.setDeactivated(p.counts().deactivated());
        record.setReactivated(p.counts().reactivated());
        record.setRevisionAfter(revisionAfter);
        record.setActorUserId(actorUserId);
        record.setCreatedAt(now);
        LookupListImport saved = importRepository.save(record);
        log.info("Lookup list {} import applied ({}): +{} ~{} -{} ↺{} ={} → revision {}", list.getId(), mode,
                p.counts().added(), p.counts().updated(), p.counts().deactivated(), p.counts().reactivated(),
                p.counts().unchanged(), revisionAfter);
        // The file goes only once the transaction has nothing left that could fail; a rollback
        // after this point (a commit failure) leaves the administrator a token that 404s, which
        // they answer by uploading again — never a half-applied list.
        discard(listId, token);
        return saved;
    }

    /** Deletes a stored file and its name; quiet when they are already gone. */
    public void discard(Long listId, String token) {
        if (!isToken(token)) {
            return;
        }
        Path dir = previewDirectory();
        deleteQuietly(dir.resolve(listId + "-" + token + ".xlsx"));
        deleteQuietly(dir.resolve(listId + "-" + token + ".name"));
    }

    @Transactional(readOnly = true)
    public List<LookupListImport> recentImports(Long listId) {
        return importRepository.findTop10ByListIdOrderByCreatedAtDescIdDesc(listId);
    }

    // ---------------------------------------------------------------------------------------
    // The analysis — one code path for preview and apply
    // ---------------------------------------------------------------------------------------

    private Analysis analyse(LookupList list, String token, String fileName, byte[] bytes,
                             LookupImportMode mode, long now) {
        String sha = sha256(bytes);
        List<RowError> errors = new ArrayList<>();
        List<ParsedRow> rows = new ArrayList<>();
        Header header;
        Workbook opened;
        try {
            opened = new XSSFWorkbook(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            // POI's own refusals (not a zip, not OOXML, an inflate ratio that looks like a zip
            // bomb) are IllegalArgumentExceptions with POI's English in them; the administrator
            // gets one sentence of ours instead.
            throw new IllegalArgumentException("The file is not a readable Excel workbook.");
        }
        try (Workbook wb = opened) {
            Sheet sheet = wb.getNumberOfSheets() == 0 ? null : wb.getSheetAt(0);
            if (sheet == null) {
                throw new IllegalArgumentException("The import file has no sheet.");
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            header = readHeader(list, sheet.getRow(sheet.getFirstRowNum()), formatter);
            int dataRows = Math.max(0, sheet.getLastRowNum() - sheet.getFirstRowNum());
            if (maxRows > 0 && dataRows > maxRows) {
                throw new IllegalArgumentException(
                        "Excel file has " + dataRows + " data rows; maximum allowed is " + maxRows + ".");
            }
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                ParsedRow parsed = readRow(list, header, row, r + 1, formatter, errors);
                if (parsed != null) {
                    rows.add(parsed);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("The file is not a readable Excel workbook.");
        }

        // Existing state, loaded once.
        Map<String, LookupItem> existingByCode = new LinkedHashMap<>();
        long activeBefore = 0;
        for (LookupItem item : itemRepository.findAllByListId(list.getId())) {
            existingByCode.put(item.getCode().toLowerCase(Locale.ROOT), item);
            if (item.isActive()) {
                activeBefore++;
            }
        }
        Map<String, Long> parentIdByCode = new HashMap<>();
        if (header.has(H_PARENT) && list.getParentListId() != null) {
            for (LookupItem parent : itemRepository.findAllByListId(list.getParentListId())) {
                parentIdByCode.put(parent.getCode().toLowerCase(Locale.ROOT), parent.getId());
            }
        }

        // Duplicate codes within the file — the later row is the error, naming the earlier one.
        Map<String, Integer> firstRowByCode = new HashMap<>();
        for (ParsedRow row : rows) {
            if (row.code == null) {
                continue;
            }
            String k = row.code.toLowerCase(Locale.ROOT);
            Integer first = firstRowByCode.putIfAbsent(k, row.rowNumber);
            if (first != null) {
                errors.add(new RowError(row.rowNumber, "Duplicate code in the file: " + row.code + " (row " + first + ")"));
                row.valid = false;
            }
        }

        int added = 0, updated = 0, unchanged = 0, deactivated = 0, reactivated = 0;
        List<LabelChange> labelChanges = new ArrayList<>();
        int labelChangeCount = 0;
        List<CodeLabel> deactivations = new ArrayList<>();
        List<CodeLabel> additions = new ArrayList<>();
        List<LookupItem> inserts = new ArrayList<>();
        List<PendingUpdate> updates = new ArrayList<>();
        java.util.Set<String> named = new java.util.HashSet<>();

        for (ParsedRow row : rows) {
            if (row.code != null) {
                named.add(row.code.toLowerCase(Locale.ROOT));
            }
            if (!row.valid) {
                continue;
            }
            Long parentId = null;
            if (header.has(H_PARENT) && row.parentCode != null) {
                parentId = parentIdByCode.get(row.parentCode.toLowerCase(Locale.ROOT));
                if (parentId == null) {
                    errors.add(new RowError(row.rowNumber, "Parent item not found in the parent list: " + row.parentCode));
                    continue;
                }
            }
            LookupItem existing = existingByCode.get(row.code.toLowerCase(Locale.ROOT));
            if (existing == null) {
                boolean active = row.active == null || row.active;
                Map<String, String> attrs = new LinkedHashMap<>();
                for (LookupColumn c : columns(list)) {
                    String v = row.attributes.get(c.getKey());
                    if (v != null) {
                        attrs.put(c.getKey(), v);
                    }
                }
                LookupItem item = new LookupItem();
                item.setListId(list.getId());
                item.setCode(row.code);
                item.setLabel(row.label);
                item.setAttributes(attrs);
                item.setParentItemId(parentId);
                item.setSortOrder(row.sortOrder == null ? 0 : row.sortOrder);
                LookupListService.applyActive(item, active, now);
                item.setSearchText(LookupListService.searchText(list, row.code, row.label, attrs));
                item.setCreatedAt(now);
                item.setUpdatedAt(now);
                inserts.add(item);
                added++;
                if (additions.size() < PREVIEW_DETAIL_LIMIT) {
                    additions.add(new CodeLabel(row.code, row.label));
                }
                continue;
            }
            if (existing.isAnonymised()) {
                errors.add(new RowError(row.rowNumber, "An anonymised lookup item cannot be imported: " + existing.getCode()));
                continue;
            }

            // The item's next state: what the file says where it has a column, the item's own
            // value where it has none.
            Map<String, String> attrs = new LinkedHashMap<>(existing.getAttributes() == null ? Map.of() : existing.getAttributes());
            for (LookupColumn c : columns(list)) {
                if (header.has(c.getKey().toLowerCase(Locale.ROOT))) {
                    String v = row.attributes.get(c.getKey());
                    if (v == null) {
                        attrs.remove(c.getKey());
                    } else {
                        attrs.put(c.getKey(), v);
                    }
                }
            }
            Long nextParent = header.has(H_PARENT) ? parentId : existing.getParentItemId();
            int nextSort = header.has(H_SORT) ? (row.sortOrder == null ? 0 : row.sortOrder) : existing.getSortOrder();
            boolean nextActive;
            if (row.active != null) {
                nextActive = row.active;
            } else if (mode == LookupImportMode.FULL_REPLACE) {
                nextActive = true;
            } else {
                nextActive = existing.isActive();
            }

            boolean labelChanged = !row.label.equals(existing.getLabel());
            boolean fieldsChanged = labelChanged
                    || !attrs.equals(existing.getAttributes() == null ? Map.of() : existing.getAttributes())
                    || !Objects.equals(nextParent, existing.getParentItemId())
                    || nextSort != existing.getSortOrder();
            boolean activeChanged = nextActive != existing.isActive();
            if (!fieldsChanged && !activeChanged) {
                unchanged++;
                continue;
            }
            if (labelChanged) {
                labelChangeCount++;
                if (labelChanges.size() < PREVIEW_DETAIL_LIMIT) {
                    labelChanges.add(new LabelChange(existing.getCode(), existing.getLabel(), row.label));
                }
            }
            if (activeChanged && nextActive) {
                reactivated++;
            } else if (activeChanged) {
                deactivated++;
                if (deactivations.size() < PREVIEW_DETAIL_LIMIT) {
                    deactivations.add(new CodeLabel(existing.getCode(), existing.getLabel()));
                }
            } else {
                updated++;
            }
            updates.add(new PendingUpdate(existing, row.label, attrs, nextParent, nextSort,
                    activeChanged ? nextActive : null,
                    LookupListService.searchText(list, existing.getCode(), row.label, attrs)));
        }

        if (mode == LookupImportMode.FULL_REPLACE) {
            for (Map.Entry<String, LookupItem> e : existingByCode.entrySet()) {
                LookupItem item = e.getValue();
                if (!item.isActive() || named.contains(e.getKey())) {
                    continue;
                }
                deactivated++;
                if (deactivations.size() < PREVIEW_DETAIL_LIMIT) {
                    deactivations.add(new CodeLabel(item.getCode(), item.getLabel()));
                }
                updates.add(new PendingUpdate(item, item.getLabel(),
                        item.getAttributes() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item.getAttributes()),
                        item.getParentItemId(), item.getSortOrder(), Boolean.FALSE, item.getSearchText()));
            }
        }

        errors.sort(java.util.Comparator.comparingInt(RowError::row));
        int errorCount = errors.size();
        List<RowError> shownErrors = errors.size() > PREVIEW_DETAIL_LIMIT
                ? new ArrayList<>(errors.subList(0, PREVIEW_DETAIL_LIMIT)) : errors;
        boolean confirmationRequired = deactivated > 0
                && (long) deactivated * 100 > activeBefore * (long) confirmDeactivatePercent;
        Counts counts = new Counts(added, updated, unchanged, deactivated, reactivated);
        Preview preview = new Preview(list.getId(), token, fileName, sha, mode, rows.size(), counts, activeBefore,
                confirmDeactivatePercent, confirmationRequired, shownErrors, errorCount,
                labelChanges, labelChangeCount, deactivations, additions);
        return new Analysis(preview, inserts, updates);
    }

    // ---------------------------------------------------------------------------------------
    // Reading the workbook
    // ---------------------------------------------------------------------------------------

    /** Column index → what it holds; built from the header row and refused when it is wrong. */
    private static final class Header {
        final Map<String, Integer> indexByKey = new LinkedHashMap<>();
        final Map<Integer, String> columnKeyByIndex = new HashMap<>();
        final List<Integer> blankIndexes = new ArrayList<>();
        int width;

        boolean has(String lowerKey) {
            return indexByKey.containsKey(lowerKey);
        }
    }

    private static final class ParsedRow {
        int rowNumber;
        String code;
        String label;
        Map<String, String> attributes = new HashMap<>();
        Integer sortOrder;
        String parentCode;
        Boolean active;
        boolean valid = true;
    }

    private Header readHeader(LookupList list, Row row, DataFormatter formatter) {
        if (row == null) {
            throw new IllegalArgumentException("The import file has no header row.");
        }
        Map<String, String> columnKeyByLower = new HashMap<>();
        for (LookupColumn c : columns(list)) {
            columnKeyByLower.put(c.getKey().toLowerCase(Locale.ROOT), c.getKey());
        }
        Header header = new Header();
        header.width = Math.max(0, row.getLastCellNum());
        for (int i = 0; i < header.width; i++) {
            String raw = cellText(row.getCell(i), formatter);
            if (raw == null || raw.isBlank()) {
                header.blankIndexes.add(i);
                continue;
            }
            String key = raw.strip().toLowerCase(Locale.ROOT);
            boolean own = key.equals(H_CODE) || key.equals(H_LABEL) || key.equals(H_PARENT)
                    || key.equals(H_SORT) || key.equals(H_ACTIVE);
            if (!own && !columnKeyByLower.containsKey(key)) {
                throw new IllegalArgumentException("Unknown column in the import file: " + raw.strip());
            }
            if (header.indexByKey.putIfAbsent(key, i) != null) {
                throw new IllegalArgumentException("A column appears twice in the import file: " + raw.strip());
            }
            if (!own) {
                header.columnKeyByIndex.put(i, columnKeyByLower.get(key));
            }
        }
        if (!header.has(H_CODE) || !header.has(H_LABEL)) {
            throw new IllegalArgumentException("The import file must have a «code» and a «label» column.");
        }
        if (header.has(H_PARENT) && list.getParentListId() == null) {
            throw new IllegalArgumentException("This lookup list has no parent list.");
        }
        return header;
    }

    private ParsedRow readRow(LookupList list, Header header, Row row, int rowNumber,
                              DataFormatter formatter, List<RowError> errors) {
        if (row == null) {
            return null;
        }
        int width = Math.max(header.width, row.getLastCellNum());
        boolean blank = true;
        for (int i = 0; i < width; i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && cell.getCellType() == CellType.FORMULA) {
                errors.add(new RowError(rowNumber, "Formulas are not accepted in an import file."));
                ParsedRow broken = new ParsedRow();
                broken.rowNumber = rowNumber;
                broken.valid = false;
                return broken;
            }
            String v = cellText(cell, formatter);
            if (v != null && !v.isBlank()) {
                blank = false;
                if (header.blankIndexes.contains(i) || i >= header.width) {
                    errors.add(new RowError(rowNumber, "A value sits under a column with no header."));
                    ParsedRow broken = new ParsedRow();
                    broken.rowNumber = rowNumber;
                    broken.valid = false;
                    return broken;
                }
            }
        }
        if (blank) {
            return null;
        }
        ParsedRow parsed = new ParsedRow();
        parsed.rowNumber = rowNumber;
        try {
            parsed.code = LookupListService.normaliseItemCode(text(row, header, H_CODE, formatter));
        } catch (IllegalArgumentException e) {
            errors.add(new RowError(rowNumber, e.getMessage()));
            parsed.valid = false;
        }
        try {
            parsed.label = LookupListService.normaliseItemLabel(text(row, header, H_LABEL, formatter));
        } catch (IllegalArgumentException e) {
            errors.add(new RowError(rowNumber, e.getMessage()));
            parsed.valid = false;
        }
        for (Map.Entry<Integer, String> col : header.columnKeyByIndex.entrySet()) {
            try {
                String v = LookupListService.normaliseAttributeValue(col.getValue(),
                        cellText(row.getCell(col.getKey()), formatter));
                if (v != null) {
                    parsed.attributes.put(col.getValue(), v);
                }
            } catch (IllegalArgumentException e) {
                errors.add(new RowError(rowNumber, e.getMessage()));
                parsed.valid = false;
            }
        }
        if (header.has(H_SORT)) {
            String s = text(row, header, H_SORT, formatter);
            if (s != null && !s.isBlank()) {
                try {
                    parsed.sortOrder = Integer.parseInt(PersianSearchNormalizer.latinDigits(s.strip()));
                } catch (NumberFormatException e) {
                    errors.add(new RowError(rowNumber, "Sort order must be a whole number: " + s.strip()));
                    parsed.valid = false;
                }
            }
        }
        if (header.has(H_PARENT)) {
            String pc = text(row, header, H_PARENT, formatter);
            parsed.parentCode = pc == null || pc.isBlank() ? null : PersianSearchNormalizer.latinDigits(pc.strip());
        }
        if (header.has(H_ACTIVE)) {
            String a = text(row, header, H_ACTIVE, formatter);
            if (a != null && !a.isBlank()) {
                Boolean b = parseActive(a);
                if (b == null) {
                    errors.add(new RowError(rowNumber, "Unrecognised value in «active»: " + a.strip()));
                    parsed.valid = false;
                } else {
                    parsed.active = b;
                }
            }
        }
        return parsed;
    }

    private static String text(Row row, Header header, String lowerKey, DataFormatter formatter) {
        Integer idx = header.indexByKey.get(lowerKey);
        return idx == null ? null : cellText(row.getCell(idx), formatter);
    }

    /**
     * A cell as Excel shows it. A number is formatted by its own display format, so a code kept
     * as {@code 00123} through a custom format survives; a code typed into a plain number cell has
     * already lost its zeros in Excel, and nothing here can bring them back.
     */
    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> numericText(cell, formatter);
            case BOOLEAN -> cell.getBooleanCellValue() ? "true" : "false";
            default -> null;
        };
    }

    /**
     * Excel's «General» format writes a whole number of twelve digits or more in scientific
     * notation, and {@link DataFormatter} copies it faithfully — «1.23457E+11» for a long code.
     * A whole number in General format is therefore written out in full; any other format is
     * honoured as displayed, which is what keeps a custom {@code 0000000000} format's zeros.
     */
    private static String numericText(Cell cell, DataFormatter formatter) {
        double v = cell.getNumericCellValue();
        String format = cell.getCellStyle() == null ? "General" : cell.getCellStyle().getDataFormatString();
        if ((format == null || "General".equalsIgnoreCase(format)) && v == Math.rint(v) && !Double.isInfinite(v)) {
            return new java.math.BigDecimal(v).toPlainString();
        }
        return formatter.formatCellValue(cell);
    }

    /** The recognised yes/no words; anything else is null, which the caller turns into an error. */
    static Boolean parseActive(String value) {
        String v = PersianSearchNormalizer.normalize(value);
        return switch (v) {
            case "true", "1", "yes", "بله", "فعال" -> Boolean.TRUE;
            case "false", "0", "no", "خیر", "غیرفعال" -> Boolean.FALSE;
            default -> null;
        };
    }

    // ---------------------------------------------------------------------------------------
    // Template and export
    // ---------------------------------------------------------------------------------------

    /** Headers in file order: code, label, parentCode (when the list has a parent), columns, sortOrder, active. */
    public List<String> headers(LookupList list) {
        List<String> h = new ArrayList<>();
        h.add("code");
        h.add("label");
        if (list.getParentListId() != null) {
            h.add("parentCode");
        }
        for (LookupColumn c : columns(list)) {
            h.add(c.getKey());
        }
        h.add("sortOrder");
        h.add("active");
        return h;
    }

    /** An empty file with the right headers, every column text-formatted, and a guide sheet. */
    public void writeTemplate(LookupList list, HttpServletResponse response) throws IOException {
        writeWorkbook(list, List.of(), Map.of(), "lookup-" + list.getCode().toLowerCase(Locale.ROOT) + "-template.xlsx",
                response);
    }

    /**
     * Every item except anonymised ones, in the import's own format — so an administrator can
     * export, edit and re-import. Anonymised items stay out: re-importing one would be refused,
     * and the file would then carry a row that can never be applied.
     */
    @Transactional(readOnly = true)
    public void writeExport(LookupList list, HttpServletResponse response) throws IOException {
        List<LookupItem> items = itemRepository.findAllByListId(list.getId()).stream()
                .filter(i -> !i.isAnonymised())
                .sorted(java.util.Comparator.comparing(LookupItem::isActive).reversed()
                        .thenComparingInt(LookupItem::getSortOrder)
                        .thenComparing(LookupItem::getLabel)
                        .thenComparing(LookupItem::getId))
                .toList();
        Map<Long, String> parentCodes = new HashMap<>();
        List<Long> parentIds = items.stream().map(LookupItem::getParentItemId).filter(Objects::nonNull).distinct().toList();
        if (!parentIds.isEmpty()) {
            for (LookupItem p : itemRepository.findAllByIdIn(parentIds)) {
                parentCodes.put(p.getId(), p.getCode());
            }
        }
        writeWorkbook(list, items, parentCodes, "lookup-" + list.getCode().toLowerCase(Locale.ROOT) + ".xlsx", response);
    }

    private void writeWorkbook(LookupList list, List<LookupItem> items, Map<Long, String> parentCodes,
                               String filename, HttpServletResponse response) throws IOException {
        List<String> headers = headers(list);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        try (Workbook wb = new XSSFWorkbook()) {
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));
            Sheet sheet = wb.createSheet("items");
            sheet.setRightToLeft(true);
            // Text format on whole columns, so a code or a national id typed into the template
            // later keeps its leading zeros.
            for (int i = 0; i < headers.size(); i++) {
                sheet.setDefaultColumnStyle(i, text);
                sheet.setColumnWidth(i, 20 * 256);
            }
            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = head.createCell(i);
                c.setCellStyle(text);
                c.setCellValue(headers.get(i));
            }
            int r = 1;
            for (LookupItem item : items) {
                Row row = sheet.createRow(r++);
                int c = 0;
                setText(row, c++, item.getCode(), text);
                setText(row, c++, item.getLabel(), text);
                if (list.getParentListId() != null) {
                    setText(row, c++, item.getParentItemId() == null ? "" : parentCodes.getOrDefault(item.getParentItemId(), ""), text);
                }
                for (LookupColumn col : columns(list)) {
                    setText(row, c++, item.getAttributes() == null ? "" : item.getAttributes().getOrDefault(col.getKey(), ""), text);
                }
                setText(row, c++, String.valueOf(item.getSortOrder()), text);
                setText(row, c, item.isActive() ? "فعال" : "غیرفعال", text);
            }
            writeGuide(wb, list);
            wb.write(response.getOutputStream());
        }
    }

    /** A second sheet saying what each header means — the first thing anyone asks of a template. */
    private void writeGuide(Workbook wb, LookupList list) {
        Sheet guide = wb.createSheet("راهنما");
        guide.setRightToLeft(true);
        guide.setColumnWidth(0, 18 * 256);
        guide.setColumnWidth(1, 90 * 256);
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[]{"ستون", "معنا"});
        lines.add(new String[]{"code", "کد یکتای هر مورد (الزامی). بعد از ثبت تغییر نمی‌کند و برای فرد دیگری استفاده نمی‌شود. ارقام فارسی به لاتین تبدیل می‌شوند؛ فاصله مجاز نیست."});
        lines.add(new String[]{"label", "عنوان نمایشی، مثلاً نام و نام خانوادگی (الزامی)."});
        if (list.getParentListId() != null) {
            lines.add(new String[]{"parentCode", "کد مورد والد در فهرست والد؛ خالی یعنی بدون والد."});
        }
        for (LookupColumn c : columns(list)) {
            lines.add(new String[]{c.getKey(), c.getLabel()});
        }
        lines.add(new String[]{"sortOrder", "ترتیب نمایش (عدد صحیح). خالی یعنی ۰."});
        lines.add(new String[]{"active", "«فعال» یا «غیرفعال» (یا true/false). خالی: مورد جدید فعال است؛ در حالت «جایگزینی کامل» هر موردی که در فایل هست فعال می‌شود."});
        lines.add(new String[]{"", ""});
        lines.add(new String[]{"قاعده", "ستونی که در فایل نیست دست نمی‌خورد؛ ستونی که هست و خانه‌اش خالی است، پاک می‌شود."});
        lines.add(new String[]{"قاعده", "اگر حتی یک ردیف خطا داشته باشد، هیچ تغییری اعمال نمی‌شود."});
        lines.add(new String[]{"قاعده", "در «جایگزینی کامل»، هر مورد فعالی که در فایل نیست غیرفعال می‌شود؛ پیش از اعمال، پیش‌نمایش تعداد آن را نشان می‌دهد."});
        for (int i = 0; i < lines.size(); i++) {
            Row row = guide.createRow(i);
            row.createCell(0).setCellValue(lines.get(i)[0]);
            row.createCell(1).setCellValue(lines.get(i)[1]);
        }
    }

    private static void setText(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellStyle(style);
        cell.setCellValue(value == null ? "" : value);
    }

    // ---------------------------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------------------------

    private Path previewDirectory() {
        return Path.of(storageProperties.getStoragePath()).toAbsolutePath().normalize().resolve(PREVIEW_DIR);
    }

    private byte[] readStored(Long listId, String token) {
        if (!isToken(token)) {
            throw new IllegalArgumentException("The import preview has expired. Upload the file again.");
        }
        Path file = previewDirectory().resolve(listId + "-" + token + ".xlsx");
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("The import preview has expired. Upload the file again.");
        }
    }

    private String storedName(Long listId, String token) {
        try {
            String n = Files.readString(previewDirectory().resolve(listId + "-" + token + ".name"), StandardCharsets.UTF_8);
            return n.isBlank() ? null : n;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /** Best effort: a preview nobody applied or discarded within a day is removed. */
    private static void sweepExpired(Path dir) {
        long cutoff = System.currentTimeMillis() - PREVIEW_TTL_MS;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                FileTime modified = Files.getLastModifiedTime(f);
                if (modified.toMillis() < cutoff) {
                    deleteQuietly(f);
                }
            }
        } catch (IOException e) {
            log.warn("Lookup import preview sweep failed in {}: {}", dir, e.getMessage());
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", p, e.getMessage());
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static List<LookupColumn> columns(LookupList list) {
        return list.getColumns() == null ? List.of() : list.getColumns();
    }
}
