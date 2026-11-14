package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.entity.LogSheet;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Self-contained mobile payload for one log sheet: metadata, entries, and scoped
 * reference data. {@code context} is null for metadata-only responses.
 */
@Data
@Builder
public class LogSheetBundleDto {
    private LogSheet sheet;
    private List<LogSheetEntryDto> entries;
    private LogSheetContextDto context;
    /**
     * NFC fault reports filed for this sheet, from any source (web or mobile). Groundwork
     * only for now — the PWA stores/syncs these but does not yet act on ones it didn't file
     * itself (only same-device reports unlock the manual-entry fallback today).
     */
    private List<NfcFaultReportDto> nfcFaultReports;
    /**
     * Attachment <em>metadata</em> already stored for this sheet — never the bytes. Lets the
     * PWA show what exists (and what another operator added) without downloading anything;
     * a client fetches the file only when the operator actually opens it.
     */
    private List<AttachmentDto> attachments;
    /**
     * The server's verdict on every signature this round holds (roadmap §32), keyed
     * {@code "sheet:<fieldKey>"} for a round-level question and {@code "<assetId>:<fieldKey>"} for
     * an asset's, valued {@code VALID} / {@code STALE} / {@code NOT_BOUND} / {@code UNVERIFIED}.
     *
     * <p>Only the server can say whether a signature still covers its answers — it alone computes
     * the hash — so the tablet shows this verdict and lets the operator sign again when it says
     * {@code STALE}. Additive: an older build of the app ignores it. Empty for a round with no
     * signature.
     */
    private java.util.Map<String, String> signatureStates;
}
