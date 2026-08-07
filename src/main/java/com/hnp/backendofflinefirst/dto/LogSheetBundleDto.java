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
}
