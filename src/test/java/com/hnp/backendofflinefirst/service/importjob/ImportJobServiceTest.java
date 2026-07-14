package com.hnp.backendofflinefirst.service.importjob;

import com.hnp.backendofflinefirst.config.ImportStorageProperties;
import com.hnp.backendofflinefirst.domain.ImportJobStatus;
import com.hnp.backendofflinefirst.entity.ImportJob;
import com.hnp.backendofflinefirst.repository.ImportJobErrorRepository;
import com.hnp.backendofflinefirst.repository.ImportJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
