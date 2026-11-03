package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FormDataValidationSupport;
import com.hnp.backendofflinefirst.domain.FormDataValidationSupport.ValidationIssue;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetField;
import com.hnp.backendofflinefirst.repository.LogSheetFieldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * The round's own questions: captured when a sheet is raised, resolved when it is rendered,
 * validated when it is answered.
 *
 * <p>The sheet-level counterpart of {@link LogSheetFieldDefinitionsService}, and deliberately a
 * much smaller class than it. The per-asset side has to resolve a three-layer chain — class,
 * sub-class, per-asset override — and narrow by {@code classId} so two classes' identically
 * keyed fields stay apart. A sheet-level list belongs to no class and has no layers: one owner,
 * one list.
 *
 * <h2>The snapshot is preferred, always</h2>
 *
 * <p>{@link #resolveForSheet} reads {@code log_sheets.sheet_field_definitions_snapshot} and only
 * falls back to the live owner when a sheet has none — a round raised before V9, or before its
 * template had any questions. This is the same rule the per-asset schema has followed since V1,
 * and for the same reason: editing a question next month must not re-describe a round that was
 * already walked under the old wording.
 */
@Service
@RequiredArgsConstructor
public class LogSheetFieldService {

    private final LogSheetFieldRepository repository;

    // ------------------------------------------------------------------ editing

    /** Every question a template owns, switched-off ones included. For the editor only. */
    @Transactional(readOnly = true)
    public List<LogSheetField> editorRowsForTemplate(Long templateId) {
        if (templateId == null) {
            return List.of();
        }
        return repository.findForTemplate(templateId);
    }

    // ------------------------------------------------------------------ capture

    /**
     * The questions to freeze onto a sheet being raised from this template.
     *
     * <p>Switched-off ones are left out, which is what switching one off means: not offered on a
     * round from now on, still rendered on a round that froze it earlier.
     *
     * @return an empty list when the template owns no live questions — the caller stores null
     *         rather than an empty array, so a sheet with no round-level form is indistinguishable
     *         from one raised before the feature existed, which is exactly what it is
     */
    @Transactional(readOnly = true)
    public List<FieldDefinitionSnapshot> captureForTemplate(Long templateId) {
        if (templateId == null) {
            return List.of();
        }
        return snapshotOf(repository.findForTemplate(templateId));
    }

    /** The same, for a custom sheet that owns its questions directly. */
    @Transactional(readOnly = true)
    public List<FieldDefinitionSnapshot> captureForSheet(Long logSheetId) {
        if (logSheetId == null) {
            return List.of();
        }
        return snapshotOf(repository.findForLogSheet(logSheetId));
    }

    private static List<FieldDefinitionSnapshot> snapshotOf(List<LogSheetField> rows) {
        return rows.stream()
                .filter(row -> !row.isDeleted())
                .map(LogSheetField::toFieldDefinition)
                .map(FieldDefinitionSnapshot::from)
                .toList();
    }

    // ------------------------------------------------------------------ reading

    /**
     * The questions one sheet is actually answered against.
     *
     * <p>The sheet's own frozen copy when it has one; otherwise the live owner, so a round
     * raised before its template had questions still shows them rather than nothing. Costs no
     * query at all in the common case, because the snapshot travels on the sheet row.
     */
    @Transactional(readOnly = true)
    public List<FieldDefinition> resolveForSheet(LogSheet sheet) {
        if (sheet == null) {
            return List.of();
        }
        List<FieldDefinitionSnapshot> frozen = sheet.getSheetFieldDefinitionsSnapshot();
        if (frozen != null && !frozen.isEmpty()) {
            return FieldDefinitionSnapshot.toFieldDefinitions(frozen);
        }
        List<LogSheetField> live = sheet.getTemplateId() != null
                ? repository.findForTemplate(sheet.getTemplateId())
                : repository.findForLogSheet(sheet.getId());
        return live.stream()
                .filter(row -> !row.isDeleted())
                .map(LogSheetField::toFieldDefinition)
                .toList();
    }

    // ------------------------------------------------------------------ writing

    /**
     * What may actually be stored, from what a client submitted.
     *
     * <p>Both halves matter and both are the per-asset path's own helpers rather than new rules:
     *
     * <ul>
     *   <li>{@code retainKnownKeys} drops every key the frozen questions do not mention, so a
     *       crafted or stale request cannot widen the form. Its empty-schema rule applies here
     *       too — <em>no</em> questions means <em>no</em> keys allowed, never "accept
     *       anything".</li>
     *   <li>{@code answeredOnly} removes blanks, so a round nobody answered stores an empty map
     *       rather than a map of empty strings. Clearing an answer still works: the key simply
     *       disappears, and {@code isAnswered} treats an absent key and a blank one alike.</li>
     * </ul>
     */
    public Map<String, Object> storable(List<FieldDefinition> questions, Map<String, Object> submitted) {
        return FormDataValidationSupport.answeredOnly(
                FormDataValidationSupport.retainKnownKeys(submitted, questions));
    }

    /**
     * What is wrong with these answers, as a message, or {@code null} when nothing is.
     *
     * <p><strong>{@code validate}, not {@code validateFilledEntry}.</strong> That one returns no
     * issues at all for a map holding no answers — deliberately, so a blank asset on a 40-asset
     * round is allowed. Using it here would mean a required round-level question never fires:
     * the single case it exists for is the single case that would be skipped. There is one
     * round-level form per sheet and leaving it blank is not "this asset was not reached".
     *
     * <p>Nothing is required in this release, so today this only rejects a malformed answer — a
     * number that is not a number, a choice outside its vocabulary. The distinction above is
     * what makes switching {@code required} on later a configuration change rather than a code
     * change.
     */
    public String validationMessage(List<FieldDefinition> questions, Map<String, Object> submitted) {
        if (questions.isEmpty()) {
            return null;
        }
        return format(FormDataValidationSupport.validate(submitted, questions));
    }

    /**
     * The same, for a round that is still being walked.
     *
     * <p><strong>Required questions are not checked.</strong> A progress push reports a partial
     * round by definition — the operator is at asset seven of forty — so judging it against
     * "every required question is answered" would refuse every push until the last one. The
     * shape of the answers that <em>are</em> there is still checked, because those are client
     * bugs that the final submit would reject anyway, and letting them into the column early
     * would mean the supervisor's live view showed values the submission then refuses.
     *
     * <p>Nothing is required in this release, so today the two methods agree on every input.
     * They are separate so that switching {@code required} on is a configuration change rather
     * than a hunt for the place that forgot the distinction.
     */
    public String partialValidationMessage(List<FieldDefinition> questions,
                                           Map<String, Object> submitted) {
        if (questions.isEmpty()) {
            return null;
        }
        return format(FormDataValidationSupport.validatePartialEntry(submitted, questions));
    }

    private static String format(List<ValidationIssue> issues) {
        if (issues.isEmpty()) {
            return null;
        }
        StringBuilder message = new StringBuilder("Sheet form validation failed: ");
        for (int i = 0; i < issues.size(); i++) {
            if (i > 0) {
                message.append("; ");
            }
            message.append("field '").append(issues.get(i).fieldKey()).append("': ")
                    .append(issues.get(i).message());
        }
        return message.toString();
    }
}
