package com.hnp.backendofflinefirst.domain;

/**
 * What a log-sheet attachment holds.
 *
 * <p>{@code VIDEO} exists in the schema and is accepted end-to-end, but the PWA does not offer
 * it yet: unlike photos and audio there is no practical way to compress video in the browser,
 * so a capture would upload at whatever size the tablet camera produced — which is exactly the
 * uncontrolled growth the rest of this design avoids. Kept here so enabling it later is a UI
 * change, not a migration.
 */
public enum AttachmentKind {
    IMAGE,
    AUDIO,
    VIDEO;

    public static AttachmentKind fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The field data types that store attachments, mapped to the kind they accept. */
    public static AttachmentKind forFieldDataType(String dataType) {
        if (dataType == null) {
            return null;
        }
        return switch (dataType.trim().toLowerCase()) {
            case "image" -> IMAGE;
            case "audio" -> AUDIO;
            case "video" -> VIDEO;
            default -> null;
        };
    }
}
