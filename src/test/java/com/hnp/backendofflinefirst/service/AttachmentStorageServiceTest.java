package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AttachmentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Storage rules for attachment bytes.
 *
 * <p>Two groups here are security rather than housekeeping: type detection must read the file
 * rather than believe the uploader, and storage keys must never be able to point outside the
 * configured root. Everything else — sharding, extensions, hashing — is about keeping the
 * directory usable and the rows honest.
 */
class AttachmentStorageServiceTest {

    // Minimal but genuine file headers.
    private static final byte[] PNG = concat(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 8);
    private static final byte[] JPEG = concat(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, 12);
    private static final byte[] WEBP = concat("RIFF____WEBP".getBytes(), 4);
    private static final byte[] WAV = concat("RIFF____WAVE".getBytes(), 4);
    private static final byte[] MATROSKA = concat(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3}, 12);
    private static final byte[] OGG = concat("OggS________".getBytes(), 4);
    private static final byte[] MP3_ID3 = concat("ID3_________".getBytes(), 4);
    private static final byte[] M4A = concat("____ftypm4a ".getBytes(), 4);
    private static final byte[] MP4 = concat("____ftypisom".getBytes(), 4);
    private static final byte[] WINDOWS_EXE = concat(new byte[]{'M', 'Z', (byte) 0x90, 0x00}, 60);

    private static byte[] concat(byte[] head, int padding) {
        byte[] out = new byte[head.length + padding];
        System.arraycopy(head, 0, out, 0, head.length);
        return out;
    }

    // ── Type detection ────────────────────────────────────────────────────────

    @Test
    void detectsEveryFormatTheClientsCanProduce() {
        assertThat(AttachmentStorageService.detectMimeType(PNG)).isEqualTo("image/png");
        assertThat(AttachmentStorageService.detectMimeType(JPEG)).isEqualTo("image/jpeg");
        assertThat(AttachmentStorageService.detectMimeType(WEBP)).isEqualTo("image/webp");
        assertThat(AttachmentStorageService.detectMimeType(WAV)).isEqualTo("audio/wav");
        assertThat(AttachmentStorageService.detectMimeType(OGG)).isEqualTo("audio/ogg");
        assertThat(AttachmentStorageService.detectMimeType(MP3_ID3)).isEqualTo("audio/mpeg");
        assertThat(AttachmentStorageService.detectMimeType(M4A)).isEqualTo("audio/mp4");
        assertThat(AttachmentStorageService.detectMimeType(MP4)).isEqualTo("video/mp4");
    }

    @Test
    void refusesAnExecutableRegardlessOfWhatItClaimsToBe() {
        // The whole point of sniffing: a client can send any Content-Type it likes.
        assertThat(AttachmentStorageService.detectMimeType(WINDOWS_EXE)).isNull();
    }

    @Test
    void refusesEmptyAndTruncatedFiles() {
        assertThat(AttachmentStorageService.detectMimeType(null)).isNull();
        assertThat(AttachmentStorageService.detectMimeType(new byte[0])).isNull();
        assertThat(AttachmentStorageService.detectMimeType(new byte[]{(byte) 0x89, 'P', 'N'})).isNull();
    }

    @Test
    void resolvesTheAmbiguousMatroskaContainerFromTheFieldKind() {
        // MediaRecorder emits audio/webm and video/webm with an identical EBML header, so the
        // bytes cannot decide; the field's declared kind can, and it is server-side data.
        String raw = AttachmentStorageService.detectMimeType(MATROSKA);
        assertThat(raw).isEqualTo("application/x-matroska");
        assertThat(AttachmentStorageService.resolveWebmType(raw, AttachmentKind.AUDIO)).isEqualTo("audio/webm");
        assertThat(AttachmentStorageService.resolveWebmType(raw, AttachmentKind.VIDEO)).isEqualTo("video/webm");
    }

    @Test
    void leavesUnambiguousTypesAloneWhenResolvingWebm() {
        assertThat(AttachmentStorageService.resolveWebmType("image/png", AttachmentKind.IMAGE))
                .isEqualTo("image/png");
        assertThat(AttachmentStorageService.resolveWebmType(null, AttachmentKind.AUDIO)).isNull();
    }

    // ── Kind matching ─────────────────────────────────────────────────────────

    @Test
    void aPhotoCannotBeStoredInAnAudioFieldOrViceVersa() {
        assertThat(AttachmentStorageService.matchesKind("image/png", AttachmentKind.IMAGE)).isTrue();
        assertThat(AttachmentStorageService.matchesKind("image/png", AttachmentKind.AUDIO)).isFalse();
        assertThat(AttachmentStorageService.matchesKind("audio/webm", AttachmentKind.AUDIO)).isTrue();
        assertThat(AttachmentStorageService.matchesKind("audio/webm", AttachmentKind.VIDEO)).isFalse();
        assertThat(AttachmentStorageService.matchesKind("video/mp4", AttachmentKind.VIDEO)).isTrue();
    }

    @Test
    void nullsNeverMatchAnything() {
        assertThat(AttachmentStorageService.matchesKind(null, AttachmentKind.IMAGE)).isFalse();
        assertThat(AttachmentStorageService.matchesKind("image/png", null)).isFalse();
    }

    // ── Storage keys ──────────────────────────────────────────────────────────

    @Test
    void keysAreDateShardedAndCarryTheRightExtension() {
        String key = AttachmentStorageService.buildStorageKey("abc-123", "image/webp");

        // yyyy/MM/dd/<id>.<ext> — no directory ever accumulates a year of uploads.
        assertThat(key).matches("\\d{4}/\\d{2}/\\d{2}/abc-123\\.webp");
    }

    @Test
    void separatorsAreStrippedFromTheIdRatherThanEscaped() {
        // The id becomes part of a path. There is no legitimate id containing a separator, so
        // anything outside the UUID alphabet is removed — an escape would still be a path.
        assertThat(AttachmentStorageService.buildStorageKey("../../etc/passwd", "image/png"))
                .doesNotContain("..")
                .endsWith("etcpasswd.png");
    }

    @Test
    void anIdWithNothingUsableIsRejected() {
        assertThatThrownBy(() -> AttachmentStorageService.buildStorageKey("../..", "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownTypeYieldsNoExtensionRatherThanAGuess() {
        assertThat(AttachmentStorageService.buildStorageKey("abc", "application/octet-stream"))
                .endsWith("/abc");
    }

    // ── Round trip and traversal ──────────────────────────────────────────────

    @Test
    void storesAndReadsBackTheExactBytes(@TempDir Path tmp) throws IOException {
        AttachmentStorageService storage = new AttachmentStorageService(tmp.toString());

        String key = storage.store("abc-123", PNG, "image/png");

        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.read(key)).isEqualTo(PNG);
        assertThat(Files.exists(tmp.resolve(key))).isTrue();
    }

    @Test
    void refusesToReadOrDeleteOutsideTheStorageRoot(@TempDir Path tmp) {
        AttachmentStorageService storage = new AttachmentStorageService(tmp.resolve("root").toString());

        assertThatThrownBy(() -> storage.read("../outside.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
        assertThat(storage.exists("../outside.png"))
                .as("a traversing key is not 'missing', it is refused — and must not read as present")
                .isFalse();
        // delete swallows the refusal rather than throwing, but must not touch anything.
        storage.delete("../outside.png");
    }

    @Test
    void refusesBlankKeys(@TempDir Path tmp) {
        AttachmentStorageService storage = new AttachmentStorageService(tmp.toString());
        assertThatThrownBy(() -> storage.read("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingIsIdempotentAndNeverThrowsForAMissingFile(@TempDir Path tmp) throws IOException {
        AttachmentStorageService storage = new AttachmentStorageService(tmp.toString());
        String key = storage.store("abc", PNG, "image/png");

        storage.delete(key);
        storage.delete(key);

        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    void overwritingTheSameKeyLeavesNoPartialFilesBehind(@TempDir Path tmp) throws IOException {
        AttachmentStorageService storage = new AttachmentStorageService(tmp.toString());

        String key = storage.store("abc", PNG, "image/png");
        storage.store("abc", JPEG, "image/png");

        assertThat(storage.read(key)).isEqualTo(JPEG);
        try (var files = Files.walk(tmp)) {
            assertThat(files.filter(Files::isRegularFile).filter(p -> p.toString().contains(".part")))
                    .as("the temp file is moved into place, never left behind")
                    .isEmpty();
        }
    }

    // ── Hash and size ceiling ─────────────────────────────────────────────────

    @Test
    void hashesContentForDeduplicationAndCorruptionChecks() {
        assertThat(AttachmentStorageService.sha256Hex(PNG))
                .hasSize(64)
                .isEqualTo(AttachmentStorageService.sha256Hex(PNG))
                .isNotEqualTo(AttachmentStorageService.sha256Hex(JPEG));
    }

    @Test
    void readAtMostRefusesAnOversizedStreamWithoutBufferingItAll() throws IOException {
        assertThat(AttachmentStorageService.readAtMost(new ByteArrayInputStream(PNG), 1024)).isEqualTo(PNG);

        assertThatThrownBy(() ->
                AttachmentStorageService.readAtMost(new ByteArrayInputStream(new byte[100]), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum allowed size");
    }
}
