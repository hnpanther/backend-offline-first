package com.hnp.backendofflinefirst.domain;

public enum ImportJobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public String faLabel() {
        return switch (this) {
            case PENDING -> "در صف";
            case RUNNING -> "در حال اجرا";
            case COMPLETED -> "پایان یافته";
            case FAILED -> "خطا";
            case CANCELLED -> "متوقف شده";
        };
    }

    public boolean isActive() {
        return this == PENDING || this == RUNNING;
    }
}
