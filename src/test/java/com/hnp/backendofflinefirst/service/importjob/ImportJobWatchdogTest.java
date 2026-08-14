package com.hnp.backendofflinefirst.service.importjob;

import com.hnp.backendofflinefirst.config.ImportStorageProperties;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportJobWatchdogTest {

    @Mock ImportJobService importJobService;
    @Mock BusinessEventLogger businessEventLogger;

    ImportJobWatchdog watchdogWithTimeout(int minutes) {
        ImportStorageProperties properties = new ImportStorageProperties();
        properties.setStaleTimeoutMinutes(minutes);
        return new ImportJobWatchdog(importJobService, properties, businessEventLogger);
    }

    @Test
    void passesACutoffOneTimeoutInThePast() {
        ImportJobWatchdog watchdog = watchdogWithTimeout(15);
        when(importJobService.failStaleRunningJobs(anyLong())).thenReturn(0);

        long before = System.currentTimeMillis();
        watchdog.failStaleJobs();

        ArgumentCaptor<Long> cutoff = ArgumentCaptor.forClass(Long.class);
        verify(importJobService).failStaleRunningJobs(cutoff.capture());
        long expected = before - TimeUnit.MINUTES.toMillis(15);
        // Only jobs silent for longer than the timeout are in scope; a cutoff computed the
        // other way round would fail every import the moment it started.
        assertThat(cutoff.getValue()).isBetween(expected - 5_000, expected + 5_000);
    }

    @Test
    void zeroTimeoutDisablesTheWatchdogEntirely() {
        ImportJobWatchdog watchdog = watchdogWithTimeout(0);

        watchdog.failStaleJobs();

        verify(importJobService, never()).failStaleRunningJobs(anyLong());
    }

    @Test
    void reportsThroughTheBusinessLogOnlyWhenSomethingWasCleared() {
        ImportJobWatchdog watchdog = watchdogWithTimeout(15);
        when(importJobService.failStaleRunningJobs(anyLong())).thenReturn(2);

        watchdog.failStaleJobs();

        verify(businessEventLogger).error("IMPORT_WATCHDOG", "clearedStaleJobs=2");
    }

    @Test
    void aFailedPassDoesNotKillTheSchedule() {
        ImportJobWatchdog watchdog = watchdogWithTimeout(15);
        when(importJobService.failStaleRunningJobs(anyLong()))
                .thenThrow(new IllegalStateException("db hiccup"));

        // An exception escaping a @Scheduled method stops nothing by itself, but a watchdog
        // that throws every minute buries the log and teaches people to ignore it. More to
        // the point, the next pass must still run — this is the safety net for the case
        // where everything else has already failed.
        watchdog.failStaleJobs();

        verify(importJobService).failStaleRunningJobs(anyLong());
    }
}
