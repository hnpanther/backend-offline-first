package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.BulkDeleteResult;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateAssetRepository;
import com.hnp.backendofflinefirst.repository.AssetStatusChangeRequestRepository;
import com.hnp.backendofflinefirst.repository.AssetActivationHistoryRepository;
import com.hnp.backendofflinefirst.repository.AssetStatusHistoryRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MasterDataDeleteServiceTest {

    @Mock LocationRepository locationRepository;
    @Mock PlantSystemRepository plantSystemRepository;
    @Mock MainFunctionRepository mainFunctionRepository;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock AssetEntryRepository assetEntryRepository;
    @Mock LogSheetEntryRepository logSheetEntryRepository;
    @Mock LogSheetTemplateAssetRepository logSheetTemplateAssetRepository;
    @Mock AssetStatusHistoryRepository assetStatusHistoryRepository;
    @Mock AssetActivationHistoryRepository assetActivationHistoryRepository;
    @Mock AssetStatusChangeRequestRepository assetStatusChangeRequestRepository;

    MasterDataDeleteService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {}

            @Override
            protected void doCommit(DefaultTransactionStatus status) {}

            @Override
            protected void doRollback(DefaultTransactionStatus status) {}
        };
        service = new MasterDataDeleteService(
                locationRepository,
                plantSystemRepository,
                mainFunctionRepository,
                subFunctionRepository,
                assetEntryRepository,
                logSheetEntryRepository,
                logSheetTemplateAssetRepository,
                assetStatusHistoryRepository,
                assetActivationHistoryRepository,
                assetStatusChangeRequestRepository,
                txManager);
    }

    @Test
    void deleteLocationsReturnsEmptyResultWhenIdsNullOrEmpty() {
        BulkDeleteResult empty = service.deleteLocations(null);
        assertThat(empty.getSuccessCount()).isZero();
        assertThat(empty.getErrorCount()).isZero();

        empty = service.deleteLocations(Collections.emptyList());
        assertThat(empty.getSuccessCount()).isZero();
        assertThat(empty.getErrorCount()).isZero();
        verify(locationRepository, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void deleteLocationsDeduplicatesIdsAndSkipsNulls() {
        stubDeletableLocation(10L);
        BulkDeleteResult result = service.deleteLocations(Arrays.asList(10L, null, 10L, 10L));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getErrorCount()).isZero();
        verify(locationRepository).deleteById(10L);
    }

    @Test
    void deleteLocationsSucceedsWhenNoDependencies() {
        stubDeletableLocation(1L);
        stubDeletableLocation(2L);

        BulkDeleteResult result = service.deleteLocations(List.of(1L, 2L));

        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getErrorCount()).isZero();
        verify(locationRepository).deleteById(1L);
        verify(locationRepository).deleteById(2L);
    }

    @Test
    void deleteLocationsReportsChildLocationError() {
        when(locationRepository.existsByParentId(5L)).thenReturn(true);

        BulkDeleteResult result = service.deleteLocations(List.of(5L));

        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(result.getErrors()).singleElement()
                .satisfies(err -> {
                    assertThat(err.id()).isEqualTo(5L);
                    assertThat(err.message()).contains("زیرمکان");
                });
        verify(locationRepository, never()).deleteById(5L);
    }

    @Test
    void deleteLocationsPartialSuccessWhenMixedBatch() {
        stubDeletableLocation(1L);
        when(locationRepository.existsByParentId(2L)).thenReturn(true);

        BulkDeleteResult result = service.deleteLocations(List.of(1L, 2L));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getErrorCount()).isEqualTo(1);
        verify(locationRepository).deleteById(1L);
        verify(locationRepository, never()).deleteById(2L);
    }

    @Test
    void deletePlantSystemsBlockedByChildSystem() {
        when(plantSystemRepository.existsByParentId(7L)).thenReturn(true);

        BulkDeleteResult result = service.deletePlantSystems(List.of(7L));

        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(result.getErrors().getFirst().message()).contains("زیرسیستم");
        verify(plantSystemRepository, never()).deleteById(7L);
    }

    @Test
    void deleteMainFunctionsBlockedBySubFunction() {
        when(mainFunctionRepository.existsByParentId(3L)).thenReturn(false);
        when(subFunctionRepository.existsByMainFunctionId(3L)).thenReturn(true);

        BulkDeleteResult result = service.deleteMainFunctions(List.of(3L));

        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(result.getErrors().getFirst().message()).contains("توابع فرعی");
    }

    @Test
    void deleteSubFunctionsBlockedByAssetEntry() {
        when(subFunctionRepository.existsByParentId(8L)).thenReturn(false);
        when(assetEntryRepository.existsBySubFunctionId(8L)).thenReturn(true);

        BulkDeleteResult result = service.deleteSubFunctions(List.of(8L));

        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(result.getErrors().getFirst().message()).contains("دارایی");
    }

    @Test
    void deleteAssetEntriesBlockedByLogSheetReference() {
        when(logSheetEntryRepository.existsByAssetId(99L)).thenReturn(true);

        BulkDeleteResult result = service.deleteAssetEntries(List.of(99L));

        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(result.getErrors().getFirst().message()).contains("لاگ");
        verify(assetEntryRepository, never()).deleteById(99L);
    }

    @Test
    void deleteAssetEntriesSucceedsWhenUnreferenced() {
        when(logSheetEntryRepository.existsByAssetId(4L)).thenReturn(false);

        BulkDeleteResult result = service.deleteAssetEntries(List.of(4L));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        verify(assetEntryRepository).deleteById(4L);
    }

    /**
     * Status history, activation toggles and status requests are ON DELETE CASCADE from the asset:
     * the database would delete them with it rather than refuse. Each one alone keeps the asset.
     */
    @Test
    void anAssetWithAnyRecordedHistoryIsKeptAndTheReasonIsNamed() {
        when(assetStatusHistoryRepository.existsByAssetId(11L)).thenReturn(true);
        when(assetActivationHistoryRepository.existsByAssetIdAndChangeTypeNot(12L,
                com.hnp.backendofflinefirst.domain.AssetActivationChangeType.CREATED)).thenReturn(true);
        when(assetStatusChangeRequestRepository.existsByAssetId(13L)).thenReturn(true);

        BulkDeleteResult result = service.deleteAssetEntries(List.of(11L, 12L, 13L));

        assertThat(result.getErrorCount()).isEqualTo(3);
        assertThat(result.getErrors()).allSatisfy(e -> assertThat(e.message()).contains("تاریخچه"));
        verify(assetEntryRepository, never()).deleteById(11L);
        verify(assetEntryRepository, never()).deleteById(12L);
        verify(assetEntryRepository, never()).deleteById(13L);
    }

    @Test
    void theCreatedRowEveryAssetIsBornWithDoesNotKeepIt() {
        // Mockito answers false for every guard: only the CREATED activation row exists.
        BulkDeleteResult result = service.deleteAssetEntries(List.of(14L));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        verify(assetEntryRepository).deleteById(14L);
    }

    @Test
    void singleDeleteLocationUsesSameDependencyChecks() {
        when(locationRepository.existsByParentId(6L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteLocation(6L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("child locations");
    }

    private void stubDeletableLocation(long id) {
        when(locationRepository.existsByParentId(id)).thenReturn(false);
        when(plantSystemRepository.existsByLocationId(id)).thenReturn(false);
        when(mainFunctionRepository.existsByLocationId(id)).thenReturn(false);
        when(subFunctionRepository.existsByLocationId(id)).thenReturn(false);
    }
}
