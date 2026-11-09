package com.hnp.backendofflinefirst.dto.device;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hnp.backendofflinefirst.service.DeviceAuthException;

/**
 * The wire shapes of device enrolment, in one place because they are one contract.
 *
 * <p>The PWA's {@code src/types/device.ts} mirrors these; a field renamed here and not there is a
 * tablet that cannot log in, so change both in the same commit.
 */
public final class DeviceDtos {

    private DeviceDtos() {
    }

    /**
     * The body of every device-enrolment refusal.
     *
     * <p>{@code message} is the field every PWA build already reads from an error body, so an
     * older build shows the right Persian sentence; {@code code} is what a current build
     * branches on.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String code, String message, Integer attemptsLeft) {

        public static ErrorResponse of(DeviceAuthException e) {
            return new ErrorResponse(e.getError().name(), e.getError().messageFa(), e.getAttemptsLeft());
        }
    }

    /**
     * A tablet asking to be enrolled.
     *
     * @param publicKey the device's ECDSA P-256 public key, base64 SPKI
     * @param label     an optional name an operator typed («تبلت ۷ واحد ۲»), shown to the approver
     */
    public record EnrollmentRequest(String publicKey, String label) {
    }

    /**
     * A tablet completing its own enrolment with the code it was given.
     *
     * <p>{@code enrollmentCode} is masked in every log line by name (LogSanitizer). Even unmasked
     * it would be useless on its own: it is accepted only alongside {@code signature}, which only
     * the key that opened this request can produce.
     *
     * <p>The request id is in the body and not the path on purpose. This endpoint is reachable
     * unauthenticated, so it must be on {@code app.http.anonymous-json-paths} — the 64 KB ceiling
     * for bodies Jackson parses before anyone is authenticated — and that list is matched
     * exactly. An id in the path could not be listed, and would have left this one anonymous
     * endpoint on the 64 MB ceiling meant for authenticated tablets.
     *
     * @param requestId      the request being completed — the number the tablet showed
     * @param enrollmentCode the one-time code as typed — Persian digits and grouping are accepted
     * @param signature      ECDSA P-256 signature (P1363, base64) over the enrolment message
     */
    public record EnrollmentConfirm(Long requestId, String enrollmentCode, String signature) {
    }

    /**
     * Where a tablet's enrolment stands.
     *
     * <p>{@code requestId} is the number the operator reads to the approver; once approved it is
     * also the device id the tablet presents at every login. {@code keyFingerprint} is the
     * server's own fingerprint of the key, returned so the tablet never has to reproduce the
     * server's encoding to identify itself — it echoes this back.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnrollmentState(long requestId, String status, String keyFingerprint, Long expiresAt) {
    }

    /**
     * A tablet asking for a login nonce.
     *
     * <p>Carries the fingerprint as well as the id, so the endpoint answers about a device only to
     * a caller that already knows its key: ids are sequential, and without this anybody could walk
     * them and learn which devices exist and in what state.
     */
    public record ChallengeRequest(Long deviceId, String keyFingerprint) {
    }

    /**
     * The answer to a challenge request.
     *
     * <p>{@code enforced = false} means device enrolment is off and the tablet logs in exactly as
     * it always has. Otherwise {@code deviceStatus} says where this device stands, and a
     * {@code nonce} is present only when it is {@code APPROVED}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChallengeResponse(boolean enforced, String deviceStatus, String nonce, Long expiresAt) {

        public static ChallengeResponse notEnforced() {
            return new ChallengeResponse(false, null, null, null);
        }

        public static ChallengeResponse status(String deviceStatus) {
            return new ChallengeResponse(true, deviceStatus, null, null);
        }
    }

    /**
     * A code just issued in the panel.
     *
     * <p>The field is named {@code enrollmentCode} so that the service-layer DEBUG line that logs
     * this return value masks it (LogSanitizer matches the name).
     */
    public record IssuedCode(long deviceId, String enrollmentCode, long expiresAt) {
    }
}
