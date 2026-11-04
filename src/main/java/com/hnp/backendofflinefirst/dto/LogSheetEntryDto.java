package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.Map;

@Data
/** Mobile sync payload. Only {@link #assetId}, {@link #formData}, {@link #createdAt}, {@link #updatedAt},
 *  and {@link #manualEntry} are applied on submit; other fields are server-authoritative snapshots for
 *  offline display. {@code manualEntry} is trusted only for labeling how the entry was captured
 *  (NFC scan vs. manual fallback) — it has no effect on whether the submission itself is accepted,
 *  since NFC was never enforced server-side. Missing/null is treated as a scanned entry (PWA_NFC),
 *  the only channel that existed before this field was introduced.
 *
 *  <p><b>Since V11 {@link #entrySource} supersedes it on the way up</b>, because a boolean cannot
 *  tell a QR scan from a tag scan and the two carry different evidence. It is applied when
 *  present and {@code manualEntry} is read when it is not, so an older tablet keeps labelling its
 *  readings exactly as before. Both remain labels rather than credentials. */
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
    /**
     * The channel the device reports: {@code PWA_NFC}, {@code PWA_QR} or {@code PWA_MANUAL}.
     *
     * <p>Carried in <b>both</b> directions and meaning slightly different things. Downward it is
     * the server's own record of how an entry was captured, for the device to display. Upward it
     * is the device's report, applied on submit in preference to {@code manualEntry}; {@code WEB}
     * and anything unrecognised fall back to the boolean rather than being refused.
     */
    private String entrySource;
    private Long filledByUserId;

    /**
     * Who filled this asset's values, by name, for the device to show.
     *
     * <p>{@link #filledByUserId} has been carried for a while and the PWA cannot do anything
     * with it — an internal row id names nobody. Without a name the operator taking over a
     * reopened sheet sees rows already filled in and no indication whose they are, which is the
     * one thing they need in order to know which rows are theirs to redo.
     *
     * <p>Server-resolved rather than looked up on the device: the PWA holds no user directory,
     * and a round is filled offline where it could not fetch one.
     *
     * <p>Null when nobody has filled the entry yet, or when the account has since been deleted.
     */
    private String filledByName;
}
