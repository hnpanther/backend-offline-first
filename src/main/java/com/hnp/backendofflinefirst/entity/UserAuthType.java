package com.hnp.backendofflinefirst.entity;

public enum UserAuthType {
    LOCAL,
    ACTIVE_DIRECTORY,
    HYBRID;

    public String faLabel() {
        return switch (this) {
            case LOCAL -> "محلی";
            case ACTIVE_DIRECTORY -> "اکتیو دایرکتوری";
            case HYBRID -> "ترکیبی (محلی + AD)";
        };
    }
}
