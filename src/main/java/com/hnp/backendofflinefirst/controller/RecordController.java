package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.RecordBatchRequest;
import com.hnp.backendofflinefirst.dto.RecordSubmitResult;
import com.hnp.backendofflinefirst.service.RecordService;
import com.hnp.backendofflinefirst.ui.ApiResponseSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('POST:/api/records/batch')")
public class RecordController {

    private final RecordService recordService;

    @PostMapping("/batch")
    public List<RecordSubmitResult> submitBatch(@RequestBody RecordBatchRequest request) {
        return ApiResponseSupport.localizeRecordSubmitResults(recordService.submitBatch(request.getRecords()));
    }
}
