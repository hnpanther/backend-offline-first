package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AssetScanMode;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.IntegrationLogSheetQuery;
import com.hnp.backendofflinefirst.domain.IntegrationKeyScope;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.integration.IntegrationLogSheetDetail;
import com.hnp.backendofflinefirst.dto.integration.IntegrationLogSheetSummary;
import com.hnp.backendofflinefirst.dto.integration.IntegrationPage;
import com.hnp.backendofflinefirst.dto.integration.IntegrationReferences;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.domain.SignatureValues;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetCategory;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.LogSheetCategoryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.hnp.backendofflinefirst.service.AttachmentReferences;

/**
 * The read model behind {@code /integration/v1/**}.
 *
 * <p>Its whole job is translation: from rows shaped for this application's own workings into a
 * document shaped for somebody else's. Nothing here decides access — that happened in the
 * filter chain — and nothing here filters out unfinished work either, because
 * {@code LogSheetRepository.findExposableToIntegration*} does that in SQL where it cannot be
 * bypassed.
 *
 * <p><b>Every lookup is batched.</b> A page of 200 sheets naively mapped is 200 entry queries,
 * 200 unit queries and 200 user queries; at one poll a minute that is a load pattern nothing
 * else in this application produces. The list endpoint resolves each dimension once for the
 * whole page — the same discipline the report queries use, and for the same reason.
 */
@Service
@RequiredArgsConstructor
public class IntegrationLogSheetService {

    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final AttachmentRepository attachmentRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final UserRepository userRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final AssetClassRepository assetClassRepository;
    private final LogSheetFieldService sheetFieldService;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;
    private final SignatureVerificationService signatureVerificationService;
    private final LogSheetCategoryRepository categoryRepository;

    /** Everything the API exposes, as an unrestricted key reads it. */
    @Transactional(readOnly = true)
    public IntegrationPage<IntegrationLogSheetSummary> search(IntegrationLogSheetQuery query) {
        return search(query, IntegrationKeyScope.UNRESTRICTED);
    }

    /**
     * One page of finished rounds, as the calling key may read them.
     *
     * @throws IntegrationKeyScope.IntegrationScopeException when the caller named a status its
     *         key may not read
     */
    @Transactional(readOnly = true)
    public IntegrationPage<IntegrationLogSheetSummary> search(IntegrationLogSheetQuery query,
                                                              IntegrationKeyScope scope) {
        Set<LogSheetStatus> statuses = scope.effectiveStatuses(query.statuses(), query.statusesNamed());
        Set<Long> categoryIds = scope.effectiveCategoryIds(resolveCategoryCodes(query.categoryCodes()));
        if (categoryIds != null && categoryIds.isEmpty()) {
            // Nothing can match — an unknown code, or only codes outside the key's scope. Answered
            // without a query, and never passed on: an empty set is one step from «no filter».
            return IntegrationPage.of(Page.empty(PageRequest.of(query.page(), query.size())), sheet -> null);
        }
        Page<LogSheet> page = logSheetRepository.findExposableToIntegration(
                statuses,
                query.fromEpochMillis(),
                query.toEpochMillis(),
                query.unitIds(),
                query.templateIds(),
                query.requiresAssets(),
                query.classIds(),
                query.assetIds(),
                query.scanModes(),
                query.origins(),
                query.severities(),
                categoryIds,
                // Unsorted: the query declares its own ORDER BY, and a Sort here would append a
                // second one that the covering index does not serve.
                PageRequest.of(query.page(), query.size()));

        List<LogSheet> sheets = page.getContent();
        List<Long> sheetIds = sheets.stream().map(LogSheet::getId).toList();

        Map<Long, Integer> assetCounts = countBySheet(
                sheetIds.isEmpty() ? List.of() : logSheetEntryRepository.findByLogSheetIdIn(sheetIds),
                LogSheetEntry::getLogSheetId);
        Map<Long, Integer> attachmentCounts = countBySheet(
                sheetIds.isEmpty() ? List.of() : attachmentRepository.findByLogSheetIdInOrderByUploadedAtAsc(sheetIds)
                        .stream().filter(Attachment::isLive).toList(),
                Attachment::getLogSheetId);
        Map<Long, OperationalUnit> units = unitsOf(sheets);
        Map<Long, User> users = usersOf(sheets);
        Map<Long, LogSheetCategory> categories = categoriesOf(sheets);

        return IntegrationPage.of(page, sheet -> toSummary(
                sheet,
                units,
                users,
                categories,
                assetCounts.getOrDefault(sheet.getId(), 0),
                attachmentCounts.getOrDefault(sheet.getId(), 0)));
    }

    /**
     * @return empty when no finished log sheet carries that id — including when an unfinished
     *         one does. The caller cannot tell the two apart, deliberately.
     */
    @Transactional(readOnly = true)
    public Optional<IntegrationLogSheetDetail> findDetail(Long id) {
        return findDetail(id, IntegrationKeyScope.UNRESTRICTED);
    }

    /**
     * @return empty as above, and also for a finished sheet outside the calling key's scope —
     *         answered exactly like one that does not exist, so a scoped key cannot learn which
     *         ids belong to rounds it may not read
     */
    @Transactional(readOnly = true)
    public Optional<IntegrationLogSheetDetail> findDetail(Long id, IntegrationKeyScope scope) {
        return logSheetRepository.findExposableToIntegrationById(id)
                .filter(sheet -> scope.permits(sheet.getStatus(), sheet.getCategoryId()))
                .map(this::toDetail);
    }

    /**
     * The ids the caller's category codes name: {@code null} when it named none, possibly empty
     * when every code is unknown.
     */
    private Set<Long> resolveCategoryCodes(Set<String> codes) {
        if (codes == null) {
            return null;
        }
        return categoryRepository.findByCodeUpperIn(codes).stream()
                .map(LogSheetCategory::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private IntegrationLogSheetSummary toSummary(LogSheet sheet,
                                                 Map<Long, OperationalUnit> units,
                                                 Map<Long, User> users,
                                                 Map<Long, LogSheetCategory> categories,
                                                 int assetCount,
                                                 int attachmentCount) {
        return new IntegrationLogSheetSummary(
                sheet.getId(),
                sheet.getTemplateId(),
                sheet.getTemplateName(),
                sheet.getScopeSummary(),
                sheet.getStatus() == null ? null : sheet.getStatus().name(),
                sheet.getOrigin() == null ? null : sheet.getOrigin().name(),
                // orDefault, not a null check: the column is NOT NULL DEFAULT 'NFC' since V11,
                // and a sheet read through an older mapping is an NFC round — which is what it
                // was. Answering null would invite a consumer to treat the mode as unknown.
                AssetScanMode.orDefault(sheet.getScanMode()).name(),
                categoryOf(sheet, categories),
                unitOf(sheet.getOperationalUnitId(), units),
                iso(sheet.getDueAt()),
                iso(sheet.getCompletedAt()),
                iso(sheet.getSubmittedAt()),
                iso(finalizedAt(sheet)),
                iso(sheet.getApprovedAt()),
                personOf(sheet.getApprovedByUserId(), users),
                personOf(sheet.getAssigneeUserId(), users),
                personOf(sheet.getCompletedByUserId(), users),
                sheet.getOperatorName(),
                assetCount,
                attachmentCount);
    }

    private IntegrationLogSheetDetail toDetail(LogSheet sheet) {
        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
        // The record of the round as it stands: a void's evidence (V15) is not part of it.
        List<Attachment> attachments = attachmentRepository.findByLogSheetIdOrderByUploadedAtAsc(sheet.getId())
                .stream().filter(Attachment::isLive).toList();

        Map<Long, OperationalUnit> units = unitsOf(List.of(sheet));
        // One user lookup for the sheet's own actors plus every operator who filled a row.
        Set<Long> userIds = new HashSet<>();
        addIfPresent(userIds, sheet.getAssigneeUserId());
        addIfPresent(userIds, sheet.getCompletedByUserId());
        addIfPresent(userIds, sheet.getApprovedByUserId());
        addIfPresent(userIds, sheet.getSheetFormFilledByUserId());
        entries.forEach(entry -> addIfPresent(userIds, entry.getFilledByUserId()));
        // And every signer (roadmap §32): a signature is published with WHO signed as a Person,
        // never as the internal user id stored in form_data.
        entries.forEach(entry -> addSigners(userIds, entry.getFormData()));
        addSigners(userIds, sheet.getSheetFormData());
        Map<Long, User> users = usersById(userIds);

        Map<Long, AssetEntry> assets = assetsById(entries);
        Map<Long, AssetClass> classes = classesById(entries, assets);
        Map<String, Attachment> attachmentsById = attachments.stream()
                .collect(Collectors.toMap(Attachment::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        List<FieldDefinitionSnapshot> snapshot = sheet.getFieldDefinitionsSnapshot() == null
                ? List.of()
                : sheet.getFieldDefinitionsSnapshot().stream()
                        .sorted(FieldDefinitionSnapshot.displayOrder())
                        .toList();

        return new IntegrationLogSheetDetail(
                toSummary(sheet, units, users, categoriesOf(List.of(sheet)), entries.size(), attachments.size()),
                snapshot.stream()
                        .map(f -> new IntegrationLogSheetDetail.Field(
                                f.getKey(), f.getLabel(), f.getDataType(), f.getUnit(),
                                f.isRequired(), f.getClassId(), f.getOrder()))
                        .toList(),
                entries.stream()
                        .map(entry -> toAssetRecord(sheet, entry, assets, classes, users, snapshot, attachmentsById))
                        .toList(),
                toSheetForm(sheet, users, attachmentsById),
                iso(sheet.getExpiredAt()),
                iso(sheet.getCancelledAt()));
    }

    /**
     * The round's own questions and answers, or null when it has none.
     *
     * <p>The schema comes from {@code LogSheetFieldService.resolveForSheet} rather than straight
     * off the snapshot column, so this endpoint describes a round the same way every other
     * surface does: the frozen copy when the sheet has one, the live owner when it never froze
     * one. A custom round that expired before anyone answered it falls into that second case,
     * and publishing its questions with empty answers is the truthful account of it — publishing
     * nothing would say it asked nothing.
     *
     * <p>Converted to {@code FieldDefinitionSnapshot} immediately so everything downstream —
     * ordering, the value loop, attachment resolution — is the per-asset code unchanged.
     */
    private IntegrationLogSheetDetail.SheetForm toSheetForm(LogSheet sheet,
                                                            Map<Long, User> users,
                                                            Map<String, Attachment> attachmentsById) {
        List<FieldDefinitionSnapshot> schema = sheetFieldService.resolveForSheet(sheet).stream()
                .map(FieldDefinitionSnapshot::from)
                .toList();
        if (schema.isEmpty()) {
            return null;
        }
        return new IntegrationLogSheetDetail.SheetForm(
                schema.stream()
                        .map(f -> new IntegrationLogSheetDetail.Field(
                                f.getKey(), f.getLabel(), f.getDataType(), f.getUnit(),
                                f.isRequired(), f.getClassId(), f.getOrder()))
                        .toList(),
                valuesOf(sheet.getId(), sheet.getSheetFormData(), schema, null, attachmentsById, users,
                        () -> sheetFieldService.resolveForSheet(sheet)),
                iso(sheet.getSheetFormUpdatedAt()),
                personOf(sheet.getSheetFormFilledByUserId(), users),
                sheet.getSheetFormSource());
    }

    private IntegrationLogSheetDetail.AssetRecord toAssetRecord(
            LogSheet sheet,
            LogSheetEntry entry,
            Map<Long, AssetEntry> assets,
            Map<Long, AssetClass> classes,
            Map<Long, User> users,
            List<FieldDefinitionSnapshot> snapshot,
            Map<String, Attachment> attachmentsById) {

        AssetEntry asset = entry.getAssetId() == null ? null : assets.get(entry.getAssetId());
        AssetClass assetClass = entry.getClassId() == null ? null : classes.get(entry.getClassId());

        return new IntegrationLogSheetDetail.AssetRecord(
                new IntegrationReferences.Asset(
                        entry.getAssetId(),
                        asset == null ? null : asset.getAssetCode(),
                        // The name frozen on the entry, not today's name on the asset: a sheet
                        // is a record of what was inspected, and renaming equipment afterwards
                        // must not rewrite history.
                        entry.getAssetName(),
                        assetClass == null ? null : assetClass.getName(),
                        entry.getSubFunctionCode(),
                        entry.getSubFunctionTag(),
                        entry.getNfcTagId()),
                toValues(sheet, entry, snapshot, attachmentsById, users),
                entry.getMaxSeverity(),
                entry.getBreachedFields(),
                iso(entry.getUpdatedAt()),
                personOf(entry.getFilledByUserId(), users),
                entry.getEntrySource() == null ? null : entry.getEntrySource().name());
    }

    /**
     * The recorded values for one asset, in schema order.
     *
     * <p>Driven by the <b>snapshot</b>, not by the keys present in {@code form_data}. Two
     * reasons, and both have bitten this codebase before: a parameter the operator left blank
     * is information (see gotcha 9c-2 on what "empty" means here) and disappears entirely if
     * the map drives the loop; and a stray key that somehow reached {@code form_data} would
     * otherwise be published unlabelled to an external system.
     *
     * <p>An attachment-typed parameter carries {@code value = null} and its metadata in
     * {@code attachments} — never the bytes, and never a base64 blob.
     */
    private List<IntegrationLogSheetDetail.Value> toValues(LogSheet sheet,
                                                           LogSheetEntry entry,
                                                           List<FieldDefinitionSnapshot> snapshot,
                                                           Map<String, Attachment> attachmentsById,
                                                           Map<Long, User> users) {
        // The asset's own resolved schema — the one its signature was stamped against — and only
        // resolved if a signature asks for it.
        return valuesOf(sheet.getId(), entry.getFormData(), snapshot, entry.getClassId(), attachmentsById, users,
                () -> fieldDefinitionsService.resolveForEntry(sheet, entry));
    }

    /**
     * The same loop for either owner — one asset's readings, or the round's own answers.
     *
     * <p>Shared rather than copied because the two have the same three subtleties (blank is
     * information, the schema drives the loop, an attachment publishes metadata only) and a
     * second copy would be where one of them was quietly dropped.
     *
     * @param ownerClassId the asset class to narrow a multi-class sheet's schema to;
     *                     {@code null} for the round's own form, which belongs to no class
     */
    private List<IntegrationLogSheetDetail.Value> valuesOf(Long logSheetId,
                                                           Map<String, Object> rawFormData,
                                                           List<FieldDefinitionSnapshot> snapshot,
                                                           Long ownerClassId,
                                                           Map<String, Attachment> attachmentsById,
                                                           Map<Long, User> users,
                                                           java.util.function.Supplier<List<FieldDefinition>> ownerDefs) {
        Map<String, Object> formData = rawFormData == null ? Map.of() : rawFormData;
        List<IntegrationLogSheetDetail.Value> values = new ArrayList<>();
        List<FieldDefinition> resolvedDefs = null;

        for (FieldDefinitionSnapshot field : snapshot) {
            // A sheet may span several asset classes; a parameter of another class is not a
            // missing answer for this asset, it is not this asset's parameter at all.
            if (field.getClassId() != null && ownerClassId != null
                    && !field.getClassId().equals(ownerClassId)) {
                continue;
            }
            Object raw = formData.get(field.getKey());
            List<IntegrationLogSheetDetail.Attachment> refs = attachmentsFor(raw, attachmentsById);
            if (SignatureValues.isSignatureField(field.getDataType())) {
                // A signature publishes its drawing as an attachment AND a value saying who
                // signed, when, and whether it still vouches for the answers beside it — the
                // verdict recomputed from the answers as they are now. No internal user id.
                if (resolvedDefs == null && raw != null) {
                    resolvedDefs = ownerDefs.get();
                }
                values.add(new IntegrationLogSheetDetail.Value(
                        field.getKey(), field.getLabel(), field.getUnit(), field.getDataType(),
                        signatureForFeed(logSheetId, raw, formData, resolvedDefs, users), refs));
                continue;
            }
            values.add(new IntegrationLogSheetDetail.Value(
                    field.getKey(),
                    field.getLabel(),
                    field.getUnit(),
                    field.getDataType(),
                    refs.isEmpty() ? raw : null,
                    refs));
        }
        return values;
    }

    /**
     * {@code {signedBy, signedAt, scope, state}} for a signature value, or null when the field is
     * unsigned. {@code scope} is what it vouches for ({@code NONE}, {@code FORM} or {@code ROUND});
     * {@code state} is {@code VALID}, {@code STALE} (an answer it covers changed after it was
     * drawn), {@code NOT_BOUND} (signed under {@code NONE}) or {@code UNVERIFIED} (no stamp to
     * check against). Judged by {@link SignatureVerificationService}, the one the panel and the
     * tablet's bundle use, so the three never disagree.
     */
    private Map<String, Object> signatureForFeed(Long logSheetId, Object raw, Map<String, Object> formData,
                                                 List<FieldDefinition> ownerDefs, Map<Long, User> users) {
        if (raw == null || AttachmentReferences.idsOf(raw).isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signedBy", personOf(SignatureValues.signedByUserId(raw), users));
        out.put("signedAt", iso(SignatureValues.signedAt(raw)));
        out.put("scope", SignatureValues.scopeOf(raw).name());
        out.put("state", signatureVerificationService.verify(logSheetId, raw, formData,
                ownerDefs == null ? List.of() : ownerDefs).name());
        return out;
    }

    private static void addSigners(Set<Long> userIds, Map<String, Object> formData) {
        if (formData == null) {
            return;
        }
        for (Object value : formData.values()) {
            if (SignatureValues.isSignatureValue(value)) {
                addIfPresent(userIds, SignatureValues.signedByUserId(value));
            }
        }
    }

    private List<IntegrationLogSheetDetail.Attachment> attachmentsFor(
            Object rawValue, Map<String, Attachment> attachmentsById) {
        if (!AttachmentReferences.looksLikeAttachmentValue(rawValue)) {
            return List.of();
        }
        List<IntegrationLogSheetDetail.Attachment> out = new ArrayList<>();
        for (String id : AttachmentReferences.idsOf(rawValue)) {
            Attachment attachment = attachmentsById.get(id);
            if (attachment == null) {
                // A reference whose row is gone — a photo deleted after the sheet was filled.
                // Reported as existing-but-unresolvable rather than dropped, because silently
                // omitting it would tell the consumer the operator never took a photo.
                out.add(new IntegrationLogSheetDetail.Attachment(id, null, null, null, null, null, null, null));
                continue;
            }
            out.add(new IntegrationLogSheetDetail.Attachment(
                    attachment.getId(),
                    attachment.getKind() == null ? null : attachment.getKind().name(),
                    attachment.getMimeType(),
                    attachment.getSizeBytes(),
                    attachment.getWidth(),
                    attachment.getHeight(),
                    attachment.getDurationMs(),
                    iso(attachment.getUploadedAt())));
        }
        return out;
    }

    // ── Batched lookups ──────────────────────────────────────────────────────

    private Map<Long, OperationalUnit> unitsOf(Collection<LogSheet> sheets) {
        Set<Long> ids = sheets.stream()
                .map(LogSheet::getOperationalUnitId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : operationalUnitRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(OperationalUnit::getId, Function.identity()));
    }

    /** One query for the page's categories — the code is not on the sheet, only the frozen name. */
    private Map<Long, LogSheetCategory> categoriesOf(Collection<LogSheet> sheets) {
        Set<Long> ids = sheets.stream()
                .map(LogSheet::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : categoryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(LogSheetCategory::getId, Function.identity()));
    }

    /**
     * The code from the category (it never changes), the name from the round (frozen when it was
     * raised). Null for an uncategorised round. A category row that has vanished cannot happen
     * while the foreign key stands; if it ever did, the frozen name is still the truth about the
     * round, so it is published with a null code rather than dropped.
     */
    private static IntegrationReferences.Category categoryOf(LogSheet sheet,
                                                             Map<Long, LogSheetCategory> categories) {
        if (sheet.getCategoryId() == null) {
            return null;
        }
        LogSheetCategory c = categories.get(sheet.getCategoryId());
        return new IntegrationReferences.Category(c == null ? null : c.getCode(), sheet.getCategoryName());
    }

    private Map<Long, User> usersOf(Collection<LogSheet> sheets) {
        Set<Long> ids = new HashSet<>();
        for (LogSheet sheet : sheets) {
            addIfPresent(ids, sheet.getAssigneeUserId());
            addIfPresent(ids, sheet.getCompletedByUserId());
            // The approver belongs here too, or every approved row would publish the
            // name-less Person that means "this user no longer exists" — a different fact.
            addIfPresent(ids, sheet.getApprovedByUserId());
        }
        return usersById(ids);
    }

    private Map<Long, User> usersById(Set<Long> ids) {
        return ids.isEmpty() ? Map.of() : userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private Map<Long, AssetEntry> assetsById(Collection<LogSheetEntry> entries) {
        Set<Long> ids = entries.stream()
                .map(LogSheetEntry::getAssetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : assetEntryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(AssetEntry::getId, Function.identity()));
    }

    private Map<Long, AssetClass> classesById(Collection<LogSheetEntry> entries,
                                              Map<Long, AssetEntry> assets) {
        Set<Long> ids = new HashSet<>();
        entries.forEach(entry -> addIfPresent(ids, entry.getClassId()));
        assets.values().forEach(asset -> addIfPresent(ids, asset.getClassId()));
        return ids.isEmpty() ? Map.of() : assetClassRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(AssetClass::getId, Function.identity()));
    }

    private static <T> Map<Long, Integer> countBySheet(Collection<T> rows, Function<T, Long> sheetIdOf) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (T row : rows) {
            Long sheetId = sheetIdOf.apply(row);
            if (sheetId != null) {
                counts.merge(sheetId, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static void addIfPresent(Set<Long> target, Long id) {
        if (id != null) {
            target.add(id);
        }
    }

    private static IntegrationReferences.Unit unitOf(Long unitId, Map<Long, OperationalUnit> units) {
        if (unitId == null) {
            return null;
        }
        OperationalUnit unit = units.get(unitId);
        return unit == null
                ? new IntegrationReferences.Unit(unitId, null, null)
                : new IntegrationReferences.Unit(unit.getId(), unit.getCode(), unit.getName());
    }

    private static IntegrationReferences.Person personOf(Long userId, Map<Long, User> users) {
        if (userId == null) {
            return null;
        }
        User user = users.get(userId);
        // A deleted user leaves the id behind on the sheet. Answering null would say nobody
        // completed the round; answering a record with no name says the record is incomplete,
        // which is the truth.
        return user == null
                ? new IntegrationReferences.Person(null, null, null)
                : new IntegrationReferences.Person(
                        user.getUsername(), user.getFullName(), user.getPersonnelCode());
    }

    /**
     * The instant the date-range filter matched on, mirroring the COALESCE in the repository
     * query. Published so a consumer can see exactly which timestamp put a row in its window
     * without having to reimplement the rule per status.
     */
    static Long finalizedAt(LogSheet sheet) {
        if (sheet.getCompletedAt() != null) {
            return sheet.getCompletedAt();
        }
        if (sheet.getExpiredAt() != null) {
            return sheet.getExpiredAt();
        }
        return sheet.getCancelledAt();
    }

    /** Epoch millis to ISO-8601 UTC, e.g. {@code 2026-08-19T07:12:33Z}. Null stays null. */
    static String iso(Long epochMillis) {
        return epochMillis == null
                ? null
                : DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
    }

    /** Exposed for the tests that assert the exposable set has not silently widened. */
    static Set<LogSheetStatus> exposableStatuses() {
        return IntegrationLogSheetQuery.EXPOSABLE_STATUSES;
    }
}
