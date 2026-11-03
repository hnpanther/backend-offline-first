package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FormDataValidationSupport;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetRevision;
import com.hnp.backendofflinefirst.repository.LogSheetRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the round-level answers a change replaced.
 *
 * <p>The sheet-level twin of {@link LogSheetEntryRevisionService}, kept as its own class rather
 * than as extra methods on that one: each writes exactly one table, and a service called
 * {@code ...EntryRevisionService} that also wrote sheet rows would be a name that lies to
 * everything injecting it. The two share their rules, not their code.
 *
 * <p><b>One writer, called from every path that changes the answers.</b> Today that is the web
 * fill page — its round-level save and its final completion — and the mobile paths join it when
 * the device learns to send them. Each caller already knows whether the value genuinely changed,
 * because each uses that same answer to decide whether to move authorship (AGENTS.md #20); this
 * service hangs off that one decision so the two can never drift apart. Answers whose authorship
 * moved with no history row, and a history row written for a save that changed nothing, are each
 * a lie of their own kind.
 *
 * <p><b>What it deliberately does not record: a first fill.</b> Nothing was replaced, so there is
 * no earlier answer to keep, and writing an empty row for every round would make the table grow
 * with rounds instead of with corrections.
 */
@Service
@RequiredArgsConstructor
public class LogSheetRevisionService {

    private final LogSheetRevisionRepository repository;

    /**
     * Records the round-level answers {@code sheet} is about to lose, if it had any.
     *
     * <p><b>Call this before mutating the sheet</b>, while it still holds what is about to be
     * overwritten. Every field copied here comes off the sheet as it currently stands; nothing
     * is re-derived.
     *
     * @param sheet        the sheet as it is <em>now</em>, before the new answers are applied
     * @param actorUserId  who is overwriting them; null for a server-driven path
     * @param source       which surface the overwrite came from
     * @param supersededAt server time of the overwrite
     * @return {@code true} when a revision row was written
     */
    @Transactional
    public boolean recordSupersededAnswers(LogSheet sheet, Long actorUserId,
                                           ActionSource source, long supersededAt) {
        if (sheet == null || sheet.getId() == null) {
            return false;
        }
        Map<String, Object> previous = sheet.getSheetFormData();
        if (!FormDataValidationSupport.hasMeaningfulFormData(previous)) {
            // Nothing was replaced. See the class javadoc.
            return false;
        }

        LogSheetRevision revision = new LogSheetRevision();
        revision.setLogSheetId(sheet.getId());
        // Copied into an independent map: the caller is about to hand the sheet a new map, and
        // keeping a reference here would silently record the new answers as the old ones.
        revision.setFormData(new LinkedHashMap<>(previous));
        revision.setRecordedByUserId(sheet.getSheetFormFilledByUserId());
        revision.setRecordedAt(sheet.getSheetFormUpdatedAt());
        revision.setSupersededByUserId(actorUserId);
        revision.setSupersededAt(supersededAt);
        revision.setSupersededSource(source);
        revision.setSheetStatus(sheet.getStatus());
        repository.save(revision);
        return true;
    }

    /** One sheet's superseded round-level answers, oldest first. */
    @Transactional(readOnly = true)
    public List<LogSheetRevision> findForSheet(Long logSheetId) {
        if (logSheetId == null) {
            return List.of();
        }
        return repository.findByLogSheetIdOrderByIdAsc(logSheetId);
    }
}
