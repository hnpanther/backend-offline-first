package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetEntryServiceTest {

    @Mock AssetEntryRepository assetEntryRepository;
    @Mock AssetClassRepository assetClassRepository;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock MasterDataUniquenessValidator uniquenessValidator;
    // Journalling collaborators: every create/update now records who changed what. Lenient
    // because most cases here are about NFC inheritance and uniqueness, not history.
    @Mock(lenient = true) AssetStatusService assetStatusService;
    @Mock(lenient = true) AssetActivationHistoryService activationHistoryService;

    @InjectMocks AssetEntryService assetEntryService;

    @Test
    void resolveNfcFromSubFunctionTagWhenNfcEmpty() {
        SubFunction sf = new SubFunction();
        sf.setId(10L);
        sf.setTag("TAG-001");
        when(subFunctionRepository.findById(10L)).thenReturn(Optional.of(sf));

        AssetEntry entry = new AssetEntry();
        entry.setSubFunctionId(10L);
        assetEntryService.prepareForImport(entry);

        assertThat(entry.getNfcTagId()).isEqualTo("TAG-001");
    }

    @Test
    void keepsExplicitNfcWhenProvided() {
        SubFunction sf = new SubFunction();
        sf.setId(10L);
        sf.setTag("TAG-001");
        when(subFunctionRepository.findById(10L)).thenReturn(Optional.of(sf));

        AssetEntry entry = new AssetEntry();
        entry.setNfcTagId("CUSTOM-NFC");
        entry.setSubFunctionId(10L);
        assetEntryService.prepareForImport(entry);

        assertThat(entry.getNfcTagId()).isEqualTo("CUSTOM-NFC");
    }

    @Test
    void deactivatingReleasesAnNfcTagInheritedFromTheSubFunctionTag() {
        // The successor asset on this sub-function inherits the very same tag, so the retired
        // asset must let go of it or the unique index would block the replacement.
        SubFunction sf = new SubFunction();
        sf.setId(10L);
        sf.setTag("TAG-001");
        when(subFunctionRepository.findById(10L)).thenReturn(Optional.of(sf));

        AssetEntry entry = new AssetEntry();
        entry.setSubFunctionId(10L);
        entry.setNfcTagId("TAG-001");
        entry.setActive(false);
        assetEntryService.prepareForImport(entry);

        assertThat(entry.getNfcTagId()).isNull();
    }

    @Test
    void deactivatingReleasesAnNfcTagInheritedFromTheSubFunctionCode() {
        SubFunction sf = new SubFunction();
        sf.setId(11L);
        sf.setCode("SF-CODE");
        when(subFunctionRepository.findById(11L)).thenReturn(Optional.of(sf));

        AssetEntry entry = new AssetEntry();
        entry.setSubFunctionId(11L);
        entry.setNfcTagId("sf-code"); // case-insensitive match
        entry.setActive(false);
        assetEntryService.prepareForImport(entry);

        assertThat(entry.getNfcTagId()).isNull();
    }

    @Test
    void deactivatingKeepsAnNfcTagTheAssetOwnsItself() {
        // A tag that is neither the sub-function tag nor its code is physically on this asset,
        // so it stays with it and keeps blocking reuse by anything else.
        SubFunction sf = new SubFunction();
        sf.setId(10L);
        sf.setTag("TAG-001");
        sf.setCode("SF-CODE");
        when(subFunctionRepository.findById(10L)).thenReturn(Optional.of(sf));

        AssetEntry entry = new AssetEntry();
        entry.setSubFunctionId(10L);
        entry.setNfcTagId("OWN-NFC");
        entry.setActive(false);
        assetEntryService.prepareForImport(entry);

        assertThat(entry.getNfcTagId()).isEqualTo("OWN-NFC");
    }

    @Test
    void inactiveAssetDoesNotInheritTheSubFunctionTag() {
        SubFunction sf = new SubFunction();
        sf.setId(10L);
        sf.setTag("TAG-001");
        when(subFunctionRepository.findById(10L)).thenReturn(Optional.of(sf));

        AssetEntry entry = new AssetEntry();
        entry.setSubFunctionId(10L);
        entry.setActive(false);
        assetEntryService.prepareForImport(entry);

        assertThat(entry.getNfcTagId()).isNull();
    }

    @Test
    void resolveNfcFromSubFunctionCodeWhenTagEmpty() {
        SubFunction sf = new SubFunction();
        sf.setId(11L);
        sf.setCode("SF-CODE");
        when(subFunctionRepository.findById(11L)).thenReturn(Optional.of(sf));

        AssetEntry entry = new AssetEntry();
        entry.setSubFunctionId(11L);
        assetEntryService.prepareForImport(entry);

        assertThat(entry.getNfcTagId()).isEqualTo("SF-CODE");
    }

    @Test
    void trimsBlankDescriptionToNull() {
        AssetEntry entry = new AssetEntry();
        entry.setAssetCode("A-1");
        entry.setAssetName("پمپ");
        entry.setDescription("   ");
        entry.setSubFunctionId(10L);
        when(assetEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubFunction sf = new SubFunction();
        sf.setTag("T1");
        when(subFunctionRepository.findById(10L)).thenReturn(Optional.of(sf));
        when(subFunctionRepository.existsById(10L)).thenReturn(true);

        assetEntryService.create(entry);

        assertThat(entry.getDescription()).isNull();
    }

    @Test
    void createRejectsMissingSubFunction() {
        AssetEntry entry = new AssetEntry();
        entry.setAssetCode("A-1");
        entry.setAssetName("پمپ");

        assertThatThrownBy(() -> assetEntryService.create(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Sub function is required.");
    }

    @Test
    void createRejectsDuplicateAssetCode() {
        doThrow(new IllegalArgumentException("Duplicate asset code: DUP"))
                .when(uniquenessValidator).validateAssetEntry(isNull(), org.mockito.ArgumentMatchers.eq("DUP"));
        when(subFunctionRepository.existsById(10L)).thenReturn(true);

        AssetEntry entry = new AssetEntry();
        entry.setAssetCode("DUP");
        entry.setAssetName("تست");
        entry.setSubFunctionId(10L);
        entry.setNfcTagId("NFC-DUP");

        assertThatThrownBy(() -> assetEntryService.create(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate asset code");
    }

    @Test
    void updateCopiesActiveFlag() {
        AssetEntry existing = new AssetEntry();
        existing.setId(1L);
        existing.setAssetCode("A-1");
        existing.setAssetName("پمپ");
        existing.setSubFunctionId(10L);
        existing.setNfcTagId("NFC-1");
        existing.setActive(true);
        when(assetEntryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(subFunctionRepository.existsById(10L)).thenReturn(true);
        when(assetEntryRepository.save(any(AssetEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        AssetEntry form = new AssetEntry();
        form.setAssetCode("A-1");
        form.setAssetName("پمپ");
        form.setSubFunctionId(10L);
        form.setNfcTagId("NFC-1");
        form.setActive(false);

        assetEntryService.update(1L, form);

        assertThat(existing.isActive()).isFalse();
    }

    @Test
    void createDefaultsActiveToTrue() {
        when(subFunctionRepository.existsById(10L)).thenReturn(true);
        when(assetEntryRepository.save(any(AssetEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        AssetEntry entry = new AssetEntry();
        entry.setAssetCode("A-3");
        entry.setAssetName("پمپ");
        entry.setSubFunctionId(10L);
        entry.setNfcTagId("NFC-3");

        AssetEntry saved = assetEntryService.create(entry);

        assertThat(saved.isActive()).isTrue();
    }

    /**
     * The chip serial identifies hardware, not a mounting position. Unlike the NFC tag it must
     * never be back-filled from the sub-function — otherwise every asset on a tagged sub-function
     * would claim the same physical chip and collide on the unique index.
     */
    @Test
    void aBlankNfcSerialIsNeverInheritedFromTheSubFunction() {
        SubFunction sf = new SubFunction();
        sf.setId(10L);
        sf.setTag("TAG-001");
        when(subFunctionRepository.findById(10L)).thenReturn(Optional.of(sf));

        AssetEntry entry = new AssetEntry();
        entry.setSubFunctionId(10L);
        assetEntryService.prepareForImport(entry);

        assertThat(entry.getNfcSerial()).isNull();
        assertThat(entry.getNfcTagId()).as("the tag still inherits — only the serial must not").isEqualTo("TAG-001");
    }

    /** Deactivation releases an inherited tag; the serial stays put, because the chip does. */
    @Test
    void deactivatingKeepsTheNfcSerialEvenThoughItReleasesTheInheritedTag() {
        SubFunction sf = new SubFunction();
        sf.setId(10L);
        sf.setTag("TAG-001");
        when(subFunctionRepository.findById(10L)).thenReturn(Optional.of(sf));

        AssetEntry entry = new AssetEntry();
        entry.setSubFunctionId(10L);
        entry.setNfcTagId("TAG-001");
        entry.setNfcSerial("00:aa:34:9f");
        entry.setActive(false);
        assetEntryService.prepareForImport(entry);

        assertThat(entry.getNfcTagId()).as("inherited tag released").isNull();
        assertThat(entry.getNfcSerial()).as("serial belongs to the hardware, kept").isEqualTo("00:aa:34:9f");
    }

    @Test
    void createTrimsTheNfcSerialAndBlankBecomesNull() {
        when(subFunctionRepository.existsById(10L)).thenReturn(true);
        when(assetEntryRepository.save(any(AssetEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        AssetEntry entry = new AssetEntry();
        entry.setAssetCode("A-5");
        entry.setAssetName("پمپ");
        entry.setSubFunctionId(10L);
        entry.setNfcSerial("   ");

        assertThat(assetEntryService.create(entry).getNfcSerial()).isNull();

        AssetEntry padded = new AssetEntry();
        padded.setAssetCode("A-6");
        padded.setAssetName("پمپ");
        padded.setSubFunctionId(10L);
        padded.setNfcSerial("  00:aa:34:9f  ");

        assertThat(assetEntryService.create(padded).getNfcSerial()).isEqualTo("00:aa:34:9f");
    }

    @Test
    void createRejectsADuplicateNfcSerial() {
        doThrow(new IllegalArgumentException("Duplicate NFC serial: 00:aa:34:9f"))
                .when(uniquenessValidator).validateAssetNfcSerial(isNull(),
                        org.mockito.ArgumentMatchers.eq("00:aa:34:9f"));
        when(subFunctionRepository.existsById(10L)).thenReturn(true);

        AssetEntry entry = new AssetEntry();
        entry.setAssetCode("A-7");
        entry.setAssetName("پمپ");
        entry.setSubFunctionId(10L);
        entry.setNfcSerial("00:aa:34:9f");

        assertThatThrownBy(() -> assetEntryService.create(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate NFC serial: 00:aa:34:9f");
    }

    @Test
    void updateCarriesTheNfcSerialOntoTheManagedEntity() {
        SubFunction sf = new SubFunction();
        sf.setId(10L);
        sf.setTag("TAG-001");

        AssetEntry existing = new AssetEntry();
        existing.setId(1L);
        existing.setAssetCode("A-8");
        existing.setAssetName("پمپ");
        existing.setSubFunctionId(10L);
        existing.setNfcSerial("00:old:serial");
        when(assetEntryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(subFunctionRepository.findById(10L)).thenReturn(Optional.of(sf));
        when(subFunctionRepository.existsById(10L)).thenReturn(true);

        AssetEntry form = new AssetEntry();
        form.setAssetCode("A-8");
        form.setAssetName("پمپ");
        form.setSubFunctionId(10L);
        form.setNfcSerial("00:new:serial");
        form.setActive(true);

        assetEntryService.update(1L, form);

        assertThat(existing.getNfcSerial()).isEqualTo("00:new:serial");
    }

    // ---- updateNfcSerial: the "scan a chip, bind it to this asset" path -----------------------

    @Test
    void updateNfcSerialWritesOnlyTheSerialAndLeavesEverythingElseAlone() {
        AssetEntry existing = new AssetEntry();
        existing.setId(1L);
        existing.setAssetCode("A-10");
        existing.setAssetName("پمپ");
        existing.setSubFunctionId(10L);
        existing.setNfcTagId("P-0101B");
        existing.setActive(true);
        when(assetEntryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(assetEntryRepository.save(any(AssetEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        AssetEntry saved = assetEntryService.updateNfcSerial(1L, "  04:33:26:92:D0:12:91  ");

        assertThat(saved.getNfcSerial()).as("trimmed").isEqualTo("04:33:26:92:D0:12:91");
        assertThat(saved.getAssetCode()).isEqualTo("A-10");
        assertThat(saved.getAssetName()).isEqualTo("پمپ");
        assertThat(saved.getNfcTagId()).as("the logical tag id must not be touched").isEqualTo("P-0101B");
        assertThat(saved.getSubFunctionId()).isEqualTo(10L);
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void updateNfcSerialRejectsAChipAlreadyBoundToAnotherAsset() {
        AssetEntry existing = new AssetEntry();
        existing.setId(1L);
        when(assetEntryRepository.findById(1L)).thenReturn(Optional.of(existing));
        doThrow(new IllegalArgumentException("Duplicate NFC serial: 04:33:26:92:D0:12:91"))
                .when(uniquenessValidator).validateAssetNfcSerial(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq("04:33:26:92:D0:12:91"));

        assertThatThrownBy(() -> assetEntryService.updateNfcSerial(1L, "04:33:26:92:D0:12:91"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate NFC serial");
    }

    /** Re-binding the same chip to the same asset must not trip the uniqueness check. */
    @Test
    void updateNfcSerialAllowsRewritingTheSameValueOnTheOwningAsset() {
        AssetEntry existing = new AssetEntry();
        existing.setId(1L);
        existing.setNfcSerial("04:33:26:92:D0:12:91");
        when(assetEntryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(assetEntryRepository.save(any(AssetEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(assetEntryService.updateNfcSerial(1L, "04:33:26:92:D0:12:91").getNfcSerial())
                .isEqualTo("04:33:26:92:D0:12:91");
    }

    @Test
    void updateNfcSerialClearsTheBindingWhenBlank() {
        AssetEntry existing = new AssetEntry();
        existing.setId(1L);
        existing.setNfcSerial("04:33:26:92:D0:12:91");
        when(assetEntryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(assetEntryRepository.save(any(AssetEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(assetEntryService.updateNfcSerial(1L, "   ").getNfcSerial()).isNull();
    }

    @Test
    void updateNfcSerialRejectsAnUnknownAsset() {
        when(assetEntryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assetEntryService.updateNfcSerial(99L, "04:33"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Asset entry not found.");
    }

    @Test
    void createRejectsSubFunctionAlreadyAssigned() {
        doThrow(new IllegalArgumentException("This sub function is already assigned to another active asset."))
                .when(uniquenessValidator).validateAssetSubFunction(
                        isNull(), org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(true));
        when(subFunctionRepository.existsById(10L)).thenReturn(true);

        AssetEntry entry = new AssetEntry();
        entry.setAssetCode("A-4");
        entry.setAssetName("پمپ");
        entry.setSubFunctionId(10L);
        entry.setNfcTagId("NFC-4");

        assertThatThrownBy(() -> assetEntryService.create(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This sub function is already assigned to another active asset.");
    }
}
