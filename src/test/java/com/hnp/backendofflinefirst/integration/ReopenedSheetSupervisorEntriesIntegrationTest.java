package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.TestPrincipals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reopened-sheet handover, replayed end to end.
 *
 * <h2>What went wrong in the field</h2>
 *
 * <p>Operator fills three assets on the tablet and syncs. A supervisor reopens the sheet, claims
 * it, fills two more assets in the browser, then reopens it again and hands it back to the same
 * operator. The operator's PWA shows only their own three, and their next submit blanks the
 * supervisor's two. Log sheet 85 in the field database still carries the evidence: rows with
 * {@code entry_source = WEB} and a {@code filled_by_user_id}, holding nothing.
 *
 * <h2>Three defects, and each test below pins one</h2>
 *
 * <ol>
 *   <li>The web fill form posted every entry of the sheet on every save, and the save path stored
 *       what it was posted — so one supervisor save wrote {@code {"Bar": "", "Status": ""}} onto
 *       every asset in the sheet, including the ones nobody had opened. (The page now edits one
 *       asset at a time and posts only that asset, so it can no longer reach an asset nobody
 *       opened. The guard below stays: it is what stopped this, the mobile path still sends the
 *       whole device state, and a single dialog can still send a cleared field as an empty
 *       string.)</li>
 *   <li>The PWA merge then asked "does the local copy have any <em>keys</em>?" rather than any
 *       <em>values</em>, so those blanks counted as local work and beat the server forever.
 *       (Pinned on the device side by {@code mergeLogSheetBundle.test.ts}.)</li>
 *   <li>The mobile merge wrote whatever the device sent, for every asset, with no check that the
 *       device had ever seen what it was overwriting.</li>
 * </ol>
 *
 * <p>These tests drive the real service methods, not the repositories, because defect 1 and
 * defect 3 both live in "what the service does with a payload that covers the whole sheet" —
 * which is invisible if the test writes entries directly.
 */
class ReopenedSheetSupervisorEntriesIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetService logSheetService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ────────────────────────────────────────────── defect 1: the web save

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void webSaveLeavesUntouchedAssetsEmptyInsteadOfWritingBlankKeys() {
        Fixture f = seedSheetWithTwoAssets();
        authenticateSupervisor(f.supervisorId());

        // Exactly what the fill form posts: every entry of the sheet, one of them answered.
        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryAId()), answers("7", "OFF"),
                String.valueOf(f.entryBId()), answers("", "")));

        assertThat(formDataOf(f.entryAId())).containsExactly(
                Map.entry("Bar", "7"), Map.entry("Status", "OFF"));
        // The whole point. Before the fix this was {"Bar": "", "Status": ""}, and from that
        // moment the device's merge could never take a server value for this asset again.
        assertThat(formDataOf(f.entryBId())).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void webSaveDropsOnlyTheBlankedFieldAndKeepsTheAnsweredOnes() {
        Fixture f = seedSheetWithTwoAssets();
        authenticateSupervisor(f.supervisorId());

        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryAId()), answers("7", "OFF")));
        // Clearing one field must still clear it — the fix drops unanswered keys, it does not
        // freeze answers in place.
        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryAId()), answers("7", "   ")));

        assertThat(formDataOf(f.entryAId())).containsExactly(Map.entry("Bar", "7"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void webSaveDoesNotAttributeAnEntryItLeftEmpty() {
        Fixture f = seedSheetWithTwoAssets();
        authenticateSupervisor(f.supervisorId());

        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryBId()), answers("", "")));

        LogSheetEntry b = logSheetEntryRepository.findById(f.entryBId()).orElseThrow();
        // An entry credited to someone, holding nothing, is what the field rows look like after
        // this bug destroyed their values. A save that writes nothing must not create one.
        assertThat(b.getFilledByUserId()).isNull();
        assertThat(b.getEntrySource()).isNull();
        assertThat(b.getCreatedAt()).isNull();
    }

    // ──────────────────────────────────────── defect 3: the stale mobile blank

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleDeviceCannotBlankAnAnswerItNeverSaw() {
        Fixture f = seedSheetWithTwoAssets();

        // The supervisor answers asset B in the browser, after this device last synced.
        authenticateSupervisor(f.supervisorId());
        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryBId()), answers("6", "ON")));
        SecurityContextHolder.clearContext();

        reopenAndAssignTo(f.sheetId(), f.operatorId());

        // The device submits everything it holds: its own answers for A, and — for B — the empty
        // entry and null timestamps it was given before the supervisor ever touched it.
        authenticateOperator(f.operatorId());
        LogSheetSubmitResult result = submit(f, "stale-device",
                entryDto(f.assetAId(), Map.of("Bar", "7", "Status", "OFF"), null, null),
                entryDto(f.assetBId(), Map.of("Bar", "", "Status", ""), null, null));

        assertThat(result.getOutcome()).isEqualTo("SUBMITTED");
        assertThat(formDataOf(f.entryAId())).containsEntry("Bar", "7");
        // Survives. This is the assertion the whole change exists for.
        assertThat(formDataOf(f.entryBId()))
                .containsExactly(Map.entry("Bar", "6"), Map.entry("Status", "ON"));

        LogSheetEntry b = logSheetEntryRepository.findById(f.entryBId()).orElseThrow();
        assertThat(b.getFilledByUserId()).isEqualTo(f.supervisorId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anUpToDateDeviceMayStillClearAnAnswer() {
        Fixture f = seedSheetWithTwoAssets();

        authenticateSupervisor(f.supervisorId());
        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryBId()), answers("6", "ON")));
        SecurityContextHolder.clearContext();

        reopenAndAssignTo(f.sheetId(), f.operatorId());

        // A device that HAS seen this answer echoes the entry's own timestamps back. Clearing is
        // then a deliberate act by somebody looking at the value, and must go through — the
        // guard refuses stale blanks, not blanks.
        LogSheetEntry stored = logSheetEntryRepository.findById(f.entryBId()).orElseThrow();
        authenticateOperator(f.operatorId());
        LogSheetSubmitResult result = submit(f, "informed-clear",
                entryDto(f.assetBId(), Map.of("Bar", "", "Status", ""),
                        stored.getCreatedAt(), stored.getUpdatedAt()));

        assertThat(result.getOutcome()).isEqualTo("SUBMITTED");
        assertThat(formDataOf(f.entryBId())).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aStaleDeviceMayStillWriteItsOwnAnswers() {
        Fixture f = seedSheetWithTwoAssets();

        authenticateSupervisor(f.supervisorId());
        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryBId()), answers("6", "ON")));
        SecurityContextHolder.clearContext();

        reopenAndAssignTo(f.sheetId(), f.operatorId());

        // Only the destructive direction is refused. Two answers in conflict stay last-writer-
        // wins at entry level — a knowing decision: field-level merging would settle this case
        // too, and is a far larger change for a far rarer conflict.
        authenticateOperator(f.operatorId());
        submit(f, "operator-rewrites",
                entryDto(f.assetBId(), Map.of("Bar", "9", "Status", "OFF"), null, null));

        assertThat(formDataOf(f.entryBId()))
                .containsExactly(Map.entry("Bar", "9"), Map.entry("Status", "OFF"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aBlankFromADeviceIsFineWhenNothingIsStored() {
        Fixture f = seedSheetWithTwoAssets();
        reopenAndAssignTo(f.sheetId(), f.operatorId());

        // Nothing to destroy, so nothing to refuse: the ordinary case of an operator submitting
        // a sheet with assets they did not reach. It must not become an error or a skipped row.
        authenticateOperator(f.operatorId());
        LogSheetSubmitResult result = submit(f, "nothing-stored",
                entryDto(f.assetAId(), Map.of("Bar", "7", "Status", "OFF"), null, null),
                entryDto(f.assetBId(), Map.of("Bar", "", "Status", ""), null, null));

        assertThat(result.getOutcome()).isEqualTo("SUBMITTED");
        assertThat(formDataOf(f.entryAId())).containsEntry("Bar", "7");
        assertThat(formDataOf(f.entryBId())).isEmpty();
    }

    // ─────────────────────────────────────────────────── the whole sequence

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theFullHandoverKeepsBothOperatorsWork() {
        Fixture f = seedSheetWithTwoAssets();

        // 1. Operator answers asset A on the tablet and syncs. Asset B goes up blank, because a
        //    mobile submit always carries every entry on the device.
        authenticateOperator(f.operatorId());
        assertThat(submit(f, "round-one",
                entryDto(f.assetAId(), Map.of("Bar", "7", "Status", "OFF"), null, null),
                entryDto(f.assetBId(), Map.of("Bar", "", "Status", ""), null, null))
                .getOutcome()).isEqualTo("SUBMITTED");
        SecurityContextHolder.clearContext();

        // 2. Supervisor reopens, takes it over, and answers asset B in the browser.
        reopenAndAssignTo(f.sheetId(), f.supervisorId());
        authenticateSupervisor(f.supervisorId());
        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryAId()), answers("7", "OFF"),
                String.valueOf(f.entryBId()), answers("6", "ON")));
        SecurityContextHolder.clearContext();

        // 3. Handed back to the operator, whose device still holds round one.
        reopenAndAssignTo(f.sheetId(), f.operatorId());
        authenticateOperator(f.operatorId());
        assertThat(submit(f, "round-two",
                entryDto(f.assetAId(), Map.of("Bar", "7", "Status", "OFF"), null, null),
                entryDto(f.assetBId(), Map.of("Bar", "", "Status", ""), null, null))
                .getOutcome()).isEqualTo("SUBMITTED");

        // Both operators' work is on the sheet, and each asset still names who answered it.
        assertThat(formDataOf(f.entryAId()))
                .containsExactly(Map.entry("Bar", "7"), Map.entry("Status", "OFF"));
        assertThat(formDataOf(f.entryBId()))
                .containsExactly(Map.entry("Bar", "6"), Map.entry("Status", "ON"));
        assertThat(logSheetEntryRepository.findById(f.entryAId()).orElseThrow().getFilledByUserId())
                .isEqualTo(f.operatorId());
        assertThat(logSheetEntryRepository.findById(f.entryBId()).orElseThrow().getFilledByUserId())
                .isEqualTo(f.supervisorId());
    }

    // ─────────────────────────────────────────────────────────── helpers

    private Map<String, Object> answers(String bar, String status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("Bar", bar);
        values.put("Status", status);
        return values;
    }

    private Map<String, Object> formDataOf(Long entryId) {
        return logSheetEntryRepository.findById(entryId).orElseThrow().getFormData();
    }

    private LogSheetEntryDto entryDto(Long assetId, Map<String, Object> formData,
                                      Long createdAt, Long updatedAt) {
        LogSheetEntryDto dto = new LogSheetEntryDto();
        dto.setAssetId(assetId);
        dto.setFormData(formData);
        dto.setCreatedAt(createdAt);
        dto.setUpdatedAt(updatedAt);
        return dto;
    }

    private LogSheetSubmitResult submit(Fixture f, String actionSuffix, LogSheetEntryDto... entries) {
        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(f.sheetId());
        dto.setLocalId("local-" + actionSuffix);
        dto.setCompletedAt(System.currentTimeMillis());
        dto.setClientActionId("client-" + actionSuffix + "-" + f.sheetId());
        dto.setEntries(List.of(entries));
        return logSheetService.submitBatch(List.of(dto)).getFirst();
    }

    /** What a supervisor's reopen leaves behind: open again, and owned by someone. */
    private void reopenAndAssignTo(Long sheetId, Long userId) {
        LogSheet sheet = logSheetRepository.findById(sheetId).orElseThrow();
        long now = System.currentTimeMillis();
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setAssigneeUserId(userId);
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setCompletedAt(null);
        sheet.setCompletedByUserId(null);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setUpdatedAt(now);
        logSheetRepository.saveAndFlush(sheet);
    }

    private void authenticateOperator(Long userId) {
        authenticate(userId, Set.of("OPERATOR"), Set.of());
    }

    private void authenticateSupervisor(Long userId) {
        // The capability, not the role code — a supervisor reaches the web fill form through
        // LOGSHEET_COMPLETE_WEB_ANY, and granting it explicitly keeps this test independent of
        // which system role happens to carry it today.
        authenticate(userId, Set.of("SUPERVISOR"), Set.of(Capabilities.LOGSHEET_COMPLETE_WEB_ANY));
    }

    private void authenticate(Long userId, Set<String> roles, Set<String> authorities) {
        User user = userRepository.findById(userId).orElseThrow();
        AppUserDetails principal = TestPrincipals.of(user, roles, authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Fixture seedSheetWithTwoAssets() {
        long now = System.currentTimeMillis();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("REOPEN-BU-" + now);
        unit.setName("Reopen Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("REOPEN-LOC-" + now);
        location.setName("Reopen Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Reopen Pump " + now);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);
        saveField(assetClass.getId(), "Bar", "number", 1, now);
        saveField(assetClass.getId(), "Status", "text", 2, now);

        AssetEntry assetA = saveAsset(location.getId(), assetClass.getId(), "A", now);
        AssetEntry assetB = saveAsset(location.getId(), assetClass.getId(), "B", now);

        User operator = createUser(unit.getId(), "reopen-op-" + now, "OPERATOR");
        User supervisor = createUser(unit.getId(), "reopen-sup-" + now, "SUPERVISOR");

        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("Reopen Round");
        sheet.setScopeSummary("location:" + location.getId());
        sheet.setOperationalUnitId(unit.getId());
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SELF_CLAIMED);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setClaimedAt(now - 60_000L);
        sheet.setStartedAt(now - 60_000L);
        sheet.setCreatedAt(now - 60_000L);
        sheet.setUpdatedAt(now - 60_000L);
        sheet = logSheetRepository.saveAndFlush(sheet);

        Long entryA = saveEntry(sheet.getId(), assetA, assetClass.getId());
        Long entryB = saveEntry(sheet.getId(), assetB, assetClass.getId());

        return new Fixture(sheet.getId(), operator.getId(), supervisor.getId(),
                assetA.getId(), assetB.getId(), entryA, entryB);
    }

    private AssetEntry saveAsset(Long locationId, Long classId, String suffix, long now) {
        SubFunction subFunction = new SubFunction();
        subFunction.setCode("REOPEN-SF-" + suffix + "-" + now);
        subFunction.setName("Reopen Sub " + suffix);
        subFunction.setTag("NFC-REOPEN-" + suffix + "-" + now);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, locationId);
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("REOPEN-" + suffix + "-" + now);
        asset.setAssetName("Reopen Pump " + suffix);
        asset.setClassId(classId);
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return assetEntryRepository.saveAndFlush(asset);
    }

    private Long saveEntry(Long sheetId, AssetEntry asset, Long classId) {
        LogSheetEntry entry = new LogSheetEntry();
        entry.setLogSheetId(sheetId);
        entry.setAssetId(asset.getId());
        entry.setAssetName(asset.getAssetName());
        entry.setClassId(classId);
        // Generated sheets start empty. That is the invariant this whole change restores.
        entry.setFormData(new HashMap<>());
        return logSheetEntryRepository.saveAndFlush(entry).getId();
    }

    private void saveField(Long classId, String key, String dataType, int order, long now) {
        FieldDefinition field = new FieldDefinition();
        field.setClassId(classId);
        field.setKey(key);
        field.setLabel(key);
        field.setDataType(dataType);
        field.setRequired(false);
        field.setOrder(order);
        field.setVersion(1);
        field.setDeleted(false);
        field.setSynced(false);
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(field);
    }

    private User createUser(Long unitId, String username, String roleCode) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username);
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName(username);
        user.setPasswordHash(passwordEncoder.encode("pw123456"));
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.saveAndFlush(user);

        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(roleRepository.findByCode(roleCode).orElseThrow().getId());
        userRoleRepository.saveAndFlush(userRole);

        UnitOperator link = new UnitOperator();
        link.setUnitId(unitId);
        link.setUserId(user.getId());
        unitOperatorRepository.saveAndFlush(link);
        return user;
    }

    private record Fixture(Long sheetId, Long operatorId, Long supervisorId,
                           Long assetAId, Long assetBId, Long entryAId, Long entryBId) {}
}
