package com.hnp.backendofflinefirst.service.importjob;

@FunctionalInterface
public interface ImportProgressListener {
    void onProgress(int processedRows, int totalRows);
}
