package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.AssetLookupResponse;
import com.hnp.backendofflinefirst.service.AssetEntryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/asset-entries")
@RequiredArgsConstructor
public class AssetEntryController {

    private final AssetEntryService assetEntryService;

    @GetMapping("/nfc/{nfcTagId}")
    public ResponseEntity<AssetLookupResponse> findByNfcTag(@PathVariable String nfcTagId) {
        return assetEntryService.findByNfcTag(nfcTagId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
