package com.hnp.backendofflinefirst.service.importjob;

/**
 * Thrown when a running import job is cooperatively cancelled between row batches.
 */
public class ImportJobCancelledException extends RuntimeException {

    public ImportJobCancelledException() {
        super("Import job cancelled.");
    }
}
