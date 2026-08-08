package com.hnp.backendofflinefirst.domain;

/**
 * What caused a status change.
 *
 * <p>{@link #MANUAL} is not written yet — it is here so a future admin edit of an asset's status
 * has a home without a schema change, and so a reader of the history is never left guessing
 * whether an unlabelled row came from a sheet.
 */
public enum AssetStatusSource {
    LOG_SHEET,
    MANUAL
}
