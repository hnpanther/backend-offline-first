package com.hnp.backendofflinefirst.service;

/**
 * The client-minted attachment id already names somebody else's file, on another sheet or field
 * (issue B-10).
 *
 * <p>The id is written into {@code form_data} before the bytes are uploaded, and anyone who may
 * read the round may read that {@code form_data}. A colleague could therefore upload their own file
 * under the id first; the owner's upload then met the idempotent branch, which used to answer 403 —
 * retried by the tablet on every pass, so the photograph never landed.
 *
 * <p>Answered {@code 409} with {@link #CODE}, which the device branches on: it mints a fresh id,
 * rewrites its own reference to it and uploads again. The squatting row is then harmless — it
 * belongs to a sheet nobody's reference names.
 */
public class AttachmentIdTakenException extends IllegalStateException {

    /** The stable token the device matches; never reworded. */
    public static final String CODE = "ATTACHMENT_ID_TAKEN";

    public AttachmentIdTakenException() {
        super("Attachment id is already used by another file.");
    }
}
