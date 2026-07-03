package com.hnp.backendofflinefirst.dto;

/** Single import row error (English message for logs/API; translate in UI layer). */
public record ImportError(int row, String message) {}
