package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.SubFunction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetNfcSupportTest {

    @Test
    void prefersExplicitAssetNfc() {
        AssetEntry asset = new AssetEntry();
        asset.setNfcTagId("ASSET-NFC");

        SubFunction sf = new SubFunction();
        sf.setTag("SF-TAG");
        sf.setCode("SF-CODE");

        assertThat(AssetNfcSupport.effectiveNfcTag(asset, sf)).isEqualTo("ASSET-NFC");
    }

    @Test
    void fallsBackToSubFunctionTag() {
        AssetEntry asset = new AssetEntry();

        SubFunction sf = new SubFunction();
        sf.setTag("SF-TAG");
        sf.setCode("SF-CODE");

        assertThat(AssetNfcSupport.effectiveNfcTag(asset, sf)).isEqualTo("SF-TAG");
    }

    @Test
    void fallsBackToSubFunctionCodeWhenTagEmpty() {
        AssetEntry asset = new AssetEntry();

        SubFunction sf = new SubFunction();
        sf.setCode("SF-CODE");

        assertThat(AssetNfcSupport.effectiveNfcTag(asset, sf)).isEqualTo("SF-CODE");
    }
}
