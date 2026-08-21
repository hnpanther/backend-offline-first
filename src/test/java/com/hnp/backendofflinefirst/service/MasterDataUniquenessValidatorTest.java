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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // ─────────────────────────────────────────────────────────────────────────
    // A field key is an identifier: [A-Za-z0-9_-]+
    //
    // It is a JSON key in form_data (so it outlives every reading stored under it), a
    // form-control name on the device, an Excel export header and part of a SpEL expression on
    // the fill page. The rule used to blocklist `.`, `[` and `]` — arrived at one bug at a time —
    // and explicitly permitted spaces. It is now an allowlist, which is the only version of this
    // that does not need extending the next time a new consumer of the key appears.
    // ─────────────────────────────────────────────────────────────────────────

    private static final String INVALID_KEY_MESSAGE =
            "Field key may contain only English letters, digits, - and _ (no spaces).";

    @Test
    void validateFieldDefinitionRejectsKeyWithDot() {
        // `.` is a nested-path separator on the device: `V.1` would be stored as {"V":{"1":…}}
        // and would never match the flat key the server validates. The original reason for a rule.
        assertThatThrownBy(() -> validator.validateFieldDefinition(null, 1L, "V.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID_KEY_MESSAGE);
    }

    @Test
    void validateFieldDefinitionRejectsKeyWithBrackets() {
        assertThatThrownBy(() -> validator.validateFieldDefinition(null, 1L, "phase[0]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID_KEY_MESSAGE);
    }

    @Test
    void validateFieldDefinitionRejectsSpaces() {
        // Deliberate reversal: spaces used to be allowed, and `validateFieldDefinitionAllows-
        // SpacesAndDashes` asserted it. A space in an identifier is invisible at both ends, so
        // two keys that look identical are two different fields — and the readings stored under
        // each are unreachable from the other.
        assertThatThrownBy(() -> validator.validateFieldDefinition(null, 1L, "Bearing Housing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID_KEY_MESSAGE);
    }

    @Test
    void validateFieldDefinitionRejectsAKeyThatIsOnlyPadded() {
        // The key is trimmed before the check, so " temp " passes as "temp" — but a key whose
        // interior holds a space does not, and neither does one that is entirely whitespace.
        assertThatThrownBy(() -> validator.validateFieldDefinition(null, 1L, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void validateFieldDefinitionRejectsPersianText() {
        // Persian belongs in `label`. A non-ASCII JSON key is legal and survives nothing else
        // reliably — least of all an Excel header or a URL query.
        assertThatThrownBy(() -> validator.validateFieldDefinition(null, 1L, "دما"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID_KEY_MESSAGE);
    }

    @Test
    void validateFieldDefinitionRejectsCharactersThatMeanSomethingSomewhereElse() {
        // One assertion per consumer that would misread it: a spreadsheet formula, a quote inside
        // a SpEL expression, a URL separator, and a JSON structure character.
        for (String key : new String[]{"=temp", "+temp", "te'mp", "te\"mp", "a&b", "a/b", "a\\b",
                                       "a{b}", "a:b", "a;b", "a,b", "a%b", "a#b", "a?b", "a@b"}) {
            assertThatThrownBy(() -> validator.validateFieldDefinition(null, 1L, key))
                    .as("key %s must be refused", key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(INVALID_KEY_MESSAGE);
        }
    }

    @Test
    void validateFieldDefinitionRejectsAnInvalidKeyOnUpdateToo() {
        // The update path is the one that matters most: renaming a key orphans every reading
        // already stored under the old one, so the new spelling has to be valid before it lands.
        assertThatThrownBy(() -> validator.validateFieldDefinition(4L, 1L, "V.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID_KEY_MESSAGE);
    }

    @Test
    void validateFieldDefinitionAcceptsAnIdentifier() {
        for (String key : new String[]{"temp", "Temperature", "inlet_temp", "V-1", "DE-Bearing",
                                       "phase3", "A", "0", "_x", "-x", "a_b-c9"}) {
            when(fieldDefinitionRepository.findByClassIdAndKeyIgnoreCase(1L, key))
                    .thenReturn(Optional.empty());

            validator.validateFieldDefinition(null, 1L, key);
        }
    }

    @Test
    void validateFieldDefinitionStillTrimsBeforeChecking() {
        // Padding a valid key is a paste artefact, not a different key. It is trimmed, then
        // validated — so the stored key is the identifier and nothing downstream sees the spaces.
        when(fieldDefinitionRepository.findByClassIdAndKeyIgnoreCase(1L, "inlet_temp"))
                .thenReturn(Optional.empty());

        validator.validateFieldDefinition(null, 1L, "  inlet_temp  ");
    }

    @Test
    void everyFieldKeyInTheShippedSchemaWouldStillBeAccepted() {
        // The rule was tightened against data that already existed. These are the keys the
        // operational database holds; if any were refused, editing that field would become
        // impossible without renaming it — and renaming orphans its readings.
        for (String key : new String[]{"Audio", "Bar", "Description", "Location", "Pic",
                                       "Status", "Temperature", "Video"}) {
            when(fieldDefinitionRepository.findByClassIdAndKeyIgnoreCase(1L, key))
                    .thenReturn(Optional.empty());

            validator.validateFieldDefinition(null, 1L, key);
        }
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
    void validateAssetNfcSerialRejectsAnotherAssetHoldingTheSameChip() {
        AssetEntry existing = new AssetEntry();
        existing.setId(8L);
        existing.setNfcSerial("00:AA:34:9F");
        when(assetEntryRepository.findByNfcSerialIgnoreCase("00:aa:34:9f")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validateAssetNfcSerial(null, "00:aa:34:9f"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate NFC serial");
    }

    /** Editing the asset that already owns the serial must not trip over its own row. */
    @Test
    void validateAssetNfcSerialAllowsTheOwningAssetToKeepIt() {
        AssetEntry existing = new AssetEntry();
        existing.setId(8L);
        existing.setNfcSerial("00:aa:34:9f");
        when(assetEntryRepository.findByNfcSerialIgnoreCase("00:aa:34:9f")).thenReturn(Optional.of(existing));

        validator.validateAssetNfcSerial(8L, "00:aa:34:9f");
    }

    @Test
    void validateAssetNfcSerialAllowsBlank() {
        validator.validateAssetNfcSerial(null, null);
        validator.validateAssetNfcSerial(null, "  ");
    }

    @Test
    void importRejectsDuplicateNfcSerialInSameFile() {
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq =
                new MasterDataUniquenessValidator.FileUniqueness();

        assertThat(validator.validateAssetNfcSerialForImport("00:AA:34", 2, result, fileUniq)).isTrue();
        assertThat(validator.validateAssetNfcSerialForImport("00:aa:34", 3, result, fileUniq)).isFalse();
        assertThat(result.getErrors().getFirst().message()).contains("Duplicate NFC serial in file");
    }

    /** Blank serials never register, so any number of rows may leave the column empty. */
    @Test
    void importAllowsManyBlankNfcSerials() {
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq =
                new MasterDataUniquenessValidator.FileUniqueness();

        assertThat(validator.validateAssetNfcSerialForImport(null, 2, result, fileUniq)).isTrue();
        assertThat(validator.validateAssetNfcSerialForImport("   ", 3, result, fileUniq)).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    /** The serial and the tag are tracked separately — one must not shadow the other. */
    @Test
    void importTracksNfcTagAndNfcSerialInSeparateNamespaces() {
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq =
                new MasterDataUniquenessValidator.FileUniqueness();

        assertThat(validator.validateAssetNfcForImport("SHARED-VALUE", 2, result, fileUniq)).isTrue();
        assertThat(validator.validateAssetNfcSerialForImport("SHARED-VALUE", 2, result, fileUniq)).isTrue();
        assertThat(result.getErrors()).isEmpty();
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

    @Test
    void validateAssetSubFunctionRejectsWhenAnotherActiveAssetHoldsIt() {
        AssetEntry existing = new AssetEntry();
        existing.setId(8L);
        existing.setSubFunctionId(10L);
        when(assetEntryRepository.findFirstBySubFunctionIdAndActiveTrue(10L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> validator.validateAssetSubFunction(null, 10L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This sub function is already assigned to another active asset.");
    }

    @Test
    void validateAssetSubFunctionAllowsSameAsset() {
        AssetEntry existing = new AssetEntry();
        existing.setId(8L);
        existing.setSubFunctionId(10L);
        when(assetEntryRepository.findFirstBySubFunctionIdAndActiveTrue(10L)).thenReturn(Optional.of(existing));

        validator.validateAssetSubFunction(8L, 10L, true);
    }

    @Test
    void validateAssetSubFunctionAllowsAnInactiveAssetToShareAnOccupiedSubFunction() {
        // Equipment replacement: the retired pump stays attached to the sub-function for
        // history while its active successor occupies the same slot.
        validator.validateAssetSubFunction(null, 10L, false);

        verify(assetEntryRepository, never()).findFirstBySubFunctionIdAndActiveTrue(anyLong());
    }

    @Test
    void importRejectsDuplicateSubFunctionAssignmentInDatabase() {
        AssetEntry existing = new AssetEntry();
        existing.setId(8L);
        when(assetEntryRepository.findFirstBySubFunctionIdAndActiveTrue(10L)).thenReturn(Optional.of(existing));

        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq =
                new MasterDataUniquenessValidator.FileUniqueness();

        assertThat(validator.validateAssetSubFunctionForImport(10L, "SF-01", true, 2, result, fileUniq)).isFalse();
        assertThat(result.getErrors().getFirst().message())
                .contains("This sub function is already assigned to another active asset");
    }

    @Test
    void importRejectsDuplicateSubFunctionWithinSameFile() {
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq =
                new MasterDataUniquenessValidator.FileUniqueness();

        assertThat(validator.validateAssetSubFunctionForImport(10L, "SF-01", true, 2, result, fileUniq)).isTrue();
        assertThat(validator.validateAssetSubFunctionForImport(10L, "SF-01", true, 3, result, fileUniq)).isFalse();
        assertThat(result.getErrors().getFirst().message()).contains("Duplicate sub function in file");
    }

    @Test
    void importAllowsSeveralInactiveRowsOnTheSameSubFunction() {
        ImportResult result = new ImportResult();
        MasterDataUniquenessValidator.FileUniqueness fileUniq =
                new MasterDataUniquenessValidator.FileUniqueness();

        assertThat(validator.validateAssetSubFunctionForImport(10L, "SF-01", false, 2, result, fileUniq)).isTrue();
        assertThat(validator.validateAssetSubFunctionForImport(10L, "SF-01", false, 3, result, fileUniq)).isTrue();
        // ...and an active one may still join them.
        assertThat(validator.validateAssetSubFunctionForImport(10L, "SF-01", true, 4, result, fileUniq)).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }
}
