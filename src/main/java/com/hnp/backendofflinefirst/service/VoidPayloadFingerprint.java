package com.hnp.backendofflinefirst.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * A stable fingerprint of what a refused submission carried — its per-asset payload and the
 * round's own answers — so the same content sent twice is kept once (issue B-04 §2).
 *
 * <p>Canonical by construction: map entries are serialised in key order at every depth, so two
 * submissions that differ only in the order a {@code HashMap} happened to iterate in produce the
 * same fingerprint. SHA-256, hex — the same 64 characters as {@code log_sheet_void_submissions
 * .payload_hash} (V26).
 */
final class VoidPayloadFingerprint {

    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private VoidPayloadFingerprint() {
    }

    static String of(List<Map<String, Object>> payload, Map<String, Object> sheetFormData) {
        try {
            byte[] canonical = CANONICAL_JSON.writeValueAsBytes(
                    Map.of("payload", payload == null ? List.of() : payload,
                           "sheetFormData", sheetFormData == null ? Map.of() : sheetFormData));
            return AttachmentStorageService.sha256Hex(canonical);
        } catch (JsonProcessingException e) {
            // Not expected for the plain maps and lists a submission carries; failing here would
            // turn "kept for review" into "lost", so the caller keeps the row without one.
            return null;
        }
    }
}
