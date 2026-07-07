package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.SubFunction;

/**
 * Resolves the NFC tag used for field lookup and log-sheet items:
 * explicit asset tag first, then sub-function tag, then sub-function code.
 */
public final class AssetNfcSupport {

    private AssetNfcSupport() {
    }

    public static String effectiveNfcTag(AssetEntry asset, SubFunction subFunction) {
        return effectiveNfcTag(asset != null ? asset.getNfcTagId() : null, subFunction);
    }

    public static String effectiveNfcTag(String assetNfcTagId, SubFunction subFunction) {
        String assetNfc = trimToNull(assetNfcTagId);
        if (assetNfc != null) {
            return assetNfc;
        }
        return nfcFromSubFunction(subFunction);
    }

    private static String nfcFromSubFunction(SubFunction subFunction) {
        if (subFunction == null) {
            return null;
        }
        String tag = trimToNull(subFunction.getTag());
        if (tag != null) {
            return tag;
        }
        return trimToNull(subFunction.getCode());
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
