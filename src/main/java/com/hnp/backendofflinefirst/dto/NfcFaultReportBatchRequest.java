package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.List;

@Data
public class NfcFaultReportBatchRequest {
    private List<NfcFaultReportDto> reports;
}
