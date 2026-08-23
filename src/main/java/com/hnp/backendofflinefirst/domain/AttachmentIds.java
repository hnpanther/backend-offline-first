package com.hnp.backendofflinefirst.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one rule for what a client-minted attachment id may be.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The id is accepted from the client and then becomes part of a filesystem path. It used to be
 * accepted as any non-blank string and made path-safe by <b>silently stripping</b> everything
 * outside {@code [A-Za-z0-9-]}, which meant two different ids could name the same file:
 * {@code abc!} and {@code abc@} both became {@code abc}.
 *
 * <p>That was not a cosmetic collision. The file is written <em>before</em> the row is inserted,
 * with {@code REPLACE_EXISTING}; the insert then failed on {@code uk_attachments_storage_key} and
 * the transaction rolled the database back — but not the filesystem. The first attachment's row
 * survived pointing at the second attachment's bytes. Because the storage path is global while
 * only the row is scoped to a log sheet, an operator could overwrite a photograph on a sheet they
 * have no access to at all: upload to their own sheet using the victim's id plus one punctuation
 * character.
 *
 * <p>A second variant produced no error whatever. {@code ABC-…} and {@code abc-…} are different
 * strings, so the unique constraint never fired; both rows committed, and on a case-insensitive
 * filesystem (Windows) both pointed at one file. Two healthy-looking rows, one content — and
 * deleting either took the other's bytes with it.
 *
 * <p>Both vanish once an id must be a canonical UUID: the character set of a UUID is already
 * path-safe, so nothing is ever stripped, and lower-casing removes the case collision.
 *
 * <h2>Lower-cased, and why the lookup stays tolerant</h2>
 *
 * <p>{@link #canonicalise} lower-cases, so a single attachment can never have two spellings. Both
 * shipping clients already mint lower-case UUIDs — the PWA through {@code uuidv4()} and the web
 * fill page through {@code UUID.randomUUID()} — so this changes nothing for real traffic. The
 * upload path still looks a row up by the id <b>as sent</b> before falling back to the canonical
 * form, so a row written by some older caller in upper case is still found and returned
 * idempotently rather than being re-uploaded into a unique-constraint failure.
 */
public final class AttachmentIds {

    /** RFC 4122 textual form, any version, either case. */
    private static final Pattern UUID_FORM = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private AttachmentIds() {
    }

    /** True when this is a textual UUID and therefore safe to use as a file name. */
    public static boolean isCanonicalForm(String attachmentId) {
        return attachmentId != null && UUID_FORM.matcher(attachmentId).matches();
    }

    /**
     * The id to store, or a refusal.
     *
     * @throws IllegalArgumentException when the id is not a textual UUID. Refusing is the point:
     *                                  accepting and repairing it is what let two ids share a file.
     */
    public static String canonicalise(String attachmentId) {
        String trimmed = attachmentId == null ? "" : attachmentId.trim();
        if (!isCanonicalForm(trimmed)) {
            throw new IllegalArgumentException("Attachment id must be a UUID.");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
