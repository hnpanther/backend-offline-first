package com.hnp.backendofflinefirst.service.importjob;

import com.hnp.backendofflinefirst.domain.ImportEntityType;
import com.hnp.backendofflinefirst.domain.ImportJobStatus;
import com.hnp.backendofflinefirst.dto.ImportError;
import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.ImportJob;
import com.hnp.backendofflinefirst.entity.ImportJobError;
import com.hnp.backendofflinefirst.repository.ImportJobErrorRepository;
import com.hnp.backendofflinefirst.repository.ImportJobRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class ImportJobService {

    private final ImportJobRepository importJobRepository;
    private final ImportJobErrorRepository importJobErrorRepository;
    private final ImportFileStorageService fileStorageService;
    private final com.hnp.backendofflinefirst.config.ImportStorageProperties storageProperties;
    private final ImportJobRunner importJobRunner;
    private final ImportJobCancellationRegistry cancellationRegistry;

    public ImportJobService(ImportJobRepository importJobRepository,
                            ImportJobErrorRepository importJobErrorRepository,
                            ImportFileStorageService fileStorageService,
                            com.hnp.backendofflinefirst.config.ImportStorageProperties storageProperties,
                            @Lazy ImportJobRunner importJobRunner,
                            ImportJobCancellationRegistry cancellationRegistry) {
        this.importJobRepository = importJobRepository;
        this.importJobErrorRepository = importJobErrorRepository;
        this.fileStorageService = fileStorageService;
        this.storageProperties = storageProperties;
        this.importJobRunner = importJobRunner;
        this.cancellationRegistry = cancellationRegistry;
    }

    @Transactional
    public ImportJob submit(ImportEntityType entityType, MultipartFile file, Long userId) throws IOException {
        if (!SecurityUtils.hasPermission(entityType.getImportPermission())) {
            throw new IllegalArgumentException("No permission to import " + entityType.getCode());
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".xlsx")) {
            throw new IllegalArgumentException("Only .xlsx files are supported.");
        }

        String jobUuid = UUID.randomUUID().toString();
        Path stored = fileStorageService.store(jobUuid, file);

        ImportJob job = new ImportJob();
        job.setJobUuid(jobUuid);
        job.setEntityType(entityType.getCode());
        job.setStatus(ImportJobStatus.PENDING);
        job.setFileName(originalName);
        job.setFilePath(stored.toString());
        job.setFileSize(file.getSize());
        job.setSubmittedByUserId(userId);
        job.setCreatedAt(System.currentTimeMillis());
        importJobRepository.save(job);
        log.info("[IMPORT_JOB] submitted jobUuid={} entityType={} filePath={} userId={} jobId={}",
                jobUuid, entityType.getCode(), stored, userId, job.getId());

        scheduleRun(job.getId());
        return job;
    }

    /** Starts async processing after the current transaction commits so the job row is visible. */
    void scheduleRun(Long jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("[IMPORT_JOB] scheduling async run after commit jobId={}", jobId);
                    importJobRunner.runAsync(jobId);
                }
            });
        } else {
            log.info("[IMPORT_JOB] scheduling async run (no tx) jobId={}", jobId);
            importJobRunner.runAsync(jobId);
        }
    }

    @Transactional(readOnly = true)
    public ImportJob requireJobForUser(String jobUuid, Long userId) {
        ImportJob job = importJobRepository.findByJobUuid(jobUuid)
                .orElseThrow(() -> new IllegalArgumentException("Import job not found."));
        if (!canViewJob(job, userId)) {
            throw new IllegalArgumentException("Import job not found.");
        }
        return job;
    }

    @Transactional(readOnly = true)
    public List<ImportJob> listRecentJobs(Long userId) {
        return importJobRepository.findTop50BySubmittedByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<ImportJob> listActiveJobs(Long userId) {
        return importJobRepository.findBySubmittedByUserIdAndStatusInOrderByCreatedAtDesc(
                userId, EnumSet.of(ImportJobStatus.PENDING, ImportJobStatus.RUNNING));
    }

    @Transactional(readOnly = true)
    public List<ImportJobError> listErrors(Long jobId, Long userId) {
        ImportJob job = importJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Import job not found."));
        if (!canViewJob(job, userId)) {
            throw new IllegalArgumentException("Import job not found.");
        }
        return importJobErrorRepository.findTop100ByJobIdOrderByRowNumAsc(jobId);
    }

    public List<ImportEntityType> availableEntityTypesForCurrentUser() {
        return java.util.Arrays.stream(ImportEntityType.values())
                .filter(t -> SecurityUtils.hasPermission(t.getImportPermission()))
                .toList();
    }

    @Transactional
    public void cancel(String jobUuid, Long userId) {
        ImportJob job = requireJobForUser(jobUuid, userId);
        if (!job.getStatus().isActive()) {
            throw new IllegalArgumentException("Import job is not active.");
        }
        if (job.getStatus() == ImportJobStatus.PENDING) {
            cancelPending(job);
            return;
        }
        cancellationRegistry.requestCancel(job.getId());
    }

    @Transactional
    public void delete(String jobUuid, Long userId) {
        ImportJob job = requireJobForUser(jobUuid, userId);
        if (job.getStatus().isActive()) {
            throw new IllegalArgumentException("Stop the import job before deleting it.");
        }
        importJobErrorRepository.deleteByJobId(job.getId());
        fileStorageService.deleteQuietly(job.getFilePath());
        importJobRepository.delete(job);
        cancellationRegistry.clear(job.getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryMarkRunning(Long jobId, int totalRows) {
        ImportJob job = importJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != ImportJobStatus.PENDING) {
            return false;
        }
        job.setStatus(ImportJobStatus.RUNNING);
        job.setStartedAt(System.currentTimeMillis());
        job.setTotalRows(totalRows);
        importJobRepository.save(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelComplete(Long jobId) {
        ImportJob job = importJobRepository.findById(jobId).orElse(null);
        if (job == null || !job.getStatus().isActive()) {
            cancellationRegistry.clear(jobId);
            return;
        }
        job.setStatus(ImportJobStatus.CANCELLED);
        job.setErrorMessage("Cancelled by user.");
        job.setCompletedAt(System.currentTimeMillis());
        importJobRepository.save(job);
        fileStorageService.deleteQuietly(job.getFilePath());
        cancellationRegistry.clear(jobId);
    }

    public boolean isCancellationRequested(Long jobId) {
        return cancellationRegistry.isCancelled(jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(Long jobId, int totalRows) {
        tryMarkRunning(jobId, totalRows);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(Long jobId, int processedRows, int totalRows) {
        importJobRepository.findById(jobId).ifPresent(job -> {
            job.setProcessedRows(processedRows);
            if (totalRows > 0) {
                job.setTotalRows(totalRows);
            }
            importJobRepository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long jobId, ImportResult result) {
        ImportJob job = importJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(ImportJobStatus.COMPLETED);
        job.setSuccessCount(result.getSuccessCount());
        job.setErrorCount(result.getErrorCount());
        job.setProcessedRows(Math.max(job.getProcessedRows(), job.getTotalRows()));
        job.setCompletedAt(System.currentTimeMillis());
        importJobRepository.save(job);
        persistErrors(jobId, result);
        fileStorageService.deleteQuietly(job.getFilePath());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long jobId, String message) {
        ImportJob job = importJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(ImportJobStatus.FAILED);
        job.setErrorMessage(message);
        job.setCompletedAt(System.currentTimeMillis());
        importJobRepository.save(job);
        fileStorageService.deleteQuietly(job.getFilePath());
    }

    @Transactional
    public void recoverStaleRunningJobs() {
        long now = System.currentTimeMillis();
        List<Long> toRequeue = new ArrayList<>();
        for (ImportJob job : importJobRepository.findByStatus(ImportJobStatus.RUNNING)) {
            job.setStatus(ImportJobStatus.FAILED);
            job.setErrorMessage("Import interrupted by server restart.");
            job.setCompletedAt(now);
            importJobRepository.save(job);
            fileStorageService.deleteQuietly(job.getFilePath());
        }
        for (ImportJob job : importJobRepository.findByStatus(ImportJobStatus.PENDING)) {
            if (job.getFilePath() != null && Files.exists(Path.of(job.getFilePath()))) {
                log.info("[IMPORT_JOB] recovery re-queue jobId={} jobUuid={} filePath={}",
                        job.getId(), job.getJobUuid(), job.getFilePath());
                toRequeue.add(job.getId());
            } else {
                job.setStatus(ImportJobStatus.FAILED);
                job.setErrorMessage("Import file missing after server restart.");
                job.setCompletedAt(now);
                importJobRepository.save(job);
                fileStorageService.deleteQuietly(job.getFilePath());
            }
        }
        for (Long jobId : toRequeue) {
            scheduleRun(jobId);
        }
    }

    private void cancelPending(ImportJob job) {
        job.setStatus(ImportJobStatus.CANCELLED);
        job.setErrorMessage("Cancelled by user.");
        job.setCompletedAt(System.currentTimeMillis());
        importJobRepository.save(job);
        fileStorageService.deleteQuietly(job.getFilePath());
        cancellationRegistry.clear(job.getId());
    }

    private void persistErrors(Long jobId, ImportResult result) {
        importJobErrorRepository.deleteByJobId(jobId);
        int limit = storageProperties.getMaxStoredErrors();
        int count = 0;
        for (ImportError err : result.getErrors()) {
            if (count >= limit) {
                break;
            }
            ImportJobError row = new ImportJobError();
            row.setJobId(jobId);
            row.setRowNum(err.row());
            row.setMessageEn(err.message());
            importJobErrorRepository.save(row);
            count++;
        }
    }

    private boolean canViewJob(ImportJob job, Long userId) {
        if (userId == null) {
            return false;
        }
        if (job.getSubmittedByUserId().equals(userId)) {
            return true;
        }
        return SecurityUtils.isAdmin();
    }
}
