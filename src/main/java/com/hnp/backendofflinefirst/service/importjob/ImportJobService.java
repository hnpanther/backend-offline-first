package com.hnp.backendofflinefirst.service.importjob;

import com.hnp.backendofflinefirst.domain.ImportEntityType;
import com.hnp.backendofflinefirst.domain.ImportJobStatus;
import com.hnp.backendofflinefirst.dto.ImportError;
import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.ImportJob;
import com.hnp.backendofflinefirst.entity.ImportJobError;
import com.hnp.backendofflinefirst.repository.ImportJobErrorRepository;
import com.hnp.backendofflinefirst.repository.ImportJobRepository;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.util.ExcelUtils;
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
        assertNoActiveImport();

        String jobUuid = UUID.randomUUID().toString();
        Path stored = fileStorageService.store(jobUuid, file);
        try {
            int dataRows = ExcelUtils.countDataRows(stored);
            ExcelUtils.assertWithinImportRowLimit(dataRows, storageProperties.getMaxRows());

            ImportJob job = new ImportJob();
            job.setJobUuid(jobUuid);
            job.setEntityType(entityType.getCode());
            job.setStatus(ImportJobStatus.PENDING);
            job.setFileName(originalName);
            job.setFilePath(stored.toString());
            job.setFileSize(file.getSize());
            job.setTotalRows(dataRows);
            job.setSubmittedByUserId(userId);
            job.setCreatedAt(System.currentTimeMillis());
            importJobRepository.save(job);
            log.info("[IMPORT_JOB] submitted jobUuid={} entityType={} filePath={} userId={} jobId={} rows={}",
                    jobUuid, entityType.getCode(), stored, userId, job.getId(), dataRows);

            scheduleRun(job.getId());
            return job;
        } catch (RuntimeException | IOException ex) {
            fileStorageService.deleteQuietly(stored.toString());
            throw ex;
        }
    }

    /** True when any import is queued or running (system-wide sequential safety). */
    @Transactional(readOnly = true)
    public boolean hasActiveImport() {
        return importJobRepository.existsByStatusIn(
                EnumSet.of(ImportJobStatus.PENDING, ImportJobStatus.RUNNING));
    }

    public int maxRowsPerFile() {
        return storageProperties.getMaxRows();
    }

    private void assertNoActiveImport() {
        if (hasActiveImport()) {
            throw new IllegalArgumentException(
                    "Another import is already queued or running. Wait for it to finish, then submit the next file.");
        }
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

    /**
     * Force-terminates an active job whose worker is gone, without waiting for a restart.
     * <p>
     * {@link #cancel} is cooperative: it raises a flag the running thread reads at its next
     * progress tick. That is the right mechanism while the thread is alive, and useless once
     * it is not — and a job stuck at RUNNING blocks {@link #assertNoActiveImport()} for every
     * user, not just its owner. This is the escape hatch for that case.
     * <p>
     * The cancel flag is raised as well. If the thread turns out to still be alive it stops
     * at its next tick, and its {@code complete}/{@code cancelComplete} call then finds a
     * terminal row and leaves this decision standing.
     */
    @Transactional
    public void abandon(String jobUuid, Long userId) {
        ImportJob job = requireJobForUser(jobUuid, userId);
        if (!job.getStatus().isActive()) {
            throw new IllegalArgumentException("Import job is not active.");
        }
        cancellationRegistry.requestCancel(job.getId());
        job.setStatus(ImportJobStatus.FAILED);
        job.setErrorMessage("Import abandoned by user; the worker was no longer responding.");
        job.setCompletedAt(System.currentTimeMillis());
        importJobRepository.save(job);
        fileStorageService.deleteQuietly(job.getFilePath());
        log.warn("[IMPORT_JOB] abandoned by user jobId={} jobUuid={} processedRows={} userId={}",
                job.getId(), job.getJobUuid(), job.getProcessedRows(), userId);
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
        long now = System.currentTimeMillis();
        job.setStatus(ImportJobStatus.RUNNING);
        job.setStartedAt(now);
        job.setHeartbeatAt(now);
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
            // Doubles as the liveness signal ImportJobWatchdog reads: a tick means the worker
            // thread reached this line, which a dead thread cannot do.
            job.setHeartbeatAt(System.currentTimeMillis());
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
        // A worker that was declared dead (watchdog or admin abandon) and then turns out to
        // be alive must not resurrect the job: the row already carries a terminal outcome
        // somebody acted on, and flipping it back to COMPLETED would claim an import
        // finished cleanly when it was written off minutes earlier.
        if (!job.getStatus().isActive()) {
            log.warn("[IMPORT_JOB] late completion ignored — jobId={} already {}", jobId, job.getStatus());
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
        if (!job.getStatus().isActive()) {
            log.warn("[IMPORT_JOB] late failure ignored — jobId={} already {}", jobId, job.getStatus());
            return;
        }
        job.setStatus(ImportJobStatus.FAILED);
        job.setErrorMessage(truncateMessage(message));
        job.setCompletedAt(System.currentTimeMillis());
        importJobRepository.save(job);
        fileStorageService.deleteQuietly(job.getFilePath());
    }

    /**
     * Records FAILED with a native statement, for use when {@link #fail} has itself thrown.
     * <p>
     * This is not paranoia about a hypothetical. The failure handler used to be the second
     * casualty of the same audit-queue overflow that killed the import: {@code fail()} saves
     * the entity, the save enqueued an audit task, the queue was still full, and the
     * rejection escaped from inside the catch block — so the row kept saying RUNNING while
     * nothing was running. Excluding ImportJob from the audit trail removed that particular
     * coupling; this removes the class of it. Recording *that* something failed must not
     * depend on anything that can fail.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void forceFail(Long jobId, String message) {
        importJobRepository.forceTerminalStatus(
                jobId, ImportJobStatus.FAILED.name(), truncateMessage(message), System.currentTimeMillis());
    }

    /** error_message is TEXT, but a stack-trace-length message helps nobody in a table cell. */
    private static String truncateMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 497) + "...";
    }

    /**
     * Fails RUNNING jobs that stopped reporting progress before {@code cutoff}. Returns how
     * many were cleared. Driven by {@link ImportJobWatchdog}.
     */
    @Transactional
    public int failStaleRunningJobs(long cutoff) {
        List<ImportJob> stale = importJobRepository.findStaleRunning(cutoff);
        long now = System.currentTimeMillis();
        for (ImportJob job : stale) {
            // If the thread is somehow alive but wedged, this stops it at its next tick;
            // its late complete()/fail() then finds a terminal row and stands down.
            cancellationRegistry.requestCancel(job.getId());
            job.setStatus(ImportJobStatus.FAILED);
            job.setErrorMessage("Import stopped reporting progress and was declared failed.");
            job.setCompletedAt(now);
            importJobRepository.save(job);
            fileStorageService.deleteQuietly(job.getFilePath());
            log.error("[IMPORT_JOB] stale job failed by watchdog jobId={} jobUuid={} processedRows={}/{} lastHeartbeat={}",
                    job.getId(), job.getJobUuid(), job.getProcessedRows(), job.getTotalRows(), job.getHeartbeatAt());
        }
        return stale.size();
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
        return SecurityUtils.hasCapability(Capabilities.IMPORT_JOB_VIEW_ALL);
    }
}
