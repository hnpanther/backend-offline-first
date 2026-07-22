package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
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
    @Mock AssetClassRepository assetClassRepository;
    @Mock FieldDefinitionRepository fieldDefinitionRepository;

    @InjectMocks MasterDataUniquenessValidator validator;

    @Test
    void validatePlantSystemRejectsDuplicateCode() {
        PlantSystem existing = new PlantSystem();
        existing.setId(5L);
        existing.setCode("SYS-01");
        when(plantSystemRepository.findByCodeIgnoreCase("SYS-01")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validatePlantSystem(null, "SYS-01", "System"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate plant system code");
    }

    @Test
    void validatePlantSystemRejectsBlankName() {
        assertThatThrownBy(() -> validator.validatePlantSystem(null, "SYS-01", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("plant system name is required.");
    }

    @Test
    void validatePlantSystemRejectsCaseInsensitiveDuplicate() {
        PlantSystem existing = new PlantSystem();
        existing.setId(5L);
        existing.setCode("COD1");
        when(plantSystemRepository.findByCodeIgnoreCase("cod1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validatePlantSystem(null, "cod1", "Pump"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate plant system code");
    }

    @Test
    void validateMainFunctionRejectsBlankCode() {
        assertThatThrownBy(() -> validator.validateMainFunction(null, " ", "Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("main function code is required.");
    }

    @Test
    void importRejectsDuplicateCodeInDatabase() {
        PlantSystem existing = new PlantSystem();
        existing.setId(1L);
        when(plantSystemRepository.findByCodeIgnoreCase("SYS-01")).thenReturn(Optional.of(existing));

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

    @Test
    void validateLocationRejectsBlankCode() {
        assertThatThrownBy(() -> validator.validateLocation(null, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("location code is required.");
    }

    @Test
    void validateLocationRejectsCaseInsensitiveDuplicate() {
        Location existing = new Location();
        existing.setId(3L);
        existing.setCode("LOC-01");
        when(locationRepository.findByCodeIgnoreCase("loc-01")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validateLocation(null, "loc-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate location code");
    }

    @Test
    void importRejectsCaseInsensitiveDuplicateLocationInFile() {
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq =
                new MasterDataUniquenessValidator.FileUniqueness();

        assertThat(validator.validateLocationForImport("LOC1", 2, result, fileUniq)).isTrue();
        assertThat(validator.validateLocationForImport("loc1", 3, result, fileUniq)).isFalse();
        assertThat(result.getErrors().getFirst().message()).contains("Duplicate code in file");
    }

    @Test
    void validateSubFunctionRejectsBlankTag() {
        assertThatThrownBy(() -> validator.validateSubFunction(null, "SF-01", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sub function tag is required.");
    }

    @Test
    void validateSubFunctionRejectsCaseInsensitiveDuplicateCode() {
        SubFunction existing = new SubFunction();
        existing.setId(9L);
        existing.setCode("COD1");
        when(subFunctionRepository.findByCodeIgnoreCase("cod1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validateSubFunction(null, "cod1", "TAG-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate sub function code");
    }

    @Test
    void validateSubFunctionRejectsCaseInsensitiveDuplicateTag() {
        SubFunction existing = new SubFunction();
        existing.setId(9L);
        existing.setTag("TAG-1");
        when(subFunctionRepository.findByCodeIgnoreCase("SF-01")).thenReturn(Optional.empty());
        when(subFunctionRepository.findByTagIgnoreCase("tag-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validateSubFunction(null, "SF-01", "tag-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate sub function tag");
    }

    @Test
    void importRejectsDuplicateSubFunctionTagInFile() {
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq =
                new MasterDataUniquenessValidator.FileUniqueness();

        assertThat(validator.validateSubFunctionForImport("SF-01", "TAG-1", 2, result, fileUniq)).isTrue();
        assertThat(validator.validateSubFunctionForImport("SF-02", "tag-1", 3, result, fileUniq)).isFalse();
        assertThat(result.getErrors().getFirst().message()).contains("Duplicate tag in file");
    }

    @Test
    void importRejectsMissingSubFunctionTag() {
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq =
                new MasterDataUniquenessValidator.FileUniqueness();

        assertThat(validator.validateSubFunctionForImport("SF-01", " ", 2, result, fileUniq)).isFalse();
        assertThat(result.getErrors().getFirst().message()).isEqualTo("Tag is required.");
    }

    @Test
    void validateAssetClassRejectsCaseInsensitiveDuplicateName() {
        AssetClass existing = new AssetClass();
        existing.setId(2L);
        existing.setName("Pump1");
        when(assetClassRepository.findByNameIgnoreCase("pump1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validateAssetClass(null, "pump1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate asset class name");
    }

    @Test
    void validateAssetClassRejectsBlankName() {
        assertThatThrownBy(() -> validator.validateAssetClass(null, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("asset class name is required.");
    }

    @Test
    void validateFieldDefinitionRejectsDuplicateKeyInSameClass() {
        FieldDefinition existing = new FieldDefinition();
        existing.setId(4L);
        existing.setClassId(1L);
        existing.setKey("temperature");
        when(fieldDefinitionRepository.findByClassIdAndKeyIgnoreCase(1L, "Temperature"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validateFieldDefinition(null, 1L, "Temperature"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate field key");
    }

    @Test
    void validateFieldDefinitionRejectsNullClassId() {
        assertThatThrownBy(() -> validator.validateFieldDefinition(null, null, "temperature"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("field definition class is required.");
    }

    @Test
    void validateAssetNfcRejectsCaseInsensitiveDuplicate() {
        AssetEntry existing = new AssetEntry();
        existing.setId(8L);
        existing.setNfcTagId("NFC-01");
        when(assetEntryRepository.findByNfcTagIdIgnoreCase("nfc-01")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validateAssetNfcTag(null, "nfc-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate NFC tag");
    }

    @Test
    void importRejectsDuplicateNfcInSameFile() {
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq =
                new MasterDataUniquenessValidator.FileUniqueness();

        assertThat(validator.validateAssetNfcForImport("NFC-1", 2, result, fileUniq)).isTrue();
        assertThat(validator.validateAssetNfcForImport("nfc-1", 3, result, fileUniq)).isFalse();
        assertThat(result.getErrors().getFirst().message()).contains("Duplicate NFC tag in file");
    }

    @Test
    void validateAssetEntryRejectsCaseInsensitiveDuplicateCode() {
        AssetEntry existing = new AssetEntry();
        existing.setId(3L);
        existing.setAssetCode("AST-01");
        when(assetEntryRepository.findFirstByAssetCodeIgnoreCase("ast-01")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validateAssetEntry(null, "ast-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate asset code");
    }

    @Test
    void validateMainFunctionRejectsCaseInsensitiveDuplicateCode() {
        MainFunction existing = new MainFunction();
        existing.setId(4L);
        existing.setCode("MF-01");
        when(mainFunctionRepository.findByCodeIgnoreCase("mf-01")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validateMainFunction(null, "mf-01", "Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate main function code");
    }

    @Test
    void validateFieldDefinitionAllowsSameKeyInDifferentClass() {
        when(fieldDefinitionRepository.findByClassIdAndKeyIgnoreCase(2L, "temperature"))
                .thenReturn(Optional.empty());

        validator.validateFieldDefinition(null, 2L, "temperature");
    }

    @Test
    void validateAssetNfcAllowsBlank() {
        validator.validateAssetNfcTag(null, null);
        validator.validateAssetNfcTag(null, "  ");
    }
}
