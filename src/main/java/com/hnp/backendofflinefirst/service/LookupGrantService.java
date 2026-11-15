package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.LookupValues;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LookupList;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LookupListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which reference lists a round draws from, and which a user may therefore hold (roadmap §31,
 * Phase 2 — «the grant»).
 *
 * <h2>The rule, and why it is this narrow</h2>
 *
 * <p>A list may hold personal data — a personnel list is names. So a list leaves the server only
 * for a reason that can be named: <b>a round assigned to this user, still open, draws from it</b>.
 * Not every list the user's role could reach, not the lists of rounds they could claim, not the
 * lists of a finished round. When the last such round closes, the grant ends, and the device drops
 * its copy at the next sync (set difference — the PWA's {@code lookupStore}).
 *
 * <p>«Draws from» is read from the round's <b>frozen</b> definitions — the asset fields and the
 * round's own questions — never today's, so an administrator editing a class cannot widen what an
 * open round's operator may download.
 */
@Service
@RequiredArgsConstructor
public class LookupGrantService {

    private final LogSheetFieldService sheetFieldService;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;
    private final LogSheetEntryRepository entryRepository;
    private final LogSheetAccessService logSheetAccessService;
    private final LookupListRepository listRepository;

    /** Lower-cased codes of every list this round's fields and questions draw from. */
    @Transactional(readOnly = true)
    public Set<String> listsReferencedBy(LogSheet sheet) {
        Set<String> out = new LinkedHashSet<>();
        if (sheet == null) {
            return out;
        }
        List<FieldDefinitionSnapshot> frozen = sheet.getFieldDefinitionsSnapshot();
        if (frozen != null) {
            for (FieldDefinitionSnapshot field : frozen) {
                add(out, field.getDataType(), field.getValidation());
            }
        } else {
            // A sheet raised before snapshots existed predates lookup fields too; resolved live
            // only so the answer is right rather than merely likely.
            for (FieldDefinition field : fieldDefinitionsService.resolveForEntries(
                    sheet, entryRepository.findByLogSheetId(sheet.getId()))) {
                add(out, field.getDataType(), field.getValidation());
            }
        }
        for (FieldDefinition question : sheetFieldService.resolveForSheet(sheet)) {
            add(out, question.getDataType(), question.getValidation());
        }
        return out;
    }

    /** Whether this round draws from the list — the panel search's object check. */
    @Transactional(readOnly = true)
    public boolean sheetUsesList(LogSheet sheet, String listCode) {
        return listCode != null && listsReferencedBy(sheet).contains(listCode.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The lists a round needs on a device, with the revision each stands at now — the bundle's
     * {@code requiredLookups}. A list the round names that no longer exists is left out: the
     * device then has nothing to fetch and the field falls back to entering a code.
     */
    @Transactional(readOnly = true)
    public List<LookupList> requiredLists(LogSheet sheet) {
        Set<String> codes = listsReferencedBy(sheet);
        if (codes.isEmpty()) {
            return List.of();
        }
        Map<String, LookupList> out = new LinkedHashMap<>();
        for (String code : codes) {
            listRepository.findByCodeIgnoreCase(code).ifPresent(list -> out.put(code, list));
        }
        return List.copyOf(out.values());
    }

    /**
     * Whether this user may hold the list on a device right now: a round assigned to them, still
     * open, draws from it. The device endpoint's object check.
     */
    @Transactional(readOnly = true)
    public boolean userMayHoldList(Long userId, String listCode) {
        if (userId == null || listCode == null) {
            return false;
        }
        String wanted = listCode.trim().toLowerCase(Locale.ROOT);
        for (LogSheet sheet : logSheetAccessService.findAssignedTo(userId)) {
            if (listsReferencedBy(sheet).contains(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static void add(Set<String> out, String dataType, Map<String, Object> validation) {
        if (!LookupValues.isLookupField(dataType)) {
            return;
        }
        String list = FieldValidationSupport.lookupList(validation);
        if (list != null) {
            out.add(list.toLowerCase(Locale.ROOT));
        }
    }
}
