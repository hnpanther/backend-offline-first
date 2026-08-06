package com.hnp.backendofflinefirst.dto;

import lombok.Data;

/**
 * Body of {@code POST /api/asset-entries/{id}/nfc-serial}.
 * A blank or null value clears the asset's chip binding.
 */
@Data
public class AssetNfcSerialRequest {
    private String nfcSerial;
}
