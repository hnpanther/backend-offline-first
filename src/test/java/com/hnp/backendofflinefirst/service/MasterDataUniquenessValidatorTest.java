package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.MainFunctionRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MasterDataUniquenessValidatorTest {

    @Mock PlantSystemRepository plantSystemRepository;
    @Mock LocationRepository locationRepository;
    @Mock MainFunctionRepository mainFunctionRepository;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock AssetEntryRepository assetEntryRepository;

    @InjectMocks MasterDataUniquenessValidator validator;

    @Test
    void validatePlantSystemRejectsDuplicateCode() {
        PlantSystem existing = new PlantSystem();
        existing.setId(5L);
        existing.setCode("SYS-01");
        when(plantSystemRepository.findByCode("SYS-01")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validatePlantSystem(null, "SYS-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate plant system code");
    }

    @Test
    void importRejectsDuplicateCodeInDatabase() {
        PlantSystem existing = new PlantSystem();
        existing.setId(1L);
        when(plantSystemRepository.findByCode("SYS-01")).thenReturn(Optional.of(existing));

        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq =
                new MasterDataUniquenessValidator.FileUniqueness();

        boolean ok = validator.validatePlantSystemForImport("SYS-01", 2, result, fileUniq);

        assertThat(ok).isFalse();
        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(result.getErrors().getFirst().message()).contains("Duplicate plant system code");
    }

    @Test
    void importRejectsDuplicateCodeWithinSameFile() {
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq =
                new MasterDataUniquenessValidator.FileUniqueness();

        assertThat(validator.validatePlantSystemForImport("SYS-01", 2, result, fileUniq)).isTrue();
        assertThat(validator.validatePlantSystemForImport("SYS-01", 3, result, fileUniq)).isFalse();
        assertThat(result.getErrors().getFirst().message()).contains("Duplicate code in file");
    }
}
