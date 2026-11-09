package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.audit.AuditEntitySupport;
import com.hnp.backendofflinefirst.audit.AuditFieldChange;
import com.hnp.backendofflinefirst.domain.AuditAction;
import com.hnp.backendofflinefirst.domain.DeviceStatus;
import com.hnp.backendofflinefirst.entity.Device;
import com.hnp.backendofflinefirst.logging.LogSanitizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the one-time enrolment code must never end up: the audit trail and the log file.
 *
 * <p>An eight-digit code's SHA-256 falls to an exhaustive search in seconds, so the hash is as
 * good as the code to anyone who can read it. Both trails are readable by more people than the
 * code is meant for, and neither gains anything by holding it: who issued a code is fully
 * recorded by {@code codeIssuedBy} and {@code codeIssuedAt}.
 */
class DeviceEnrollmentHygieneTest {

    @Test
    void theCodeHashIsMaskedInTheAuditTrail() {
        Device before = pendingDevice();
        Device after = pendingDevice();
        after.setCodeHash(DeviceKeys.sha256Hex("12345678"));
        after.setCodeIssuedBy(1L);
        after.setCodeIssuedAt(1L);
        after.setCodeExpiresAt(2L);

        List<AuditFieldChange> changes = AuditEntitySupport.diff(before, after, AuditAction.UPDATE);

        AuditFieldChange hash = changes.stream()
                .filter(c -> c.field().equals("codeHash"))
                .findFirst()
                .orElseThrow();
        assertThat(hash.newValue()).isEqualTo("***");
        assertThat(changes).noneSatisfy(c ->
                assertThat(c.newValue()).isEqualTo(DeviceKeys.sha256Hex("12345678")));
        // Who issued it is still on the record — that is the part of the trail worth having.
        assertThat(changes).anySatisfy(c -> assertThat(c.field()).isEqualTo("codeIssuedBy"));
    }

    @Test
    void theCodeIsMaskedInEveryLogRendering() {
        // As JSON (a request or response body logged at DEBUG)...
        String json = LogSanitizer.sanitize("{\"enrollmentCode\":\"12345678\",\"signature\":\"abc\"}");
        assertThat(json).doesNotContain("12345678").contains("\"enrollmentCode\":\"***\"");

        // ...and as name=value (an argument summary, or the panel's IssuedCode record).
        String kv = LogSanitizer.sanitize("IssuedCode[deviceId=4, enrollmentCode=12345678, expiresAt=9]");
        assertThat(kv).doesNotContain("12345678").contains("enrollmentCode=***");
    }

    @Test
    void ordinaryCodesAreNotMasked() {
        // The reason the name is listed rather than adding "code" to the masked words.
        String line = LogSanitizer.sanitize("{\"unitCode\":\"U-02\",\"personnelCode\":\"P-17\"}");
        assertThat(line).contains("U-02").contains("P-17");
    }

    private static Device pendingDevice() {
        Device device = new Device();
        device.setId(7L);
        device.setPublicKey("pk");
        device.setKeyFingerprint("fp");
        device.setStatus(DeviceStatus.PENDING);
        device.setRequestedAt(1L);
        device.setRequestExpiresAt(2L);
        return device;
    }
}
