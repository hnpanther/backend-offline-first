package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetStatusRequestStatus;
import com.hnp.backendofflinefirst.domain.AssetStatusSource;
import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.repository.*;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.TestPrincipals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who sees which asset-status requests, and the ceiling that used to decide it.
 *
 * <h2>The defect</h2>
 *
 * <p>The queue was scoped by materialising the caller's reportable asset ids —
 * {@code findReportableAssets(null, PageRequest.of(0, 5000))} — and passing them to the query as
 * an {@code IN} list. Past 5000 assets the list was <b>truncated</b>, so requests for the assets
 * beyond the cut were absent from the queue on every page, with no error, no warning and no way
 * for the reader to know a row was missing. The underlying query carries no {@code ORDER BY}
 * either, so <em>which</em> 5000 survived was never defined.
 *
 * <p>Pagination does not help and never could: the cap is on the filter, computed whole before
 * the first row is fetched, not on the result. Raising it is not possible either — a real
 * installation here has 200,000 assets, and PostgreSQL accepts 65,535 bind parameters.
 *
 * <p>The scope now resolves inside the statement, through the same
 * {@code REPORTABLE_ASSETS_CTE} that {@code AssetAccessService.findReportableAssets} uses, so
 * "what this page lists" and "what this user may act on" remain one rule.
 *
 * <h2>What these cases are really guarding</h2>
 *
 * <p>Removing a cap can only widen what a query returns, and on a list whose contents are an
 * access decision that is the dangerous direction. So the scope assertions come first and are
 * the reason this class exists: the queue must show <b>more of the caller's own units</b> and
 * not one row more than that. The equivalence case guards the second risk — there are now two
 * query definitions, one JPQL for unrestricted callers and one native for scoped ones, and they
 * must not drift.
 */
class AssetStatusRequestScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AssetStatusChangeRequestRepository requestRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired LocationUnitRepository locationUnitRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired UserRepository userRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;

    private static final String VIEW = "GET:/asset-status-requests";

    Long unitAId;
    Long unitBId;
    Long locAId;
    Long assetInA;
    Long assetInB;
    Long requestInA;
    Long requestInB;
    User supervisorOfA;
    User supervisorOfNothing;
    long nano;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        nano = System.nanoTime();

        OperationalUnit unitA = unit("ASR-OU-A-" + nano);
        OperationalUnit unitB = unit("ASR-OU-B-" + nano);
        unitAId = unitA.getId();
        unitBId = unitB.getId();

        locAId = location("ASR-LOC-A-" + nano, unitA.getId());
        Long locB = location("ASR-LOC-B-" + nano, unitB.getId());

        assetInA = asset("ASR-AST-A-" + nano, subFunction("ASR-SF-A-" + nano, locAId));
        assetInB = asset("ASR-AST-B-" + nano, subFunction("ASR-SF-B-" + nano, locB));

        supervisorOfA = user("asr-sup-a-" + nano);
        supervise(supervisorOfA, unitA.getId());
        supervisorOfNothing = user("asr-sup-none-" + nano);

        requestInA = request(assetInA, AssetStatusRequestStatus.PENDING,
                "نشتی از آب‌بند", supervisorOfA.getId());
        requestInB = request(assetInB, AssetStatusRequestStatus.PENDING,
                "لرزش غیرعادی", supervisorOfA.getId());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── the access guarantees, which removing a cap could only have loosened ─────────────────

    @Test
    void aSupervisorSeesTheRequestsOfTheirOwnUnit() throws Exception {
        assertThat(idsVisibleTo(supervisorOfA)).contains(requestInA);
    }

    @Test
    void aSupervisorNeverSeesAnotherUnitsRequest() throws Exception {
        // The one that must never break. Everything else here is correctness; this is access.
        assertThat(idsVisibleTo(supervisorOfA))
                .as("unit B is not this supervisor's to see")
                .doesNotContain(requestInB);
    }

    @Test
    void anUnrestrictedAdminSeesEveryUnitsRequests() throws Exception {
        assertThat(idsVisibleTo(admin())).contains(requestInA, requestInB);
    }

    @Test
    void aUserScopedToNoUnitsSeesNothing() throws Exception {
        // `visibleUnitIds()` answers null for an unrestricted admin and an EMPTY set for this
        // user, and the two mean opposite things. Reading the second as the first shows them the
        // whole plant; letting it reach the query produces `IN ()`, which is not valid SQL.
        assertThat(idsVisibleTo(supervisorOfNothing)).isEmpty();
    }

    @Test
    void anAssetReachableOnlyThroughALogSheetIsStillInScope() throws Exception {
        // The reporting scope is deliberately WIDER than location ownership: responsibility
        // arrives through the log sheet, and a template with restrict_scope_to_unit = false puts
        // another unit's assets on this unit's round. That second branch of the CTE is easy to
        // drop when rewriting the query, and dropping it would hide the readings of work the
        // supervisor had just required.
        Long strangerAsset = asset("ASR-AST-FAR-" + nano,
                subFunction("ASR-SF-FAR-" + nano, location("ASR-LOC-FAR-" + nano, unitBId)));
        onASheetOfUnit(unitAId, strangerAsset);
        Long strangerRequest = request(strangerAsset, AssetStatusRequestStatus.PENDING,
                "از راه لاگ‌شیت", supervisorOfA.getId());

        assertThat(idsVisibleTo(supervisorOfA)).contains(strangerRequest);
    }

    // ── the ceiling ─────────────────────────────────────────────────────────────────────────

    @Test
    void aScopeLargerThanTheOldFiveThousandCeilingIsNotTruncated() throws Exception {
        // Seeded in two statements rather than five thousand round trips — the point is the size
        // of the scope, and building it through the service layer would cost minutes for nothing.
        // Its own unit and location, and deleted again below, because the container is shared by
        // the whole suite and 5000 stray assets would follow every later test around.
        OperationalUnit bigUnit = unit("ASR-OU-BIG-" + nano);
        Long bigLoc = location("ASR-LOC-BIG-" + nano, bigUnit.getId());
        User supervisorOfBig = user("asr-sup-big-" + nano);
        supervise(supervisorOfBig, bigUnit.getId());
        String tagPrefix = "ASR-BULK-" + nano;

        try {
            bulkAssets(bigLoc, tagPrefix, 5200);
            Long lastAsset = jdbc.queryForObject(
                    "SELECT max(a.id) FROM asset_entries a JOIN sub_functions s ON s.id = a.sub_function_id"
                            + " WHERE s.code LIKE ?", Long.class, tagPrefix + "-%");
            assertThat(lastAsset).isNotNull();
            Long beyondTheCap = request(lastAsset, AssetStatusRequestStatus.PENDING,
                    "پشت سقف قدیمی", supervisorOfBig.getId());

            assertThat(idsVisibleTo(supervisorOfBig))
                    .as("5200 assets in scope; the request on the last one must still be listed")
                    .contains(beyondTheCap);
        } finally {
            jdbc.update("DELETE FROM asset_status_change_requests WHERE asset_id IN"
                    + " (SELECT a.id FROM asset_entries a JOIN sub_functions s ON s.id = a.sub_function_id"
                    + "  WHERE s.code LIKE ?)", tagPrefix + "-%");
            jdbc.update("DELETE FROM asset_entries WHERE sub_function_id IN"
                    + " (SELECT id FROM sub_functions WHERE code LIKE ?)", tagPrefix + "-%");
            jdbc.update("DELETE FROM sub_functions WHERE code LIKE ?", tagPrefix + "-%");
        }
    }

    // ── the header figure, which was never scoped at all ────────────────────────────────────

    @Test
    void thePendingCountIsScopedLikeTheListUnderIt() throws Exception {
        // It used to be `countByStatus(PENDING)` with no scope whatsoever, so a supervisor of one
        // unit was shown the whole plant's backlog above a list that could never contain it. The
        // number is also a disclosure: how much is happening in units the reader cannot see.
        long plantWide = requestRepository.countByStatus(AssetStatusRequestStatus.PENDING);
        assertThat(plantWide)
                .as("the fixture must make the unscoped and scoped answers differ, or this proves nothing")
                .isGreaterThan(1);

        Map<String, Object> model = pageFor(supervisorOfA);

        assertThat((Long) model.get("pendingCount")).isLessThan(plantWide);
        assertThat((Long) model.get("pendingCount"))
                .as("every pending request this supervisor can see, not just the page shown")
                .isEqualTo(idsVisibleTo(supervisorOfA).size());
    }

    // ── the filters, all rewritten from JPQL into SQL ───────────────────────────────────────

    @Test
    void theStatusFilterStillNarrowsTheScopedQueue() throws Exception {
        Long rejected = request(assetInA, AssetStatusRequestStatus.REJECTED, "رد شده", supervisorOfA.getId());

        assertThat(idsVisibleTo(supervisorOfA, "status", "REJECTED")).containsExactly(rejected);
        assertThat(idsVisibleTo(supervisorOfA, "status", "PENDING")).contains(requestInA).doesNotContain(rejected);
    }

    @Test
    void theAssetFilterStillNarrowsTheScopedQueue() throws Exception {
        assertThat(idsVisibleTo(supervisorOfA, "assetId", String.valueOf(assetInA)))
                .containsExactly(requestInA);
    }

    @Test
    void freeTextStillMatchesTheReasonTheAssetAndThePerson() throws Exception {
        // Three of the six branches the rewritten predicate has to keep, chosen because each
        // reaches a different table. A supervisor working the queue searches by what they know.
        assertThat(idsVisibleTo(supervisorOfA, "q", "آب‌بند"))
                .as("the request's own reason").containsExactly(requestInA);
        assertThat(idsVisibleTo(supervisorOfA, "q", "ASR-AST-A-" + nano))
                .as("the asset's code, through a join").containsExactly(requestInA);
        assertThat(idsVisibleTo(supervisorOfA, "q", supervisorOfA.getUsername()))
                .as("the requester's name, through another join").contains(requestInA);
    }

    @Test
    void freeTextThatMatchesNothingReturnsNothingRatherThanEverything() throws Exception {
        // The failure mode of a rewritten OR-chain: one branch always true turns a search into
        // "show me the lot", which looks like a working page.
        assertThat(idsVisibleTo(supervisorOfA, "q", "zzz-no-such-thing-zzz")).isEmpty();
    }

    // ── two query definitions must not drift ────────────────────────────────────────────────

    @Test
    void theScopedAndUnrestrictedQueriesAgreeWhenTheScopeCoversEverything() throws Exception {
        // Unrestricted callers keep the original JPQL and scoped callers get the new native one,
        // so the two can now disagree. Given a supervisor of both units, they must not.
        supervise(supervisorOfA, unitBId);

        List<Long> scoped = idsVisibleTo(supervisorOfA);
        List<Long> unrestricted = idsVisibleTo(admin());

        assertThat(scoped).contains(requestInA, requestInB);
        assertThat(unrestricted).containsAll(scoped);
        assertThat(scoped.stream().sorted().toList())
                .as("same rows, same order rule, for the same filters")
                .isEqualTo(unrestricted.stream().filter(scoped::contains).sorted().toList());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> pageFor(User user, String... params) throws Exception {
        var request = get("/asset-status-requests").with(authentication(tokenFor(user)));
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return result.getModelAndView().getModel();
    }

    @SuppressWarnings("unchecked")
    private List<Long> idsVisibleTo(User user, String... params) throws Exception {
        return ((List<AssetStatusChangeRequest>) pageFor(user, params).get("requests"))
                .stream().map(AssetStatusChangeRequest::getId).toList();
    }

    /**
      * A principal over a <b>real user row</b>, so {@code visibleUnitIds()} resolves the scope
      * from the database rather than from anything this test asserts.
      *
      * <p>Through {@code TestPrincipals}, never the {@code AppUserDetails} constructor: scope is
      * decided by a <em>capability</em> now, not by the role code, and a hand-built principal
      * carries none. Built by hand, the "admin" here came back scoped to nothing and the two
      * unrestricted cases failed against an empty list — which is the helper's own warning,
      * met in practice.
      */
    private Authentication tokenFor(User user) {
        Set<String> roles = user == null ? Set.of("ADMIN") : Set.of("SUPERVISOR");
        User principalUser = user != null ? user : adminRow();
        AppUserDetails principal = TestPrincipals.of(principalUser, roles, Set.of(VIEW));
        return new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
    }

    private User admin() {
        return null;
    }

    private User adminRow() {
        return userRepository.findByUsername("asr-admin-" + nano).orElseGet(() -> user("asr-admin-" + nano));
    }

    /** 5000+ assets in two statements: one sub-function each, because of the active-per-position rule. */
    private void bulkAssets(Long locationId, String prefix, int count) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO sub_functions (code, name, tag, location_id, created_at, updated_at)
                SELECT ? || '-' || g, 'bulk', ? || '-TAG-' || g, ?, ?, ?
                FROM generate_series(1, ?) g
                """, prefix, prefix, locationId, now, now, count);
        jdbc.update("""
                INSERT INTO asset_entries (asset_code, asset_name, sub_function_id, active, created_at, updated_at)
                SELECT ? || '-A-' || sf.id, 'bulk', sf.id, TRUE, ?, ?
                FROM sub_functions sf WHERE sf.code LIKE ?
                """, prefix, now, now, prefix + "-%");
    }

    private void onASheetOfUnit(Long unitId, Long assetId) {
        long now = System.currentTimeMillis();
        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("ASR sheet " + assetId);
        sheet.setOperationalUnitId(unitId);
        sheet.setStatus(com.hnp.backendofflinefirst.domain.LogSheetStatus.PENDING);
        sheet.setOrigin(com.hnp.backendofflinefirst.domain.GenerationMode.MANUAL);
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        sheet = logSheetRepository.saveAndFlush(sheet);

        LogSheetEntry entry = new LogSheetEntry();
        entry.setLogSheetId(sheet.getId());
        entry.setAssetId(assetId);
        entry.setAssetName("far asset");
        logSheetEntryRepository.saveAndFlush(entry);
    }

    private Long request(Long assetId, AssetStatusRequestStatus status, String reason, Long byUserId) {
        long now = System.currentTimeMillis();
        AssetStatusChangeRequest r = new AssetStatusChangeRequest();
        r.setAssetId(assetId);
        r.setRequestedStatus("OUT_OF_SERVICE");
        r.setStatus(status);
        r.setSource(AssetStatusSource.MANUAL);
        r.setReason(reason);
        r.setRequestedByUserId(byUserId);
        r.setRequestedAt(now);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return requestRepository.saveAndFlush(r).getId();
    }

    private OperationalUnit unit(String code) {
        long now = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode(code);
        unit.setName(code);
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        return operationalUnitRepository.saveAndFlush(unit);
    }

    private Long location(String code, Long unitId) {
        long now = System.currentTimeMillis();
        Location location = new Location();
        location.setCode(code);
        location.setName(code);
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        LocationUnit link = new LocationUnit();
        link.setLocationId(location.getId());
        link.setUnitId(unitId);
        locationUnitRepository.saveAndFlush(link);
        return location.getId();
    }

    private Long subFunction(String code, Long locationId) {
        long now = System.currentTimeMillis();
        SubFunction sf = new SubFunction();
        sf.setCode(code);
        sf.setName(code);
        sf.setTag(code + "-TAG");
        sf.setLocationId(locationId);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        return subFunctionRepository.saveAndFlush(sf).getId();
    }

    private Long asset(String code, Long subFunctionId) {
        long now = System.currentTimeMillis();
        AssetEntry asset = new AssetEntry();
        asset.setAssetCode(code);
        asset.setAssetName(code);
        asset.setSubFunctionId(subFunctionId);
        asset.setActive(true);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return assetEntryRepository.saveAndFlush(asset).getId();
    }

    private User user(String username) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username);
        user.setPersonnelCode("PC-" + username);
        user.setFullName(username);
        user.setPasswordHash("{noop}x");
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userRepository.saveAndFlush(user);
    }

    private void supervise(User user, Long unitId) {
        UnitSupervisor link = new UnitSupervisor();
        link.setUnitId(unitId);
        link.setUserId(user.getId());
        unitSupervisorRepository.saveAndFlush(link);
    }
}
