package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.AssetLookupResponse;
import com.hnp.backendofflinefirst.dto.AssetNfcSerialRequest;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.service.AssetEntryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/asset-entries")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('GET:/api/asset-entries/nfc/{nfcTagId}')")
public class AssetEntryController {

    private final AssetEntryService assetEntryService;

    @GetMapping("/nfc/{nfcTagId}")
    public ResponseEntity<AssetLookupResponse> findByNfcTag(@PathVariable String nfcTagId) {
        return assetEntryService.findByNfcTag(nfcTagId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Binds a scanned physical chip to an asset (the admin NFC-inspect tool).
     *
     * <p>Carries its own {@code @PreAuthorize}, which overrides the class-level one: reading a tag
     * is an everyday operator action, writing the chip binding is not. Only roles holding this
     * separate authority — ADMIN and HIGH_USER by seed — can reach it.
     */
    @PostMapping("/{id}/nfc-serial")
    @PreAuthorize("hasAuthority('POST:/api/asset-entries/{id}/nfc-serial')")
    public ResponseEntity<AssetEntry> updateNfcSerial(@PathVariable Long id,
                                                      @RequestBody AssetNfcSerialRequest request) {
        return ResponseEntity.ok(assetEntryService.updateNfcSerial(id, request.getNfcSerial()));
    }
}
