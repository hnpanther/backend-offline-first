package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.domain.IntegrationKeyScope.IntegrationScopeException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.hnp.backendofflinefirst.domain.LogSheetStatus.APPROVED;
import static com.hnp.backendofflinefirst.domain.LogSheetStatus.CANCELLED;
import static com.hnp.backendofflinefirst.domain.LogSheetStatus.SUBMITTED;
import static com.hnp.backendofflinefirst.domain.LogSheetStatus.VOIDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What one integration key may read (V20): a scope only ever narrows a request. */
class IntegrationKeyScopeTest {

    @Test
    void nothingChosenIsNoRestrictionAndAnEmptySetCannotBeBuilt() {
        assertThat(IntegrationKeyScope.of(List.of(), List.of()).isUnrestricted()).isTrue();
        assertThatThrownBy(() -> new IntegrationKeyScope(Set.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrationKeyScope(null, Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInFlightStatusCanNeverBeInAScope() {
        assertThatThrownBy(() -> new IntegrationKeyScope(null, Set.of(LogSheetStatus.IN_PROGRESS)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNamedStatusOutsideTheScopeIsRefusedWithTheStatusesItMayRead() {
        IntegrationKeyScope scope = new IntegrationKeyScope(null, Set.of(SUBMITTED, APPROVED));
        assertThatThrownBy(() -> scope.effectiveStatuses(Set.of(SUBMITTED, VOIDED), true))
                .isInstanceOf(IntegrationScopeException.class)
                .hasMessageContaining("[VOIDED]")
                .hasMessageContaining("[APPROVED, SUBMITTED]");
        assertThat(scope.effectiveStatuses(Set.of(APPROVED), true)).containsExactly(APPROVED);
    }

    @Test
    void theDefaultIsTheCompletedStatusesTheKeyMayReadOrElseEverythingItMayRead() {
        assertThat(new IntegrationKeyScope(null, Set.of(APPROVED, VOIDED))
                .effectiveStatuses(IntegrationLogSheetQuery.DEFAULT_STATUSES, false))
                .containsExactly(APPROVED);
        assertThat(new IntegrationKeyScope(null, Set.of(VOIDED, CANCELLED))
                .effectiveStatuses(IntegrationLogSheetQuery.DEFAULT_STATUSES, false))
                .as("a key issued only for invalidated rounds is not handed an always-empty default")
                .containsExactlyInAnyOrder(VOIDED, CANCELLED);
        assertThat(IntegrationKeyScope.UNRESTRICTED
                .effectiveStatuses(IntegrationLogSheetQuery.DEFAULT_STATUSES, false))
                .isEqualTo(IntegrationLogSheetQuery.DEFAULT_STATUSES);
    }

    @Test
    void categoriesNarrowAndNeverWidenAndANarrowingToNothingStaysEmpty() {
        IntegrationKeyScope scope = new IntegrationKeyScope(Set.of(1L, 2L), null);
        assertThat(scope.effectiveCategoryIds(null)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(scope.effectiveCategoryIds(Set.of(2L, 3L))).containsExactly(2L);
        assertThat(scope.effectiveCategoryIds(Set.of(3L))).as("empty, never null").isNotNull().isEmpty();
        assertThat(IntegrationKeyScope.UNRESTRICTED.effectiveCategoryIds(null)).isNull();
    }

    @Test
    void anUncategorisedRoundIsOutsideACategoryScope() {
        IntegrationKeyScope scope = new IntegrationKeyScope(Set.of(1L), Set.of(SUBMITTED));
        assertThat(scope.permits(SUBMITTED, 1L)).isTrue();
        assertThat(scope.permits(SUBMITTED, null)).isFalse();
        assertThat(scope.permits(APPROVED, 1L)).isFalse();
        assertThat(IntegrationKeyScope.UNRESTRICTED.permits(VOIDED, null)).isTrue();
    }

    @Test
    void theStatusColumnRoundTripsAndFailsClosedWhenNothingInItIsExposable() {
        IntegrationKeyScope scope = new IntegrationKeyScope(null, Set.of(VOIDED, SUBMITTED));
        assertThat(scope.statusesColumn()).isEqualTo("SUBMITTED,VOIDED");
        assertThat(IntegrationKeyScope.parseStatusesColumn("SUBMITTED,VOIDED")).containsExactly(SUBMITTED, VOIDED);
        assertThat(IntegrationKeyScope.parseStatusesColumn(null)).isNull();
        assertThatThrownBy(() -> IntegrationKeyScope.parseStatusesColumn("IN_PROGRESS"))
                .as("a restricted key must never widen to every status")
                .isInstanceOf(IllegalStateException.class);
    }
}
