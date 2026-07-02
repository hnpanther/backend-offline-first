package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.DataRecordDto;
import com.hnp.backendofflinefirst.dto.RecordSubmitResult;
import com.hnp.backendofflinefirst.entity.DataRecord;
import com.hnp.backendofflinefirst.repository.DataRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RecordService {

    private final DataRecordRepository dataRecordRepository;

    public List<RecordSubmitResult> submitBatch(List<DataRecordDto> dtos) {
        List<RecordSubmitResult> results = new ArrayList<>();
        for (DataRecordDto dto : dtos) {
            try {
                Optional<DataRecord> existing = dataRecordRepository.findByLocalId(dto.getLocalId());
                Long serverId;
                if (existing.isPresent()) {
                    DataRecord record = existing.get();
                    record.setRecordStatus(dto.getRecordStatus());
                    record.setFormData(dto.getFormData());
                    record.setNotes(dto.getNotes());
                    record.setOperatorName(dto.getOperatorName());
                    record.setUpdatedAt(dto.getUpdatedAt());
                    dataRecordRepository.save(record);
                    serverId = record.getId();
                } else {
                    DataRecord record = toEntity(dto);
                    dataRecordRepository.save(record);
                    serverId = record.getId();
                }
                results.add(new RecordSubmitResult(dto.getLocalId(), serverId, null));
            } catch (Exception e) {
                results.add(new RecordSubmitResult(dto.getLocalId(), null, e.getMessage()));
            }
        }
        return results;
    }

    private DataRecord toEntity(DataRecordDto dto) {
        DataRecord record = new DataRecord();
        record.setLocalId(dto.getLocalId());
        record.setNfcTagId(dto.getNfcTagId());
        record.setAssetEntryId(dto.getAssetEntryId());
        record.setAssetName(dto.getAssetName());
        record.setAssetTypeId(dto.getAssetTypeId());
        record.setRecordStatus(dto.getRecordStatus());
        record.setSyncStatus(dto.getSyncStatus());
        record.setFormData(dto.getFormData());
        record.setNotes(dto.getNotes());
        record.setOperatorName(dto.getOperatorName());
        record.setLocation(dto.getLocation());
        record.setSyncedAt(dto.getSyncedAt());
        record.setSyncError(dto.getSyncError());
        record.setCreatedAt(dto.getCreatedAt());
        record.setUpdatedAt(dto.getUpdatedAt());
        return record;
    }
}
