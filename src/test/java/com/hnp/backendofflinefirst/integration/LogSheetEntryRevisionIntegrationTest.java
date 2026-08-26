package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.AttachmentKind;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetEntryRevision;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRevisionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetEntryRevisionService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reading a correction replaced.
 *
 * <p><b>The hole this closes.</b> Until V4, a supervisor reopening a delivered round and editing
 * an entry destroyed the operator's measurement with no trace anywhere on the server. The entry
 * kept only the new value, with {@code entry_source}, {@code filled_by_user_id} and
 * {@code updated_at} moved to the supervisor — attribution standing over a value nobody could
 * see any more. The generic audit trail did not cover it and could not: {@code LogSheetEntry} is
 * excluded from the aspect, and {@code AuditEntitySupport.auditFields()} skips every Map field,
 * so {@code form_data} would never have appeared in a diff.
 *
 * <p><b>The shape.</b> Each row holds the value that was <em>replaced</em>; the current value
 * stays in {@code log_sheet_entries}. So an entry's history is its revisions in id order followed
 * by the entry itself, with no duplication — and filling an empty entry writes nothing at all,
 * which is what keeps the table proportional to corrections rather than to readings.
 */
@Transactional
class LogSheetEntryRevisionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetService logSheetService;
    @Autowired LogSheetEntryRevisionRepository revisionRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired LogSheetEntryRevisionService revisionService;
    @Autowired AttachmentRepository attachmentRepository;

    // -----------------------------------------------------------------------
    // When a row is written, and when it is not
    // -----------------------------------------------------------------------

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"CAP:LOGSHEET_COMPLETE_WEB_ANY"})
    void fillingAnEmptyEntryWritesNoRevision() {
        Fixture f = seed();

        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "10")));

        assertThat(revisionRepository.countByLogSheetId(f.sheetId())).isZero();
        assertThat(entryFor(f).getFormData()).containsEntry("temp", "10");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"CAP:LOGSHEET_COMPLETE_WEB_ANY"})
    void overwritingAnAnsweredEntryKeepsWhatItReplaced() {
        Fixture f = seed();
        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "10")));

        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "99")));

        List<LogSheetEntryRevision> history = revisionsFor(f);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getFormData()).containsEntry("temp", "10");
        assertThat(history.get(0).getSupersededSource()).isEqualTo(ActionSource.WEB);
        assertThat(history.get(0).getSheetStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        // ...and the live row holds the new one.
        assertThat(entryFor(f).getFormData()).containsEntry("temp", "99");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"CAP:LOGSHEET_COMPLETE_WEB_ANY"})
    void resavingTheSameValueWritesNothing() {
        // The web fill form posts every entry on every save, including ones nobody touched.
        // Without this the table would grow by one row per asset per save — and each row would
        // claim a correction that never happened.
        Fixture f = seed();
        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "10")));

        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "10")));

        assertThat(revisionsFor(f)).hasSize(0);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"CAP:LOGSHEET_COMPLETE_WEB_ANY"})
    void clearingAnAnsweredEntryIsAlsoAnOverwrite() {
        // Removing a reading destroys it exactly as surely as replacing it does.
        Fixture f = seed();
        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "10")));

        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "")));

        assertThat(revisionsFor(f)).hasSize(1);
        assertThat(revisionsFor(f).get(0).getFormData()).containsEntry("temp", "10");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"CAP:LOGSHEET_COMPLETE_WEB_ANY"})
    void successiveCorrectionsStackOldestFirst() {
        Fixture f = seed();
        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "10")));
        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "20")));
        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "30")));

        assertThat(revisionsFor(f))
                .extracting(r -> r.getFormData().get("temp"))
                .containsExactly("10", "20");
        assertThat(entryFor(f).getFormData()).containsEntry("temp", "30");
    }

    // -----------------------------------------------------------------------
    // What each row carries
    // -----------------------------------------------------------------------

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"CAP:LOGSHEET_COMPLETE_WEB_ANY"})
    void therevisionNamesWhoRecordedTheOldValueAndWhoReplacedIt() {
        Fixture f = seed();
        // Stage an operator's reading directly, so the two people involved are different.
        LogSheetEntry entry = entryFor(f);
        entry.setFormData(Map.of("temp", 42));
        entry.setMaxSeverity("OK");
        entry.setEntrySource(LogSheetEntrySource.PWA_NFC);
        entry.setFilledByUserId(f.operatorId());
        entry.setCreatedAt(1_000L);
        entry.setUpdatedAt(2_000L);
        logSheetEntryRepository.saveAndFlush(entry);

        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "99")));

        LogSheetEntryRevision revision = revisionsFor(f).get(0);
        assertThat(revision.getRecordedByUserId()).isEqualTo(f.operatorId());
        assertThat(revision.getEntrySource()).isEqualTo(LogSheetEntrySource.PWA_NFC);
        assertThat(revision.getMaxSeverity()).isEqualTo("OK");
        // The device time of the replaced reading — when somebody was at the equipment, not when
        // the correction was typed.
        assertThat(revision.getRecordedAt()).isEqualTo(2_000L);
        assertThat(revision.getSupersededAt()).isNotNull();
        assertThat(revision.getSupersededByUserId()).isNotNull();
        assertThat(revision.getSupersededByUserId()).isNotEqualTo(f.operatorId());
        assertThat(revision.getAssetId()).isEqualTo(f.assetId());
        assertThat(revision.getLogSheetId()).isEqualTo(f.sheetId());
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"CAP:LOGSHEET_COMPLETE_WEB_ANY"})
    void theStoredCopyIsIndependentOfTheLiveRow() {
        // The caller hands the entry a new map immediately afterwards, and some paths reuse the
        // instance. Holding a reference rather than a copy would silently record the *new* value
        // as the old one — a history that agrees with the present is worse than none.
        Fixture f = seed();
        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "10")));
        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "99")));

        assertThat(revisionsFor(f).get(0).getFormData()).containsEntry("temp", "10");
        assertThat(revisionsFor(f).get(0).getFormData()).doesNotContainEntry("temp", "99");
    }

    // -----------------------------------------------------------------------
    // What a deleted attachment leaves behind
    // -----------------------------------------------------------------------

    /**
     * The reason the snapshot column exists.
     *
     * <p>A superseded value holds attachment <b>ids</b>, and {@code AttachmentService.delete}
     * removes the row and the bytes outright. Without this, the id resolves to nothing and the
     * history panel can only say «فایل پیوست در دسترس نیست» — which reads exactly like storage
     * having lost the file, and says nothing about what the removed evidence was.
     *
     * <p>The delete here is the real scenario, not a contrivance: it is what the supervisor's
     * correction does to the photo it replaced.
     */
    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"CAP:LOGSHEET_COMPLETE_WEB_ANY"})
    void aRevisionKeepsWhatItsAttachmentsWereEvenAfterTheyAreDeleted() {
        Fixture f = seed();
        String attachmentId = UUID.randomUUID().toString();
        stageAttachment(f, attachmentId, AttachmentKind.AUDIO, 40_960L, 20_000L);

        LogSheetEntry entry = entryFor(f);
        entry.setFormData(Map.of("voice", List.of(attachmentId)));
        entry.setUpdatedAt(2_000L);
        logSheetEntryRepository.saveAndFlush(entry);

        revisionService.recordSupersededValue(entry, logSheetRepository.findById(f.sheetId()).orElseThrow(),
                1L, ActionSource.WEB, System.currentTimeMillis());

        // The correction removes the file, exactly as the real path does.
        attachmentRepository.deleteById(attachmentId);
        attachmentRepository.flush();

        LogSheetEntryRevision revision = revisionsFor(f).getFirst();
        assertThat(attachmentRepository.findById(attachmentId)).isEmpty();
        assertThat(revision.getAttachmentSnapshot()).containsKey(attachmentId);
        Map<String, Object> meta = revision.getAttachmentSnapshot().get(attachmentId);
        assertThat(meta).containsEntry("kind", "AUDIO");
        // Numbers survive the JSONB round trip as numbers, whatever their Java type.
        assertThat(((Number) meta.get("sizeBytes")).longValue()).isEqualTo(40_960L);
        assertThat(((Number) meta.get("durationMs")).longValue()).isEqualTo(20_000L);
        assertThat(meta).containsEntry("mimeType", "audio/webm");
        assertThat(((Number) meta.get("uploadedAt")).longValue()).isPositive();
    }

    /**
     * A numeric correction is the overwhelming majority of rows. Writing a column of empty
     * objects on every one of them would be pure noise in the one table whose readability is the
     * point.
     */
    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"CAP:LOGSHEET_COMPLETE_WEB_ANY"})
    void aRevisionWithNoAttachmentsCarriesNoSnapshot() {
        Fixture f = seed();
        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "10")));
        logSheetService.saveDraftFromWeb(f.sheetId(), values(f, Map.of("temp", "99")));

        assertThat(revisionsFor(f).getFirst().getAttachmentSnapshot()).isNull();
    }

    /**
     * An id whose row was already gone before this correction. This revision is not the place
     * that lost it, so it records nothing about it rather than an empty entry that would read as
     * "deleted here".
     */
    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"CAP:LOGSHEET_COMPLETE_WEB_ANY"})
    void anIdWithNoRowIsNotInventedIntoTheSnapshot() {
        Fixture f = seed();
        LogSheetEntry entry = entryFor(f);
        entry.setFormData(Map.of("voice", List.of(UUID.randomUUID().toString())));
        logSheetEntryRepository.saveAndFlush(entry);

        revisionService.recordSupersededValue(entry, logSheetRepository.findById(f.sheetId()).orElseThrow(),
                1L, ActionSource.WEB, System.currentTimeMillis());

        assertThat(revisionsFor(f).getFirst().getAttachmentSnapshot()).isNull();
    }

    private void stageAttachment(Fixture f, String id, AttachmentKind kind, long sizeBytes, Long durationMs) {
        Attachment a = new Attachment();
        a.setId(id);
        a.setLogSheetId(f.sheetId());
        a.setAssetId(f.assetId());
        a.setFieldKey("voice");
        a.setKind(kind);
        a.setMimeType("audio/webm");
        a.setSizeBytes(sizeBytes);
        a.setDurationMs(durationMs);
        a.setStorageKey("test/" + id);
        a.setUploadedAt(System.currentTimeMillis());
        a.setCreatedByUserId(f.operatorId());
        attachmentRepository.saveAndFlush(a);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private record Fixture(Long sheetId, Long entryId, Long assetId, Long operatorId) {}

    private LogSheetEntry entryFor(Fixture f) {
        return logSheetEntryRepository.findById(f.entryId()).orElseThrow();
    }

    private List<LogSheetEntryRevision> revisionsFor(Fixture f) {
        return revisionRepository.findByLogSheetEntryIdOrderByIdAsc(f.entryId());
    }

    /** The web fill form's shape: {@code {entryId -> {fieldKey -> value}}}. */
    private Map<String, Map<String, Object>> values(Fixture f, Map<String, Object> formData) {
        return Map.of(String.valueOf(f.entryId()), formData);
    }

    private Fixture seed() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("REV-BU-" + nano);
        unit.setName("Revision Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("REV-LOC-" + nano);
        location.setName("Revision Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("REV-SF-" + nano);
        subFunction.setName("Revision Sub");
        subFunction.setTag("NFC-REV-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Revision Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        FieldDefinition def = new FieldDefinition();
        def.setClassId(assetClass.getId());
        def.setKey("temp");
        def.setLabel("Temperature");
        def.setDataType("text");
        def.setRequired(false);
        def.setOrder(1);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(def);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("REV-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.saveAndFlush(asset);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Revision Template " + nano);
        template.setScopeType(AssetHierarchyService.SCOPE_LOCATION);
        template.setScopeId(location.getId());
        template.setClassId(assetClass.getId());
        template.setOperationalUnitId(unit.getId());
        template.setGenerationMode(GenerationMode.MANUAL);
        template.setScheduleActive(false);
        template.setActive(true);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.saveAndFlush(template);

        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, now);

        User operator = operator(unit.getId(), nano);
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(now);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        logSheetRepository.saveAndFlush(sheet);

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheet.getId()).get(0);
        return new Fixture(sheet.getId(), entry.getId(), entry.getAssetId(), operator.getId());
    }

    private User operator(Long unitId, long nano) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername("rev-op-" + nano);
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Revision Operator");
        user.setPasswordHash("{noop}x");
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.saveAndFlush(user);

        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(roleRepository.findByCode("OPERATOR").orElseThrow().getId());
        userRoleRepository.saveAndFlush(userRole);

        UnitOperator link = new UnitOperator();
        link.setUnitId(unitId);
        link.setUserId(user.getId());
        unitOperatorRepository.saveAndFlush(link);
        return user;
    }
}
