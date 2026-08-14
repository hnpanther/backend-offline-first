package com.hnp.backendofflinefirst.service.importjob;

import com.hnp.backendofflinefirst.config.ImportStorageProperties;
import com.hnp.backendofflinefirst.domain.ImportJobStatus;
import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.ImportJob;
import com.hnp.backendofflinefirst.repository.ImportJobErrorRepository;
import com.hnp.backendofflinefirst.repository.ImportJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportJobServiceTest {

    @Mock ImportJobRepository importJobRepository;
    @Mock ImportJobErrorRepository importJobErrorRepository;
    @Mock ImportFileStorageService fileStorageService;
    @Mock ImportStorageProperties storageProperties;
    @Mock ImportJobRunner importJobRunner;
    @Mock ImportJobCancellationRegistry cancellationRegistry;

    @InjectMocks ImportJobService importJobService;

    @Test
    void cancelPendingJobMarksCancelledAndDeletesFile() {
        ImportJob job = pendingJob();
        when(importJobRepository.findByJobUuid("job-1")).thenReturn(Optional.of(job));

        importJobService.cancel("job-1", 1L);

        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.CANCELLED);
        assertThat(job.getCompletedAt()).isNotNull();
        verify(fileStorageService).deleteQuietly(job.getFilePath());
        verify(cancellationRegistry).clear(10L);
    }

    @Test
    void cancelRunningJobRequestsCooperativeStop() {
        ImportJob job = pendingJob();
        job.setStatus(ImportJobStatus.RUNNING);
        when(importJobRepository.findByJobUuid("job-1")).thenReturn(Optional.of(job));

        importJobService.cancel("job-1", 1L);

        verify(cancellationRegistry).requestCancel(10L);
        verify(importJobRepository, never()).save(any());
    }

    @Test
    void deleteTerminalJobRemovesRecordAndErrors() {
        ImportJob job = pendingJob();
        job.setStatus(ImportJobStatus.FAILED);
        when(importJobRepository.findByJobUuid("job-1")).thenReturn(Optional.of(job));

        importJobService.delete("job-1", 1L);

        verify(importJobErrorRepository).deleteByJobId(10L);
        verify(fileStorageService).deleteQuietly(job.getFilePath());
        verify(importJobRepository).delete(job);
        verify(cancellationRegistry).clear(10L);
    }

    @Test
    void deleteActiveJobRejected() {
        ImportJob job = pendingJob();
        when(importJobRepository.findByJobUuid("job-1")).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> importJobService.delete("job-1", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stop the import job");

        verify(importJobRepository, never()).delete(any());
    }

    @Test
    void tryMarkRunningSkipsWhenJobNoLongerPending() {
        ImportJob job = pendingJob();
        job.setStatus(ImportJobStatus.CANCELLED);
        when(importJobRepository.findById(10L)).thenReturn(Optional.of(job));

        boolean started = importJobService.tryMarkRunning(10L, 5);

        assertThat(started).isFalse();
        verify(importJobRepository, never()).save(any());
    }

    @Test
    void cancelCompleteMarksCancelledForActiveJob() {
        ImportJob job = pendingJob();
        job.setStatus(ImportJobStatus.RUNNING);
        when(importJobRepository.findById(10L)).thenReturn(Optional.of(job));

        importJobService.cancelComplete(10L);

        ArgumentCaptor<ImportJob> captor = ArgumentCaptor.forClass(ImportJob.class);
        verify(importJobRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ImportJobStatus.CANCELLED);
        verify(fileStorageService).deleteQuietly(eq(job.getFilePath()));
        verify(cancellationRegistry).clear(10L);
    }

    // ── Abandoning a job whose worker is gone ─────────────────────────────────

    @Test
    void abandonMarksRunningJobFailedAndRaisesCancelFlag() {
        ImportJob job = pendingJob();
        job.setStatus(ImportJobStatus.RUNNING);
        when(importJobRepository.findByJobUuid("job-1")).thenReturn(Optional.of(job));

        importJobService.abandon("job-1", 1L);

        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(job.getCompletedAt()).isNotNull();
        assertThat(job.getErrorMessage()).contains("abandoned by user");
        // Raised in case the thread is merely wedged rather than dead: it then stops at its
        // next tick instead of carrying on against a row that says FAILED.
        verify(cancellationRegistry).requestCancel(10L);
        verify(fileStorageService).deleteQuietly(job.getFilePath());
    }

    @Test
    void abandonRejectsAlreadyTerminalJob() {
        ImportJob job = pendingJob();
        job.setStatus(ImportJobStatus.COMPLETED);
        when(importJobRepository.findByJobUuid("job-1")).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> importJobService.abandon("job-1", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not active");

        verify(importJobRepository, never()).save(any());
    }

    @Test
    void deleteWorksAfterAbandon() {
        ImportJob job = pendingJob();
        job.setStatus(ImportJobStatus.RUNNING);
        when(importJobRepository.findByJobUuid("job-1")).thenReturn(Optional.of(job));

        importJobService.abandon("job-1", 1L);
        importJobService.delete("job-1", 1L);

        verify(importJobRepository).delete(job);
    }

    // ── A late worker must not overwrite a decision already taken ─────────────

    @Test
    void completeIgnoredWhenJobWasAlreadyAbandoned() {
        ImportJob job = pendingJob();
        job.setStatus(ImportJobStatus.FAILED);
        when(importJobRepository.findById(10L)).thenReturn(Optional.of(job));

        importJobService.complete(10L, new ImportResult());

        // Flipping back to COMPLETED would claim a clean finish for an import somebody wrote
        // off minutes earlier and already restarted.
        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.FAILED);
        verify(importJobRepository, never()).save(any());
    }

    @Test
    void failIgnoredWhenJobWasAlreadyAbandoned() {
        ImportJob job = pendingJob();
        job.setStatus(ImportJobStatus.CANCELLED);
        when(importJobRepository.findById(10L)).thenReturn(Optional.of(job));

        importJobService.fail(10L, "boom");

        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.CANCELLED);
        assertThat(job.getErrorMessage()).isNull();
        verify(importJobRepository, never()).save(any());
    }

    @Test
    void failTruncatesAnOverlongMessage() {
        ImportJob job = pendingJob();
        job.setStatus(ImportJobStatus.RUNNING);
        when(importJobRepository.findById(10L)).thenReturn(Optional.of(job));

        importJobService.fail(10L, "x".repeat(900));

        assertThat(job.getErrorMessage()).hasSize(500).endsWith("...");
    }

    @Test
    void forceFailBypassesTheEntityAndWritesNatively() {
        importJobService.forceFail(10L, "worker gone");

        // Native UPDATE: no persistence context, no audit aspect, nothing left to reject —
        // which is the entire reason this path exists.
        verify(importJobRepository).forceTerminalStatus(
                eq(10L), eq("FAILED"), eq("worker gone"), anyLong());
        verify(importJobRepository, never()).save(any());
    }

    // ── Heartbeat and the watchdog ────────────────────────────────────────────

    @Test
    void tryMarkRunningStampsTheFirstHeartbeat() {
        ImportJob job = pendingJob();
        when(importJobRepository.findById(10L)).thenReturn(Optional.of(job));

        long before = System.currentTimeMillis();
        boolean started = importJobService.tryMarkRunning(10L, 5);

        assertThat(started).isTrue();
        assertThat(job.getHeartbeatAt()).isNotNull().isGreaterThanOrEqualTo(before);
    }

    @Test
    void updateProgressRefreshesTheHeartbeat() {
        ImportJob job = pendingJob();
        job.setStatus(ImportJobStatus.RUNNING);
        job.setHeartbeatAt(1_000L);
        when(importJobRepository.findById(10L)).thenReturn(Optional.of(job));

        importJobService.updateProgress(10L, 50, 100);

        assertThat(job.getProcessedRows()).isEqualTo(50);
        assertThat(job.getHeartbeatAt()).isGreaterThan(1_000L);
    }

    @Test
    void failStaleRunningJobsClearsThemAndReleasesTheSubmitGate() {
        ImportJob stale = pendingJob();
        stale.setStatus(ImportJobStatus.RUNNING);
        stale.setHeartbeatAt(1_000L);
        when(importJobRepository.findStaleRunning(5_000L)).thenReturn(List.of(stale));

        int cleared = importJobService.failStaleRunningJobs(5_000L);

        assertThat(cleared).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(stale.getErrorMessage()).contains("stopped reporting progress");
        verify(cancellationRegistry).requestCancel(10L);
        verify(fileStorageService).deleteQuietly(stale.getFilePath());
    }

    @Test
    void failStaleRunningJobsLeavesALiveJobAlone() {
        when(importJobRepository.findStaleRunning(5_000L)).thenReturn(List.of());

        assertThat(importJobService.failStaleRunningJobs(5_000L)).isZero();

        verify(importJobRepository, never()).save(any());
    }

    @Test
    void hasActiveImportWhenPendingOrRunningExists() {
        when(importJobRepository.existsByStatusIn(any())).thenReturn(true);

        assertThat(importJobService.hasActiveImport()).isTrue();
    }

    @Test
    void maxRowsPerFileReadsConfiguredLimit() {
        when(storageProperties.getMaxRows()).thenReturn(10_000);

        assertThat(importJobService.maxRowsPerFile()).isEqualTo(10_000);
    }

    private static ImportJob pendingJob() {
        ImportJob job = new ImportJob();
        job.setId(10L);
        job.setJobUuid("job-1");
        job.setStatus(ImportJobStatus.PENDING);
        job.setSubmittedByUserId(1L);
        job.setFilePath("/tmp/test.xlsx");
        return job;
    }
}
