package com.hnp.backendofflinefirst.service.importjob;

import com.hnp.backendofflinefirst.domain.ImportEntityType;
import com.hnp.backendofflinefirst.domain.ImportJobStatus;
import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.ImportJob;
import com.hnp.backendofflinefirst.repository.ImportJobRepository;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import com.hnp.backendofflinefirst.util.ExcelUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Slf4j
public class ImportJobRunner {

    private final ImportJobRepository importJobRepository;
    private final ImportJobService importJobService;
    private final ExcelImportService excelImportService;

    public ImportJobRunner(ImportJobRepository importJobRepository,
                            @Lazy ImportJobService importJobService,
                            ExcelImportService excelImportService) {
        this.importJobRepository = importJobRepository;
        this.importJobService = importJobService;
        this.excelImportService = excelImportService;
    }

    @Async("importExecutor")
    public void runAsync(Long jobId) {
        ImportJob job = importJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("[IMPORT_JOB] run skipped — job not found: jobId={}", jobId);
            return;
        }
        if (job.getStatus() != ImportJobStatus.PENDING) {
            log.warn("[IMPORT_JOB] run skipped — jobId={} status={}", jobId, job.getStatus());
            return;
        }
        try {
            Path path = Path.of(job.getFilePath()).toAbsolutePath().normalize();
            log.info("[IMPORT_JOB] run start jobId={} jobUuid={} filePath={} exists={}",
                    jobId, job.getJobUuid(), path, Files.exists(path));
            int totalRows = job.getTotalRows() > 0 ? job.getTotalRows() : ExcelUtils.countDataRows(path);
            if (!importJobService.tryMarkRunning(jobId, totalRows)) {
                log.warn("[IMPORT_JOB] run aborted — could not mark RUNNING: jobId={}", jobId);
                return;
            }

            ImportEntityType entityType = ImportEntityType.fromCode(job.getEntityType())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown entity type: " + job.getEntityType()));

            ImportProgressListener progress = (processed, total) -> {
                if (importJobService.isCancellationRequested(jobId)) {
                    throw new ImportJobCancelledException();
                }
                importJobService.updateProgress(jobId, processed, total);
            };

            PathMultipartFile file = new PathMultipartFile(path);
            ImportResult result = excelImportService.importEntity(entityType, file, progress);
            importJobService.complete(jobId, result);
        } catch (ImportJobCancelledException e) {
            importJobService.cancelComplete(jobId);
        } catch (Exception e) {
            log.warn("[IMPORT_JOB] jobId={} failed: {}", jobId, e.getMessage());
            importJobService.fail(jobId, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}
