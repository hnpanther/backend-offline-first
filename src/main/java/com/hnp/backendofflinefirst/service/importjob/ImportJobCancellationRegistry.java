package com.hnp.backendofflinefirst.service.importjob;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ImportJobCancellationRegistry {

    private final ConcurrentHashMap<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    public void requestCancel(Long jobId) {
        cancelFlags.computeIfAbsent(jobId, id -> new AtomicBoolean()).set(true);
    }

    public boolean isCancelled(Long jobId) {
        AtomicBoolean flag = cancelFlags.get(jobId);
        return flag != null && flag.get();
    }

    public void clear(Long jobId) {
        cancelFlags.remove(jobId);
    }
}
