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
        } catch (Throwable t) {
            // Throwable, not Exception. A 10,000-row workbook is read into memory in one
            // piece, so the realistic way a large import dies is OutOfMemoryError — which is
            // an Error. Catching only Exception let it through, the thread ended, and the row
            // stayed RUNNING forever, blocking every subsequent import for every user.
            log.warn("[IMPORT_JOB] jobId={} failed: {}", jobId, t.toString(), t);
            markFailed(jobId, t);
            if (t instanceof Error) {
                // The status is recorded; now let the JVM see the Error. Swallowing an OOME
                // would leave the process in a state we have decided nothing about.
                throw (Error) t;
            }
        }
    }

    /**
     * Records the failure, and keeps trying if the ordinary path is what broke.
     * <p>
     * {@code fail()} is an entity save inside a transaction, so it has its own ways to throw
     * — and when it did, the exception escaped from inside the catch block and the job was
     * never marked at all. {@code forceFail()} is a native UPDATE with nothing left to go
     * wrong. If even that fails there is nothing more this process can do, and the watchdog
     * will clear the row within the timeout.
     */
    private void markFailed(Long jobId, Throwable cause) {
        String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        try {
            importJobService.fail(jobId, message);
        } catch (Throwable failureToFail) {
            log.error("[IMPORT_JOB] jobId={} could not record failure normally ({}), forcing status",
                    jobId, failureToFail.toString());
            try {
                importJobService.forceFail(jobId, message);
            } catch (Throwable forced) {
                log.error("[IMPORT_JOB] jobId={} could not force failure status either — "
                        + "the watchdog will clear it: {}", jobId, forced.toString(), forced);
            }
        }
    }
}
