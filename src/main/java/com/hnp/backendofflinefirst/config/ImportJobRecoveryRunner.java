package com.hnp.backendofflinefirst.config;

import com.hnp.backendofflinefirst.service.importjob.ImportJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportJobRecoveryRunner implements ApplicationRunner {

    private final ImportJobService importJobService;

    @Override
    public void run(ApplicationArguments args) {
        importJobService.recoverStaleRunningJobs();
        log.info("Import job recovery completed for stale PENDING/RUNNING jobs.");
    }
}
