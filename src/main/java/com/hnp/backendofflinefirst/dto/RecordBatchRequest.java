package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.List;

@Data
public class RecordBatchRequest {
    private List<DataRecordDto> records;
}
