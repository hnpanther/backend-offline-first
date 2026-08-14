package com.hnp.backendofflinefirst.service.importjob;

import com.hnp.backendofflinefirst.domain.ImportJobStatus;
import com.hnp.backendofflinefirst.entity.ImportJob;
import com.hnp.backendofflinefirst.repository.ImportJobRepository;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the runner behaves when the import does <em>not</em> go to plan.
 *
 * <p>Every case here corresponds to a way a job used to end up stranded at {@code RUNNING},
 * which is far worse than a plain failure: {@code assertNoActiveImport()} is system-wide, so
 * one wedged row blocked every user's next import until somebody restarted the application.
 */
@ExtendWith(MockitoExtension.class)
class ImportJobRunnerTest {

    @Mock ImportJobRepository importJobRepository;
    @Mock ImportJobService importJobService;
    @Mock ExcelImportService excelImportService;

    @InjectMocks ImportJobRunner runner;

    @Test
    void anOutOfMemoryErrorStillMarksTheJobFailed() throws Exception {
        arrangePendingJob();
        // The realistic death of a large import: the whole workbook is read into memory at
        // once. OutOfMemoryError is an Error, so the old `catch (Exception)` never saw it,
        // the thread ended, and the row said RUNNING forever.
        when(excelImportService.importEntity(any(), any(), any()))
                .thenThrow(new OutOfMemoryError("Java heap space"));

        assertThatThrownBy(() -> runner.runAsync(1L)).isInstanceOf(OutOfMemoryError.class);

        verify(importJobService).fail(eq(1L), anyString());
    }

    @Test
    void anOrdinaryExceptionIsNotRethrown() throws Exception {
        arrangePendingJob();
        when(excelImportService.importEntity(any(), any(), any()))
                .thenThrow(new IllegalStateException("bad row"));

        runner.runAsync(1L);

        verify(importJobService).fail(eq(1L), eq("bad row"));
    }

    @Test
    void whenRecordingTheFailureItselfFailsTheStatusIsForced() throws Exception {
        arrangePendingJob();
        when(excelImportService.importEntity(any(), any(), any()))
                .thenThrow(new IllegalStateException("bad row"));
        // Exactly what used to happen: fail() saves the entity, the save enqueued an audit
        // task, the audit queue was full from the same import, and the rejection escaped
        // from inside the catch block — so nothing was ever written.
        doThrow(new TaskRejectedException("ExecutorService in active state did not accept task"))
                .when(importJobService).fail(eq(1L), anyString());

        runner.runAsync(1L);

        verify(importJobService).forceFail(eq(1L), eq("bad row"));
    }

    @Test
    void whenEvenTheForcedWriteFailsTheRunnerStillReturns() throws Exception {
        arrangePendingJob();
        when(excelImportService.importEntity(any(), any(), any()))
                .thenThrow(new IllegalStateException("bad row"));
        doThrow(new TaskRejectedException("nope")).when(importJobService).fail(eq(1L), anyString());
        doThrow(new IllegalStateException("db down")).when(importJobService).forceFail(eq(1L), anyString());

        // Nothing more this process can do; the watchdog clears the row. What must not happen
        // is an exception propagating out of an @Async method and being lost.
        runner.runAsync(1L);

        verify(importJobService).forceFail(eq(1L), anyString());
    }

    @Test
    void aCancelledImportIsMarkedCancelledNotFailed() throws Exception {
        arrangePendingJob();
        when(excelImportService.importEntity(any(), any(), any()))
                .thenThrow(new ImportJobCancelledException());

        runner.runAsync(1L);

        verify(importJobService).cancelComplete(1L);
        verify(importJobService, never()).fail(any(), anyString());
    }

    @Test
    void aJobThatIsNoLongerPendingIsNotRun() throws Exception {
        ImportJob job = job();
        job.setStatus(ImportJobStatus.CANCELLED);
        when(importJobRepository.findById(1L)).thenReturn(Optional.of(job));

        runner.runAsync(1L);

        verify(excelImportService, never()).importEntity(any(), any(), any());
        verify(importJobService, never()).fail(any(), anyString());
    }

    private void arrangePendingJob() {
        when(importJobRepository.findById(1L)).thenReturn(Optional.of(job()));
        when(importJobService.tryMarkRunning(eq(1L), any(Integer.class))).thenReturn(true);
    }

    private static ImportJob job() {
        ImportJob job = new ImportJob();
        job.setId(1L);
        job.setJobUuid("job-1");
        job.setStatus(ImportJobStatus.PENDING);
        job.setEntityType("asset-entries");
        job.setFilePath("missing.xlsx");
        job.setTotalRows(10);
        job.setSubmittedByUserId(1L);
        return job;
    }
}
