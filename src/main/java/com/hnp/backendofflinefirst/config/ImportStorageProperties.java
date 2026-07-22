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
}
