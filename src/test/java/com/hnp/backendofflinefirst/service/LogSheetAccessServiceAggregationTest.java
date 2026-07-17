package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetAccessServiceAggregationTest {

    @Mock LogSheetRepository logSheetRepository;
    @Mock OperationalUnitScopeService unitScopeService;
    @InjectMocks LogSheetAccessService logSheetAccessService;

    private MockedStatic<SecurityUtils> security;

    @AfterEach
    void tearDown() {
        if (security != null) {
            security.close();
        }
    }

    @Test
    void countVisibleByStatusUsesSqlAggregation() {
        security = mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::isUnitScopedOnly).thenReturn(false);
        when(logSheetRepository.countGroupedByStatus(isNull())).thenReturn(List.of(
                new Object[]{LogSheetStatus.PENDING, 3L},
                new Object[]{LogSheetStatus.SUBMITTED, 1L}
        ));

        var counts = logSheetAccessService.countVisibleByStatus();
        assertThat(counts).containsEntry("PENDING", 3L).containsEntry("SUBMITTED", 1L);
        verify(logSheetRepository).countGroupedByStatus(isNull());
    }

    @Test
    void countVisibleRespectsEmptyUnitScope() {
        security = mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::isUnitScopedOnly).thenReturn(true);
        security.when(SecurityUtils::currentUserId).thenReturn(9L);
        when(unitScopeService.getAccessibleUnitIds(9L)).thenReturn(Set.of());

        assertThat(logSheetAccessService.countVisible()).isZero();
        assertThat(logSheetAccessService.countVisibleByStatus()).isEmpty();
        assertThat(logSheetAccessService.countVisibleByTemplateName()).isEmpty();
    }

    @Test
    void countVisibleByTemplateNameUsesSqlAggregation() {
        security = mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::isUnitScopedOnly).thenReturn(false);
        when(logSheetRepository.countGroupedByTemplateName(isNull())).thenReturn(List.of(
                new Object[]{"قالب الف", 5L},
                new Object[]{"قالب ب", 2L}
        ));

        var counts = logSheetAccessService.countVisibleByTemplateName();
        assertThat(counts)
                .containsEntry("قالب الف", 5L)
                .containsEntry("قالب ب", 2L);
    }
}
