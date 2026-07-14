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
        AssetEntry entry = new AssetEntry();
        entry.setNfcTagId("CUSTOM-NFC");
        entry.setSubFunctionId(10L);
        assetEntryService.prepareForImport(entry);

        assertThat(entry.getNfcTagId()).isEqualTo("CUSTOM-NFC");
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
        when(assetEntryRepository.existsByNfcTagId("T1")).thenReturn(false);

        assetEntryService.create(entry);

        assertThat(entry.getDescription()).isNull();
    }

    @Test
    void createRejectsDuplicateAssetCode() {
        doThrow(new IllegalArgumentException("Duplicate asset code: DUP"))
                .when(uniquenessValidator).validateAssetEntry(isNull(), org.mockito.ArgumentMatchers.eq("DUP"));

        AssetEntry entry = new AssetEntry();
        entry.setAssetCode("DUP");
        entry.setAssetName("تست");

        assertThatThrownBy(() -> assetEntryService.create(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate asset code");
    }
}
