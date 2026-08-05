package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.Map;

@Data
/** Mobile sync payload. Only {@link #assetId}, {@link #formData}, {@link #createdAt}, {@link #updatedAt},
 *  and {@link #manualEntry} are applied on submit; other fields are server-authoritative snapshots for
 *  offline display. {@code manualEntry} is trusted only for labeling how the entry was captured
 *  (NFC scan vs. manual fallback) — it has no effect on whether the submission itself is accepted,
 *  since NFC was never enforced server-side. Missing/null is treated as a scanned entry (PWA_NFC),
 *  the only channel that existed before this field was introduced. */
public class LogSheetEntryDto {
    private Long assetId;
    private String assetName;
    private String subFunctionCode;
    private String subFunctionTag;
    private String nfcTagId;
    /** Physical NFC chip serial snapshot — server-authoritative, for offline scan matching. */
    private String nfcSerial;
    private Long classId;
    private Map<String, Object> formData;
    private Long createdAt;
    private Long updatedAt;
    private Boolean manualEntry;
    private String entrySource;
    private Long filledByUserId;
}
