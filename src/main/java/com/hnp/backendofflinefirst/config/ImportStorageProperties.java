package com.hnp.backendofflinefirst.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.import")
@Getter
@Setter
public class ImportStorageProperties {

    private String storagePath = "./data/imports";

    /** Max row-level errors persisted per job. */
    private int maxStoredErrors = 500;

    /**
     * Safety limit for Excel import data rows (excluding header). Large initial loads
     * should be split into sequential files of at most this size.
     */
    private int maxRows = 10_000;

    /**
     * How long a RUNNING job may go without a progress tick before the watchdog declares it
     * failed. {@code 0} disables the watchdog.
     * <p>
     * A healthy import ticks every 25 rows, so anything above a couple of minutes is already
     * generous; the default is deliberately far beyond that, because wrongly failing a live
     * import costs a redo while leaving a dead one in place blocks every user's imports until
     * the next restart.
     */
    private int staleTimeoutMinutes = 15;
}
