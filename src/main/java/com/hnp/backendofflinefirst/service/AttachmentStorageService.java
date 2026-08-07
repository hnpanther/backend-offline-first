package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AttachmentKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Reads and writes attachment bytes on the filesystem.
 *
 * <p>The storage root comes from {@code app.attachments.storage-dir}. Files are date-sharded
 * ({@code 2026/08/06/<uuid>.<ext>}) so no directory ever accumulates a year of uploads —
 * some filesystems and most file managers degrade badly past a few tens of thousands of
 * entries in one folder.
 *
 * <p>Two rules here are security, not tidiness:
 * <ul>
 *   <li><b>The declared content type is never trusted.</b> {@link #detectMimeType} reads the
 *       file's magic bytes. A client can label a {@code .exe} as {@code image/webp}; only the
 *       first few bytes tell the truth, and serving it back later with the claimed type would
 *       be the exploit.</li>
 *   <li><b>Storage keys are never taken from input.</b> They are generated here from the
 *       attachment id, and {@link #resolveWithinRoot} re-checks that the resolved path is
 *       still inside the root before any read or delete — otherwise a crafted key like
 *       {@code ../../etc/passwd} would escape it.</li>
 * </ul>
 */
@Slf4j
@Service
public class AttachmentStorageService {

    /** Longest magic-byte prefix any signature below needs. */
    private static final int SNIFF_BYTES = 32;

    private final Path root;

    public AttachmentStorageService(@Value("${app.attachments.storage-dir}") String storageDir) {
        this.root = Paths.get(storageDir).toAbsolutePath().normalize();
    }

    /** Absolute storage root — surfaced so startup logging and tests can report it. */
    public Path getRoot() {
        return root;
    }

    /**
     * Writes the bytes and returns the storage key to record on the row.
     *
     * <p>Writes to a temporary file and then moves it into place: a half-written file that a
     * later read would treat as valid is worse than no file, and the move is atomic on the
     * same filesystem.
     */
    public String store(String attachmentId, byte[] content, String mimeType) throws IOException {
        String key = buildStorageKey(attachmentId, mimeType);
        Path target = resolveWithinRoot(key);
        Files.createDirectories(target.getParent());

        Path temp = Files.createTempFile(target.getParent(), "upload-", ".part");
        try {
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        return key;
    }

    public byte[] read(String storageKey) throws IOException {
        return Files.readAllBytes(resolveWithinRoot(storageKey));
    }

    public boolean exists(String storageKey) {
        try {
            return Files.isRegularFile(resolveWithinRoot(storageKey));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Best-effort delete; a missing file is not an error, the row is the source of truth. */
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolveWithinRoot(storageKey));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not delete attachment file {}: {}", storageKey, e.getMessage());
        }
    }

    public static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The real content type, read from the file's leading bytes.
     *
     * @return the detected type, or {@code null} when the bytes match nothing we accept —
     *         which the caller must treat as a rejection, not as "unknown but probably fine".
     */
    public static String detectMimeType(byte[] content) {
        if (content == null || content.length < 12) {
            return null;
        }
        // JPEG: FF D8 FF
        if (u(content[0]) == 0xFF && u(content[1]) == 0xD8 && u(content[2]) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (u(content[0]) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G'
                && u(content[4]) == 0x0D && u(content[5]) == 0x0A && u(content[6]) == 0x1A && u(content[7]) == 0x0A) {
            return "image/png";
        }
        // RIFF container: "RIFF" ... then "WEBP" at offset 8. Same container also carries WAV.
        if (content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F') {
            if (content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
                return "image/webp";
            }
            if (content[8] == 'W' && content[9] == 'A' && content[10] == 'V' && content[11] == 'E') {
                return "audio/wav";
            }
        }
        // Matroska/WebM (EBML): 1A 45 DF A3. Audio and video share it, so the caller's expected
        // kind decides which label applies — see resolveWebmType.
        if (u(content[0]) == 0x1A && u(content[1]) == 0x45 && u(content[2]) == 0xDF && u(content[3]) == 0xA3) {
            return "application/x-matroska";
        }
        // MP4 / M4A family: "ftyp" at offset 4.
        if (content[4] == 'f' && content[5] == 't' && content[6] == 'y' && content[7] == 'p') {
            String brand = new String(content, 8, 4).toLowerCase(Locale.ROOT);
            return brand.startsWith("m4a") ? "audio/mp4" : "video/mp4";
        }
        // OGG: "OggS"
        if (content[0] == 'O' && content[1] == 'g' && content[2] == 'g' && content[3] == 'S') {
            return "audio/ogg";
        }
        // MP3: ID3 tag, or a frame sync FF Ex/Fx
        if (content[0] == 'I' && content[1] == 'D' && content[2] == '3') {
            return "audio/mpeg";
        }
        if (u(content[0]) == 0xFF && (u(content[1]) & 0xE0) == 0xE0) {
            return "audio/mpeg";
        }
        return null;
    }

    /**
     * Resolves the ambiguous Matroska container against what the field expects.
     *
     * <p>{@code MediaRecorder} produces {@code audio/webm} and {@code video/webm} with an
     * identical EBML header, so the bytes alone cannot separate them. Deciding by the field's
     * declared kind is safe because the kind is server-side data, not client input.
     */
    public static String resolveWebmType(String detected, AttachmentKind expectedKind) {
        if (!"application/x-matroska".equals(detected)) {
            return detected;
        }
        return expectedKind == AttachmentKind.AUDIO ? "audio/webm" : "video/webm";
    }

    /** Whether a detected type is acceptable for the field's kind. */
    public static boolean matchesKind(String mimeType, AttachmentKind kind) {
        if (mimeType == null || kind == null) {
            return false;
        }
        return switch (kind) {
            case IMAGE -> mimeType.startsWith("image/");
            case AUDIO -> mimeType.startsWith("audio/");
            case VIDEO -> mimeType.startsWith("video/");
        };
    }

    static String buildStorageKey(String attachmentId, String mimeType) {
        LocalDate today = LocalDate.now();
        return String.format("%04d/%02d/%02d/%s%s",
                today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
                sanitiseId(attachmentId), extensionFor(mimeType));
    }

    /**
     * The id becomes part of a filesystem path, so anything that is not a plain UUID character
     * is stripped rather than escaped — there is no legitimate id containing a separator.
     */
    private static String sanitiseId(String attachmentId) {
        String cleaned = attachmentId == null ? "" : attachmentId.replaceAll("[^A-Za-z0-9-]", "");
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("Attachment id is not usable as a file name.");
        }
        return cleaned;
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null) return "";
        return switch (mimeType) {
            case "image/webp" -> ".webp";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "audio/webm" -> ".weba";
            case "audio/ogg" -> ".ogg";
            case "audio/mpeg" -> ".mp3";
            case "audio/mp4" -> ".m4a";
            case "audio/wav" -> ".wav";
            case "video/webm" -> ".webm";
            case "video/mp4" -> ".mp4";
            default -> "";
        };
    }

    /**
     * Resolves a storage key under the root, refusing anything that escapes it.
     *
     * <p>Keys are generated by this class, so an escaping key means either corruption or an
     * attempt — either way the only safe response is to refuse rather than to normalise.
     */
    private Path resolveWithinRoot(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("Attachment storage key is required.");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Attachment storage key escapes the storage root.");
        }
        return resolved;
    }

    private static int u(byte b) {
        return b & 0xFF;
    }

    /** Reads a stream fully, refusing anything over the cap without buffering the whole body. */
    public static byte[] readAtMost(InputStream in, long maxBytes) throws IOException {
        byte[] all = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
        if (all.length > maxBytes) {
            throw new IllegalArgumentException("Attachment exceeds the maximum allowed size.");
        }
        return all;
    }

    /** Exposed for the sniffing tests; production code sniffs the whole array. */
    static int sniffLength() {
        return SNIFF_BYTES;
    }
}
