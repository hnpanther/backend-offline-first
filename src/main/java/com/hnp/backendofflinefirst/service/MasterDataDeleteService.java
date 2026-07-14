package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.BulkDeleteResult;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.DataRecordRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class MasterDataDeleteService {

    private final LocationRepository locationRepository;
    private final PlantSystemRepository plantSystemRepository;
    private final MainFunctionRepository mainFunctionRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final DataRecordRepository dataRecordRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final TransactionTemplate transactionTemplate;

    public MasterDataDeleteService(LocationRepository locationRepository,
                                   PlantSystemRepository plantSystemRepository,
                                   MainFunctionRepository mainFunctionRepository,
                                   SubFunctionRepository subFunctionRepository,
                                   AssetEntryRepository assetEntryRepository,
                                   DataRecordRepository dataRecordRepository,
                                   LogSheetEntryRepository logSheetEntryRepository,
                                   PlatformTransactionManager transactionManager) {
        this.locationRepository = locationRepository;
        this.plantSystemRepository = plantSystemRepository;
        this.mainFunctionRepository = mainFunctionRepository;
        this.subFunctionRepository = subFunctionRepository;
        this.assetEntryRepository = assetEntryRepository;
        this.dataRecordRepository = dataRecordRepository;
        this.logSheetEntryRepository = logSheetEntryRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public void deleteLocation(Long id) {
        doDeleteLocation(id);
    }

    @Transactional
    public void deletePlantSystem(Long id) {
        doDeletePlantSystem(id);
    }

    @Transactional
    public void deleteMainFunction(Long id) {
        doDeleteMainFunction(id);
    }

    @Transactional
    public void deleteSubFunction(Long id) {
        doDeleteSubFunction(id);
    }

    @Transactional
    public void deleteAssetEntry(Long id) {
        doDeleteAssetEntry(id);
    }

    public BulkDeleteResult deleteLocations(Collection<Long> ids) {
        return deleteAll(ids, this::doDeleteLocation);
    }

    public BulkDeleteResult deletePlantSystems(Collection<Long> ids) {
        return deleteAll(ids, this::doDeletePlantSystem);
    }

    public BulkDeleteResult deleteMainFunctions(Collection<Long> ids) {
        return deleteAll(ids, this::doDeleteMainFunction);
    }

    public BulkDeleteResult deleteSubFunctions(Collection<Long> ids) {
        return deleteAll(ids, this::doDeleteSubFunction);
    }

    public BulkDeleteResult deleteAssetEntries(Collection<Long> ids) {
        return deleteAll(ids, this::doDeleteAssetEntry);
    }

    private BulkDeleteResult deleteAll(Collection<Long> ids, ItemDeleter deleter) {
        BulkDeleteResult result = new BulkDeleteResult();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        Set<Long> unique = new LinkedHashSet<>(ids);
        for (Long id : unique) {
            if (id == null) {
                continue;
            }
            try {
                transactionTemplate.executeWithoutResult(status -> deleter.delete(id));
                result.addSuccess();
            } catch (Exception e) {
                result.addError(id, translateDeleteError(e));
            }
        }
        return result;
    }

    private void doDeleteLocation(Long id) {
        assertDeletableLocation(id);
        locationRepository.deleteById(id);
    }

    private void doDeletePlantSystem(Long id) {
        assertDeletablePlantSystem(id);
        plantSystemRepository.deleteById(id);
    }

    private void doDeleteMainFunction(Long id) {
        assertDeletableMainFunction(id);
        mainFunctionRepository.deleteById(id);
    }

    private void doDeleteSubFunction(Long id) {
        assertDeletableSubFunction(id);
        subFunctionRepository.deleteById(id);
    }

    private void doDeleteAssetEntry(Long id) {
        assertDeletableAssetEntry(id);
        assetEntryRepository.deleteById(id);
    }

    private void assertDeletableLocation(Long id) {
        if (locationRepository.existsByParentId(id)) {
            throw new IllegalStateException("This location has child locations and cannot be deleted.");
        }
        if (plantSystemRepository.existsByLocationId(id)) {
            throw new IllegalStateException("This location has plant systems and cannot be deleted.");
        }
        if (mainFunctionRepository.existsByLocationId(id) || subFunctionRepository.existsByLocationId(id)) {
            throw new IllegalStateException("This location is referenced by functions and cannot be deleted.");
        }
    }

    private void assertDeletablePlantSystem(Long id) {
        if (plantSystemRepository.existsByParentId(id)) {
            throw new IllegalStateException("This plant system has child systems and cannot be deleted.");
        }
        if (mainFunctionRepository.existsBySystemId(id) || subFunctionRepository.existsBySystemId(id)) {
            throw new IllegalStateException("This plant system is referenced by functions and cannot be deleted.");
        }
    }

    private void assertDeletableMainFunction(Long id) {
        if (mainFunctionRepository.existsByParentId(id)) {
            throw new IllegalStateException("This main function has child main functions and cannot be deleted.");
        }
        if (subFunctionRepository.existsByMainFunctionId(id)) {
            throw new IllegalStateException("This main function has sub functions and cannot be deleted.");
        }
    }

    private void assertDeletableSubFunction(Long id) {
        if (subFunctionRepository.existsByParentId(id)) {
            throw new IllegalStateException("This sub function has child sub functions and cannot be deleted.");
        }
        if (assetEntryRepository.existsBySubFunctionId(id)) {
            throw new IllegalStateException("This sub function has asset entries and cannot be deleted.");
        }
    }

    private void assertDeletableAssetEntry(Long id) {
        if (logSheetEntryRepository.existsByAssetId(id) || dataRecordRepository.existsByAssetEntryId(id)) {
            throw new IllegalStateException("This asset entry is referenced by log sheets or records and cannot be deleted.");
        }
    }

    private static String translateDeleteError(Exception e) {
        if (e instanceof IllegalStateException ise) {
            return ErrorTranslator.toFa(ise.getMessage());
        }
        if (e instanceof DataIntegrityViolationException dive) {
            return ErrorTranslator.dataIntegrityViolation(dive);
        }
        return ErrorTranslator.toFa(e.getMessage());
    }

    @FunctionalInterface
    private interface ItemDeleter {
        void delete(Long id);
    }
}
