package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.dto.BulkDeleteResult;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LocationUnit;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LocationUnitRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.service.OperationalUnitService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deleting operational units, one or many.
 *
 * <h2>The rule, and the one part of it that is easy to get backwards</h2>
 *
 * <p>A unit is refused when something would be left pointing at nothing — a child unit, a
 * location it owns, a template scoped to it, a log sheet raised against it. Each of those is
 * configuration or history somebody would have to reconstruct.
 *
 * <p><b>Supervisors and operators are not such a thing.</b> They are assignments *to* the unit:
 * the rows hold nothing but the pairing, the people are untouched, and a unit that no longer
 * exists has no staff to have. Refusing on their account would mean unassigning everybody by
 * hand first, for no gain. Several tests below exist only to pin that, because "it has people in
 * it, so it must be in use" is the natural reading and it is the wrong one.
 *
 * <p>Not {@code @Transactional}: the bulk path runs each id in its own transaction, which is the
 * behaviour under test, and a surrounding rollback would hide it.
 */
class OperationalUnitBulkDeleteIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired OperationalUnitService operationalUnitService;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired LocationUnitRepository locationUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final List<Long> unitIds = new ArrayList<>();
    private final List<Long> locationIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // Reverse insertion order: a child is always seeded after its parent, so unwinding
        // backwards never leaves a parent referenced by a row that is still there.
        for (Long id : unitIds.reversed()) {
            jdbcTemplate.update("DELETE FROM location_units WHERE unit_id = ?", id);
            jdbcTemplate.update("DELETE FROM unit_supervisors WHERE unit_id = ?", id);
            jdbcTemplate.update("DELETE FROM unit_operators WHERE unit_id = ?", id);
            jdbcTemplate.update("DELETE FROM operational_units WHERE id = ?", id);
        }
        unitIds.clear();
        locationIds.forEach(id -> jdbcTemplate.update("DELETE FROM locations WHERE id = ?", id));
        locationIds.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Staff are not a reason to refuse
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void aUnitWithSupervisorsAndOperatorsIsDeleted() {
        Long unitId = seedUnit();
        Long userId = anyUserId();
        assignSupervisor(unitId, userId);
        assignOperator(unitId, userId);

        operationalUnitService.delete(unitId);

        assertThat(operationalUnitRepository.findById(unitId)).isEmpty();
    }

    @Test
    void theStaffAssignmentsGoWithTheUnitRatherThanBeingOrphaned() {
        Long unitId = seedUnit();
        Long userId = anyUserId();
        assignSupervisor(unitId, userId);
        assignOperator(unitId, userId);

        operationalUnitService.delete(unitId);

        assertThat(unitSupervisorRepository.findByUnitId(unitId)).isEmpty();
        assertThat(unitOperatorRepository.findByUnitId(unitId)).isEmpty();
    }

    @Test
    void thePeopleThemselvesAreUntouched() {
        // Deleting a unit is not a personnel action. If this ever regresses it removes a real
        // account, and the account is the thing every log sheet in history is attributed to.
        Long unitId = seedUnit();
        Long userId = anyUserId();
        assignSupervisor(unitId, userId);

        operationalUnitService.delete(unitId);

        assertThat(userRepository.findById(userId)).isPresent();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // What is a reason to refuse
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void aUnitThatOwnsALocationIsRefused() {
        Long unitId = seedUnit();
        attachAnyLocation(unitId);

        assertThatThrownBy(() -> operationalUnitService.delete(unitId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("locations");

        assertThat(operationalUnitRepository.findById(unitId)).isPresent();
    }

    @Test
    void aUnitWithAChildUnitIsRefused() {
        Long parentId = seedUnit();
        Long childId = seedUnit(parentId);

        assertThatThrownBy(() -> operationalUnitService.delete(parentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("child units");

        assertThat(operationalUnitRepository.findById(parentId)).isPresent();
        assertThat(operationalUnitRepository.findById(childId)).isPresent();
    }

    /** A refusal must leave the unit exactly as it was, staff included. */
    @Test
    void aRefusalRollsBackNothingItHadAlreadyDone() {
        // The guards run before any delete, but the staff rows are removed inside the same
        // transaction as the unit — so a refusal that happened *after* they were cleared would
        // leave a unit with its people silently detached. Ordering is what prevents that; this
        // asserts the outcome rather than the ordering.
        Long unitId = seedUnit();
        Long userId = anyUserId();
        assignSupervisor(unitId, userId);
        assignOperator(unitId, userId);
        attachAnyLocation(unitId);

        assertThatThrownBy(() -> operationalUnitService.delete(unitId))
                .isInstanceOf(IllegalStateException.class);

        assertThat(unitSupervisorRepository.findByUnitId(unitId)).hasSize(1);
        assertThat(unitOperatorRepository.findByUnitId(unitId)).hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The bulk path
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The realistic selection: several units, one of which is in use.
     *
     * <p>Per-id transactions are the whole point — a single transaction would roll the deletable
     * ones back too and leave the administrator to find the offender by bisecting.
     */
    @Test
    void theDeletableOnesGoAndTheUsedOneIsReportedById() {
        Long empty = seedUnit();
        Long staffed = seedUnit();
        assignSupervisor(staffed, anyUserId());
        assignOperator(staffed, anyUserId());
        Long used = seedUnit();
        attachAnyLocation(used);

        BulkDeleteResult result = operationalUnitService.deleteAll(List.of(empty, staffed, used));

        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(result.getErrors()).singleElement()
                .satisfies(error -> {
                    assertThat(error.id()).isEqualTo(used);
                    assertThat(error.message())
                            .as("the page shows this to an administrator, so it is Persian")
                            .contains("مکان");
                });

        assertThat(operationalUnitRepository.findById(empty)).isEmpty();
        assertThat(operationalUnitRepository.findById(staffed)).isEmpty();
        assertThat(operationalUnitRepository.findById(used)).isPresent();
    }

    @Test
    void anEmptySelectionIsNotAnError() {
        assertThat(operationalUnitService.deleteAll(List.of()).getSuccessCount()).isZero();
        assertThat(operationalUnitService.deleteAll(null).getErrorCount()).isZero();
    }

    @Test
    void aRepeatedIdIsDeletedOnce() {
        // The ids come from checkboxes, so duplicates are not expected — but counting one delete
        // twice would report a success that did not happen.
        Long unitId = seedUnit();

        BulkDeleteResult result = operationalUnitService.deleteAll(List.of(unitId, unitId, unitId));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getErrorCount()).isZero();
    }

    /**
     * An id that is already gone counts as a success, and nothing is thrown.
     *
     * <p>Not an oversight — {@code deleteById} is a no-op for a row that is not there, and that
     * is the right answer here. The administrator ticked a row and wants it gone; if somebody
     * else removed it in the meantime, the end state is exactly what they asked for and an error
     * banner would be noise about a success. `MasterDataDeleteService` behaves the same way for
     * locations and the rest, so the two paths agree.
     *
     * <p>What must not happen is an exception escaping and taking the rest of the selection with
     * it, which is what this really pins.
     */
    @Test
    void anIdThatIsAlreadyGoneIsNotAnError() {
        Long unitId = seedUnit();

        BulkDeleteResult result = operationalUnitService.deleteAll(List.of(unitId, 999_999_999L));

        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getErrorCount()).isZero();
        assertThat(operationalUnitRepository.findById(unitId)).isEmpty();
    }

    /**
     * Deleting a parent and its child together, parent first.
     *
     * <p>The parent is refused because the child still exists at that moment; the child then
     * succeeds. That is the honest outcome of per-id transactions and it is worth pinning: the
     * alternative — ordering the selection so children go first — would look tidier and would
     * quietly delete a subtree the administrator only half selected.
     */
    @Test
    void aParentSelectedWithItsChildIsRefusedWhileTheChildIsDeleted() {
        Long parentId = seedUnit();
        Long childId = seedUnit(parentId);

        BulkDeleteResult result = operationalUnitService.deleteAll(List.of(parentId, childId));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(result.getErrors()).singleElement()
                .satisfies(error -> assertThat(error.id()).isEqualTo(parentId));
        assertThat(operationalUnitRepository.findById(childId)).isEmpty();
        assertThat(operationalUnitRepository.findById(parentId)).isPresent();
    }

    /** The bulk path and the single path must refuse for the same reasons. */
    @Test
    void bulkAndSingleDeleteAgreeOnWhatIsDeletable() {
        Long usedForSingle = seedUnit();
        attachAnyLocation(usedForSingle);
        Long usedForBulk = seedUnit();
        attachAnyLocation(usedForBulk);

        assertThatThrownBy(() -> operationalUnitService.delete(usedForSingle))
                .isInstanceOf(IllegalStateException.class);
        BulkDeleteResult result = operationalUnitService.deleteAll(List.of(usedForBulk));

        assertThat(result.getErrorCount())
                .as("one guard set, shared — not two that can drift apart")
                .isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────────────────────────────────

    private Long seedUnit() {
        return seedUnit(null);
    }

    private Long seedUnit(Long parentId) {
        long now = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("BULK-" + System.nanoTime());
        unit.setName("واحد آزمون حذف گروهی");
        unit.setParentId(parentId);
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        Long id = operationalUnitRepository.saveAndFlush(unit).getId();
        unitIds.add(id);
        return id;
    }

    private Long anyUserId() {
        return userRepository.findAll().stream().findFirst().orElseThrow().getId();
    }

    private void assignSupervisor(Long unitId, Long userId) {
        UnitSupervisor link = new UnitSupervisor();
        link.setUnitId(unitId);
        link.setUserId(userId);
        unitSupervisorRepository.saveAndFlush(link);
    }

    private void assignOperator(Long unitId, Long userId) {
        UnitOperator link = new UnitOperator();
        link.setUnitId(unitId);
        link.setUserId(userId);
        unitOperatorRepository.saveAndFlush(link);
    }

    /** Gives the unit a location to own, which is one of the four reasons to refuse a delete. */
    private void attachAnyLocation(Long unitId) {
        LocationUnit link = new LocationUnit();
        link.setLocationId(seedLocation());
        link.setUnitId(unitId);
        locationUnitRepository.saveAndFlush(link);
    }

    private Long seedLocation() {
        long now = System.currentTimeMillis();
        Location location = new Location();
        location.setCode("BULK-LOC-" + System.nanoTime());
        location.setName("Bulk delete fixture");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        Long id = locationRepository.saveAndFlush(location).getId();
        locationIds.add(id);
        return id;
    }
}
