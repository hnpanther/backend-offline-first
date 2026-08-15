package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.service.AssetAccessService;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit scope is now decided by a <b>capability</b>, and this proves it end to end.
 *
 * <p>{@code isUnitScopedOnly()} used to be {@code !hasRole("ADMIN") && !hasRole("HIGH_USER")}.
 * It is now the absence of {@link Capabilities#SCOPE_PLANT_WIDE}. Testing that helper in
 * isolation is not enough: what matters is whether the real queries — the ones that build a
 * `WHERE unit_id IN (…)` filter from it — actually change behaviour. A mistake here does not
 * throw, it quietly shows one unit's assets to another unit's staff.
 *
 * <p>The two directions checked are the two ways it could go wrong:
 * <ul>
 *   <li><b>Fail-open:</b> a custom role holding every endpoint permission but no capability must
 *       still be confined to its own units. This is why the capability is phrased positively.</li>
 *   <li><b>Fail-closed:</b> a role duplicated from ADMIN must see the whole plant, or the
 *       copyability fix is only skin-deep.</li>
 * </ul>
 *
 * <p>The fixture also nests a location under an owned one, so the recursive location walk in
 * {@code AssetUnitScopeSql} is exercised rather than assumed.
 */
class CapabilityScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetAccessService assetAccessService;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired UserRepository userRepository;

    private Long unitAId;
    private User supervisorOfA;
    private String assetInA;
    private String assetInNestedA;
    private String assetInB;

    @BeforeEach
    void seed() {
        long now = System.nanoTime();

        OperationalUnit unitA = unit("CAP-OU-A-" + now);
        OperationalUnit unitB = unit("CAP-OU-B-" + now);
        unitAId = unitA.getId();

        Location locA = location("CAP-LOC-A-" + now, null, unitA.getId());
        // A child of an owned location: only reachable through the recursive loc_tree walk.
        Location locANested = location("CAP-LOC-A-NESTED-" + now, locA.getId(), List.of());
        Location locB = location("CAP-LOC-B-" + now, null, unitB.getId());

        assetInA = asset("CAP-AST-A-" + now, subFunction("CAP-SF-A-" + now, locA.getId()));
        assetInNestedA = asset("CAP-AST-A-NESTED-" + now, subFunction("CAP-SF-A-N-" + now, locANested.getId()));
        assetInB = asset("CAP-AST-B-" + now, subFunction("CAP-SF-B-" + now, locB.getId()));

        supervisorOfA = new User();
        supervisorOfA.setUsername("cap-scope-sup-" + now);
        supervisorOfA.setPersonnelCode("PC-CAP-" + now);
        supervisorOfA.setPasswordHash("x");
        supervisorOfA.setActive(true);
        supervisorOfA = userRepository.save(supervisorOfA);

        UnitSupervisor link = new UnitSupervisor();
        link.setUnitId(unitA.getId());
        link.setUserId(supervisorOfA.getId());
        unitSupervisorRepository.save(link);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void withoutTheCapabilityTheUserIsConfinedToTheirOwnUnit() {
        authenticate(supervisorOfA, Set.of());

        assertThat(assetAccessService.visibleUnitIds())
                .as("no CAP:SCOPE_PLANT_WIDE means a real unit filter, not null")
                .containsExactly(unitAId);
        assertThat(visibleAssetCodes()).contains(assetInA).doesNotContain(assetInB);
    }

    @Test
    void aCustomRoleHoldingEveryEndpointPermissionIsStillConfined() {
        // The fail-open case. Endpoint permissions say which routes you may call; they say
        // nothing about how much of the plant you may see, and must not be mistaken for it.
        List<String> everyEndpointPermission = List.of(
                "GET:/asset-entries", "GET:/asset-entries/export", "GET:/reports",
                "GET:/log-sheets", "GET:/locations", "POST:/asset-entries");
        authenticate(supervisorOfA, Set.copyOf(everyEndpointPermission));

        assertThat(assetAccessService.visibleUnitIds()).containsExactly(unitAId);
        assertThat(visibleAssetCodes()).doesNotContain(assetInB);
    }

    @Test
    void withTheCapabilityTheUserSeesEveryUnit() {
        authenticate(supervisorOfA, Set.of(Capabilities.SCOPE_PLANT_WIDE));

        assertThat(assetAccessService.visibleUnitIds())
                .as("null means unrestricted — the branch every scoped query must special-case")
                .isNull();
        assertThat(visibleAssetCodes()).contains(assetInA, assetInB);
    }

    @Test
    void theCapabilityWorksWhateverTheRoleIsCalled() {
        // A copy of ADMIN carries the capability under its own role code. If scope keyed off the
        // code, this user would be silently confined despite holding everything ADMIN holds.
        authenticate(supervisorOfA, Set.of(Capabilities.SCOPE_PLANT_WIDE), "ZZCOPY-OF-ADMIN");

        assertThat(assetAccessService.visibleUnitIds()).isNull();
        assertThat(visibleAssetCodes()).contains(assetInB);
    }

    @Test
    void beingNamedAdminWithoutTheCapabilityGrantsNothing() {
        // The mirror image, and the reason the guard test forbids role-name checks.
        authenticate(supervisorOfA, Set.of(), "ADMIN");

        assertThat(assetAccessService.visibleUnitIds()).containsExactly(unitAId);
        assertThat(visibleAssetCodes()).doesNotContain(assetInB);
    }

    @Test
    void anAssetUnderANestedLocationIsInScope() {
        // The recursive loc_tree walk: the nested location is attached to no unit of its own and
        // is reachable only as a descendant of the one unit A owns.
        authenticate(supervisorOfA, Set.of());

        assertThat(visibleAssetCodes())
                .as("a child location of an owned location is inside the unit's scope")
                .contains(assetInNestedA);
    }

    @Test
    void aScopedUserWithNoUnitAtAllSeesNothingAndDoesNotFail() {
        // Fail-safe, and specifically not an empty SQL IN () — the services short-circuit.
        User orphan = new User();
        orphan.setUsername("cap-orphan-" + System.nanoTime());
        orphan.setPersonnelCode("PC-ORPH-" + System.nanoTime());
        orphan.setPasswordHash("x");
        orphan.setActive(true);
        orphan = userRepository.save(orphan);
        authenticate(orphan, Set.of());

        assertThat(assetAccessService.visibleUnitIds()).isEmpty();
        assertThat(visibleAssetCodes()).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<String> visibleAssetCodes() {
        return assetAccessService.findVisibleAssets(null, PageRequest.of(0, 200))
                .getContent().stream().map(AssetEntry::getAssetCode).toList();
    }

    private void authenticate(User user, Set<String> authorities) {
        authenticate(user, authorities, "SOME_ROLE");
    }

    private void authenticate(User user, Set<String> authorities, String roleCode) {
        AppUserDetails principal = new AppUserDetails(user, Set.of(roleCode), authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        authorities.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList())));
    }

    private OperationalUnit unit(String code) {
        long now = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode(code);
        unit.setName(code);
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        return operationalUnitRepository.save(unit);
    }

    private Location location(String code, Long parentId, Long unitId) {
        return location(code, parentId, unitId == null ? List.of() : List.of(unitId));
    }

    private Location location(String code, Long parentId, List<Long> unitIds) {
        long now = System.currentTimeMillis();
        Location loc = new Location();
        loc.setCode(code);
        loc.setName(code);
        loc.setParentId(parentId);
        loc.setCreatedAt(now);
        loc.setUpdatedAt(now);
        return hierarchyService.saveLocation(loc, unitIds);
    }

    private Long subFunction(String code, Long locationId) {
        long now = System.currentTimeMillis();
        SubFunction sf = new SubFunction();
        sf.setCode(code);
        sf.setName(code);
        sf.setTag("TAG-" + code);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, locationId);
        return hierarchyService.saveSubFunction(sf).getId();
    }

    private String asset(String code, Long subFunctionId) {
        long now = System.currentTimeMillis();
        AssetEntry asset = new AssetEntry();
        asset.setAssetCode(code);
        asset.setAssetName(code);
        asset.setSubFunctionId(subFunctionId);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        assetEntryRepository.save(asset);
        return code;
    }
}
