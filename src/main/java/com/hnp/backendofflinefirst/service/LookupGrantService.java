package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.LookupValues;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.FormField;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LookupList;
import com.hnp.backendofflinefirst.repository.FormFieldRepository;
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
 * Which reference lists a round or a fillable ad-hoc form draws from, and which a user may
 * therefore hold (roadmap §31 Phase 2 + V29 carrier 3).
 *
 * <h2>The rule</h2>
 *
 * <p>A list leaves the server only for a reason that can be named:
 * <ul>
 *   <li>a round assigned to this user, still open, draws from it; <b>or</b></li>
 *   <li>an active form this user may FILL draws from it (union leased at login / bootstrap).</li>
 * </ul>
 * When neither applies, the grant ends and the device drops its copy at the next sync.
 */
@Service
@RequiredArgsConstructor
public class LookupGrantService {

    private final LogSheetFieldService sheetFieldService;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;
    private final LogSheetEntryRepository entryRepository;
    private final LogSheetAccessService logSheetAccessService;
    private final LookupListRepository listRepository;
    private final FormAccessService formAccessService;
    private final FormFieldRepository formFieldRepository;

    /** Lower-cased codes of every list this round's fields and questions draw from. */
    @Transactional(readOnly = true)
    public Set<String> listsReferencedBy(LogSheet sheet) {
        Set<String> out = new LinkedHashSet<>();
        if (sheet == null) {
            return out;
        }
        List<FieldDefinitionSnapshot> frozen = sheet.getFieldDefinitionsSnapshot();
        if (sheet.getEntrySchema() != null) {
            for (var fields : sheet.getEntrySchema().definitions()) {
                for (var field : fields) add(out, field.getDataType(), field.getValidation());
            }
        } else if (frozen != null) {
            for (FieldDefinitionSnapshot field : frozen) {
                add(out, field.getDataType(), field.getValidation());
            }
        } else {
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

    /** Whether a form definition's live fields draw from the list (new-fill picker). */
    @Transactional(readOnly = true)
    public boolean formDefinitionUsesList(Long formDefinitionId, String listCode) {
        return listCode != null
                && listsReferencedByForm(formDefinitionId).contains(listCode.trim().toLowerCase(Locale.ROOT));
    }

    /** Whether this filing's frozen (or live) fields draw from the list. */
    @Transactional(readOnly = true)
    public boolean formSubmissionUsesList(FormSubmission submission, String listCode) {
        if (submission == null || listCode == null) {
            return false;
        }
        String wanted = listCode.trim().toLowerCase(Locale.ROOT);
        List<FieldDefinitionSnapshot> frozen = submission.getFieldDefinitionsSnapshot();
        if (frozen != null && !frozen.isEmpty()) {
            Set<String> out = new LinkedHashSet<>();
            for (FieldDefinitionSnapshot field : frozen) {
                add(out, field.getDataType(), field.getValidation());
            }
            return out.contains(wanted);
        }
        return formDefinitionUsesList(submission.getFormDefinitionId(), listCode);
    }

    /**
     * The lists a round needs on a device, with the revision each stands at now — the bundle's
     * {@code requiredLookups}. A list the round names that no longer exists is left out: the
     * device then has nothing to fetch and the field falls back to entering a code.
     */
    @Transactional(readOnly = true)
    public List<LookupList> requiredLists(LogSheet sheet) {
        return resolveLists(listsReferencedBy(sheet));
    }

    /** Lower-cased codes drawn from a form definition's live (non-deleted) fields. */
    @Transactional(readOnly = true)
    public Set<String> listsReferencedByForm(Long formDefinitionId) {
        Set<String> out = new LinkedHashSet<>();
        if (formDefinitionId == null) {
            return out;
        }
        for (FormField field : formFieldRepository.findByFormDefinitionIdAndDeletedFalseOrderByOrderAscIdAsc(formDefinitionId)) {
            add(out, field.getDataType(), field.getValidation());
        }
        return out;
    }

    /**
     * Union of lookup codes for every form this user may FILL right now — leased on login /
     * bootstrap so air-gapped tablets can open those forms offline.
     */
    @Transactional(readOnly = true)
    public Set<String> listsForFillableForms(Long userId) {
        Set<String> out = new LinkedHashSet<>();
        if (userId == null) {
            return out;
        }
        for (FormDefinition form : formAccessService.fillableForms(userId)) {
            out.addAll(listsReferencedByForm(form.getId()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<LookupList> requiredListsForFillableForms(Long userId) {
        return resolveLists(listsForFillableForms(userId));
    }

    /**
     * Whether this user may hold the list on a device right now: an open assigned round draws
     * from it, <b>or</b> a fillable form draws from it.
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
        return listsForFillableForms(userId).contains(wanted);
    }

    private List<LookupList> resolveLists(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        Map<String, LookupList> out = new LinkedHashMap<>();
        for (String code : codes) {
            listRepository.findByCodeIgnoreCase(code).ifPresent(list -> out.put(code, list));
        }
        return List.copyOf(out.values());
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
