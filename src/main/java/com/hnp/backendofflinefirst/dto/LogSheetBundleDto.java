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
}
