package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.SignatureScope;
import com.hnp.backendofflinefirst.domain.SignatureValues;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The one place a signature's verdict is computed from a log sheet (roadmap §32) — for the panel,
 * the tablet's bundle and the integration feed alike, so the three can never disagree about
 * whether a signature still vouches for what it covers.
 *
 * <p>{@link SignatureValues} holds the rules and stays free of repositories; this class only
 * gathers what a {@link SignatureScope#ROUND} signature covers — every asset's answers, judged
 * against <b>that asset's</b> resolved schema ({@code groupByAsset}, the same resolution the
 * write path stamps with). A {@code FORM} or {@code NONE} signature reads nothing from the
 * database.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SignatureVerificationService {

    /** Bundle key for a round-level signature: {@code "sheet:<fieldKey>"}. */
    public static final String SHEET_OWNER = "sheet";

    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;
    private final LogSheetFieldService sheetFieldService;

    /** Every asset's answers on this round, as they stand now. */
    public List<SignatureValues.EntryAnswers> roundAnswers(LogSheet sheet) {
        return roundAnswers(sheet, logSheetEntryRepository.findByLogSheetId(sheet.getId()));
    }

    public List<SignatureValues.EntryAnswers> roundAnswers(LogSheet sheet, Collection<LogSheetEntry> entries) {
        Map<Long, List<FieldDefinition>> defsByAsset = fieldDefinitionsService.groupByAsset(sheet, entries);
        List<SignatureValues.EntryAnswers> out = new ArrayList<>();
        for (LogSheetEntry entry : entries) {
            if (entry.getAssetId() == null) {
                continue;
            }
            out.add(new SignatureValues.EntryAnswers(entry.getAssetId(), entry.getFormData(),
                    defsByAsset.getOrDefault(entry.getAssetId(), List.of())));
        }
        return out;
    }

    /**
     * The verdict on one stored signature.
     *
     * @param logSheetId the round it belongs to — needed only when it was taken under
     *                   {@link SignatureScope#ROUND}; null where there is no round to read (a voided
     *                   submission's payload), which makes such a signature {@code UNVERIFIED}
     */
    public SignatureValues.State verify(Long logSheetId, Object value, Map<String, Object> ownerFormData,
                                        Collection<FieldDefinition> ownerDefs) {
        Supplier<List<SignatureValues.EntryAnswers>> round = null;
        if (logSheetId != null && SignatureValues.scopeOf(value) == SignatureScope.ROUND) {
            round = () -> logSheetRepository.findById(logSheetId)
                    .map(this::roundAnswers)
                    .orElse(List.of());
        }
        return SignatureValues.state(value, ownerFormData, ownerDefs, round);
    }

    /**
     * Every signature's verdict on this round, keyed {@code "sheet:<fieldKey>"} for a round-level
     * question and {@code "<assetId>:<fieldKey>"} for an asset's — what the tablet's bundle
     * carries, so the operator sees a signature the server judges out of date and can sign again.
     * Empty when the round holds no signature, which is every round that asks for none: nothing
     * here is computed for them beyond one scan of the answers already loaded.
     */
    public Map<String, String> statesFor(LogSheet sheet, List<LogSheetEntry> entries) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<Long, List<FieldDefinition>> defsByAsset = null;
        for (LogSheetEntry entry : entries) {
            if (entry.getAssetId() == null || !holdsSignature(entry.getFormData())) {
                continue;
            }
            if (defsByAsset == null) {
                defsByAsset = fieldDefinitionsService.groupByAsset(sheet, entries);
            }
            List<FieldDefinition> defs = defsByAsset.getOrDefault(entry.getAssetId(), List.of());
            entry.getFormData().forEach((key, value) -> {
                if (SignatureValues.isSignatureValue(value)) {
                    out.put(entry.getAssetId() + ":" + key,
                            SignatureValues.state(value, entry.getFormData(), defs).name());
                }
            });
        }
        Map<String, Object> sheetAnswers = sheet.getSheetFormData();
        if (holdsSignature(sheetAnswers)) {
            List<FieldDefinition> questions = sheetFieldService.resolveForSheet(sheet);
            Supplier<List<SignatureValues.EntryAnswers>> round = () -> roundAnswers(sheet, entries);
            sheetAnswers.forEach((key, value) -> {
                if (SignatureValues.isSignatureValue(value)) {
                    out.put(SHEET_OWNER + ":" + key,
                            SignatureValues.state(value, sheetAnswers, questions, round).name());
                }
            });
        }
        return out;
    }

    private static boolean holdsSignature(Map<String, Object> formData) {
        return formData != null && formData.values().stream().anyMatch(SignatureValues::isSignatureValue);
    }
}
