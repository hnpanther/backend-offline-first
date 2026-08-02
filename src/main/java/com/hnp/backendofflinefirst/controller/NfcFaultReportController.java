package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.NfcFaultReportBatchRequest;
import com.hnp.backendofflinefirst.dto.NfcFaultReportSubmitResult;
import com.hnp.backendofflinefirst.service.NfcFaultReportService;
import com.hnp.backendofflinefirst.ui.ApiResponseSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/nfc-fault-reports")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('POST:/api/nfc-fault-reports/batch')")
public class NfcFaultReportController {

    private final NfcFaultReportService nfcFaultReportService;

    @PostMapping("/batch")
    public List<NfcFaultReportSubmitResult> submitBatch(@RequestBody NfcFaultReportBatchRequest request) {
        return ApiResponseSupport.localizeNfcFaultReportSubmitResults(
                nfcFaultReportService.submitBatch(request.getReports()));
    }
}
