package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.DeviceAuthError;
import com.hnp.backendofflinefirst.domain.DeviceRevokeReason;
import com.hnp.backendofflinefirst.domain.DeviceStatus;
import com.hnp.backendofflinefirst.dto.device.DeviceDtos;
import com.hnp.backendofflinefirst.entity.Device;
import com.hnp.backendofflinefirst.repository.DeviceRepository;
import com.hnp.backendofflinefirst.security.DeviceKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Which handheld may use the mobile app — enrolment, its one-time code, and the device proof at
 * login.
 *
 * <h2>The flow</h2>
 *
 * <ol>
 *   <li>A tablet that is not enrolled asks to be ({@link #requestEnrollment}). It sends the public
 *       half of a key it generated and can never export, and gets a request number to read to an
 *       approver.</li>
 *   <li>An approver finds that number in the panel and issues a one-time code for it
 *       ({@link #issueCode}) — at the desk, or over the phone at night.</li>
 *   <li>The tablet sends the code back, signed by its key ({@link #confirmEnrollment}). The
 *       signature is what binds the code to this request: a code overheard on the phone cannot
 *       be used by any other device, because no other device holds this key.</li>
 *   <li>From then on, every login first fetches a nonce ({@link #issueChallenge}) and signs it;
 *       the login is authorised only if that proof checks out ({@link #authorizeLogin}).</li>
 * </ol>
 *
 * <h2>Off means inert</h2>
 *
 * <p>With {@code auth.device_enrollment.enabled} off, {@link #issueChallenge} says so and nothing
 * else, the tablet logs in exactly as before, and the enrolment endpoints refuse. The controller
 * does not call {@link #authorizeLogin} at all. Nothing about a login changes until an
 * administrator turns this on — and then only at the next login, never for a session already
 * running.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceEnrollmentService {

    /** How long a pending request may wait for its code to be issued and entered. */
    static final long REQUEST_TTL_MS = 30 * 60_000L;

    /** How long a one-time code lives once issued. */
    static final long CODE_TTL_MS = 10 * 60_000L;

    /** Wrong codes allowed before the code is destroyed and has to be issued again. */
    static final int MAX_CODE_ATTEMPTS = 5;

    /** How long a login nonce lives. Long enough for a slow link, short enough to be worthless. */
    static final long CHALLENGE_TTL_MS = 2 * 60_000L;

    /** Outstanding nonces kept per device; a real tablet needs one. */
    static final int MAX_CHALLENGES_PER_DEVICE = 5;

    /**
     * Unexpired pending requests allowed at once, in total and from one address.
     *
     * <p>The request endpoint is public — it has to be, the tablet asking is by definition not yet
     * trusted — so these caps are what keep it from being used to fill the table. A request does
     * nothing until an approver issues a code for it, so junk requests are harmless beyond
     * occupying the list; the caps bound even that.
     */
    static final int MAX_LIVE_PENDING = 50;
    static final int MAX_LIVE_PENDING_PER_IP = 5;

    private static final int CODE_DIGITS = 8;
    private static final int CODE_BOUND = 100_000_000;
    private static final int MAX_LABEL_LENGTH = 255;
    private static final int MAX_USER_AGENT_LENGTH = 512;
    private static final int MAX_IP_LENGTH = 64;

    private final DeviceRepository deviceRepository;
    private final ApiSessionService apiSessionService;
    private final AppSettingsService appSettingsService;
    private final SecureRandom random = new SecureRandom();

    public boolean isEnabled() {
        return appSettingsService.isDeviceEnrollmentEnabled();
    }

    // =====================================================================================
    // The tablet
    // =====================================================================================

    /**
     * A tablet asks to be enrolled.
     *
     * <p>Idempotent per key: asking again with a key that already has a row answers with that
     * row's state — an approved key is told it is approved (the response that said so may have
     * been lost), a pending one gets its request back (re-armed if it had expired), and a revoked
     * key is refused, so a withdrawn device cannot quietly re-enter the queue under its old
     * identity; it must make a new key.
     */
    @Transactional
    public DeviceDtos.EnrollmentState requestEnrollment(String publicKeyBase64, String label,
                                                        String userAgent, String ip, long now) {
        requireEnabled();
        DeviceKeys.ParsedKey parsed = DeviceKeys.parsePublicKey(publicKeyBase64)
                .orElseThrow(() -> new DeviceAuthException(DeviceAuthError.INVALID_KEY));

        Optional<Device> existing = deviceRepository.findByKeyFingerprint(parsed.fingerprint());
        if (existing.isPresent()) {
            Device device = deviceRepository.findByIdForUpdate(existing.get().getId()).orElseThrow();
            return switch (device.getStatus()) {
                case APPROVED -> state(device);
                case REVOKED -> throw new DeviceAuthException(DeviceAuthError.DEVICE_REVOKED);
                case PENDING -> {
                    if (!device.isPendingAt(now)) {
                        // An old request for this key ran out. Re-arm it rather than insert: the
                        // key is unique, and the approver should see one row per tablet.
                        device.setRequestedAt(now);
                        device.setRequestExpiresAt(now + REQUEST_TTL_MS);
                        device.clearCode();
                    }
                    device.setLabel(trim(label, MAX_LABEL_LENGTH));
                    device.setUserAgent(trim(userAgent, MAX_USER_AGENT_LENGTH));
                    device.setRequestIp(trim(ip, MAX_IP_LENGTH));
                    yield state(deviceRepository.save(device));
                }
            };
        }

        String requestIp = trim(ip, MAX_IP_LENGTH);
        if (deviceRepository.countLivePending(DeviceStatus.PENDING, now) >= MAX_LIVE_PENDING
                || (requestIp != null
                && deviceRepository.countLivePendingFromIp(DeviceStatus.PENDING, requestIp, now)
                >= MAX_LIVE_PENDING_PER_IP)) {
            throw new DeviceAuthException(DeviceAuthError.TOO_MANY_PENDING);
        }

        Device device = new Device();
        device.setPublicKey(parsed.canonicalBase64());
        device.setKeyFingerprint(parsed.fingerprint());
        device.setLabel(trim(label, MAX_LABEL_LENGTH));
        device.setUserAgent(trim(userAgent, MAX_USER_AGENT_LENGTH));
        device.setRequestIp(requestIp);
        device.setStatus(DeviceStatus.PENDING);
        device.setRequestedAt(now);
        device.setRequestExpiresAt(now + REQUEST_TTL_MS);
        try {
            device = deviceRepository.saveAndFlush(device);
        } catch (DataIntegrityViolationException e) {
            // Two requests with one key at the same instant: the unique fingerprint let exactly
            // one through. The tablet retries and is answered from the row that won.
            throw new DeviceAuthException(DeviceAuthError.REQUEST_CONFLICT);
        }
        log.info("Device enrolment requested: request {} from {} ({})",
                device.getId(), device.getRequestIp(), device.getLabel());
        return state(device);
    }

    /**
     * The tablet completes its enrolment with the code an approver gave it.
     *
     * <p>The signature is checked <b>first</b>, before anything about the code: without the key
     * that opened this request nothing else is reachable, so a stranger cannot even spend this
     * request's attempts, let alone guess its code.
     *
     * <p>{@code noRollbackFor}: a wrong code must stay counted, and an expired or exhausted code
     * must stay destroyed, even though the call itself is refused.
     */
    @Transactional(noRollbackFor = DeviceAuthException.class)
    public DeviceDtos.EnrollmentState confirmEnrollment(long requestId, String enrollmentCode,
                                                        String signature, long now) {
        requireEnabled();
        Device device = deviceRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new DeviceAuthException(DeviceAuthError.REQUEST_UNKNOWN));
        String code = DeviceKeys.normalizeCode(enrollmentCode);

        DeviceKeys.ParsedKey key = DeviceKeys.parsePublicKey(device.getPublicKey())
                .orElseThrow(() -> new DeviceAuthException(DeviceAuthError.INVALID_KEY));
        if (!DeviceKeys.verify(key.key(), DeviceKeys.enrollMessage(requestId, code), signature)) {
            throw new DeviceAuthException(DeviceAuthError.SIGNATURE_INVALID);
        }

        switch (device.getStatus()) {
            case APPROVED:
                // The response that approved it was lost; the key just proved itself again.
                return state(device);
            case REVOKED:
                throw new DeviceAuthException(DeviceAuthError.DEVICE_REVOKED);
            case PENDING:
            default:
                break;
        }

        if (!device.isPendingAt(now)) {
            throw new DeviceAuthException(DeviceAuthError.REQUEST_EXPIRED);
        }
        if (device.getCodeHash() == null) {
            throw new DeviceAuthException(DeviceAuthError.CODE_NOT_ISSUED);
        }
        if (!device.hasLiveCodeAt(now)) {
            device.clearCode();
            deviceRepository.save(device);
            throw new DeviceAuthException(DeviceAuthError.CODE_EXPIRED);
        }
        if (!DeviceKeys.constantTimeEquals(DeviceKeys.sha256Hex(code), device.getCodeHash())) {
            int attempts = device.getCodeFailedAttempts() + 1;
            if (attempts >= MAX_CODE_ATTEMPTS) {
                device.clearCode();
                deviceRepository.save(device);
                log.warn("Device enrolment code for request {} destroyed after {} wrong attempts",
                        requestId, attempts);
                throw new DeviceAuthException(DeviceAuthError.CODE_LOCKED);
            }
            device.setCodeFailedAttempts(attempts);
            deviceRepository.save(device);
            throw new DeviceAuthException(DeviceAuthError.CODE_INVALID, MAX_CODE_ATTEMPTS - attempts);
        }

        device.setStatus(DeviceStatus.APPROVED);
        device.setApprovedAt(now);
        // The approver is whoever issued the code that was just used — the person who decided.
        device.setApprovedBy(device.getCodeIssuedBy());
        device.clearCode();
        deviceRepository.save(device);
        log.info("Device {} enrolled (approved by user {})", device.getId(), device.getApprovedBy());
        return state(device);
    }

    // =====================================================================================
    // Login
    // =====================================================================================

    /**
     * A nonce for an approved device to sign, or the reason there is none.
     *
     * <p>Answers about a device only when the caller presents both its id and its key
     * fingerprint, so the endpoint cannot be used to enumerate devices. With enrolment off it
     * says only that, and writes nothing.
     */
    @Transactional
    public DeviceDtos.ChallengeResponse issueChallenge(Long deviceId, String keyFingerprint, long now) {
        if (!isEnabled()) {
            return DeviceDtos.ChallengeResponse.notEnforced();
        }
        if (deviceId == null || keyFingerprint == null || keyFingerprint.isBlank()) {
            return DeviceDtos.ChallengeResponse.status("UNKNOWN");
        }
        Optional<Device> found = deviceRepository.findById(deviceId)
                .filter(d -> DeviceKeys.constantTimeEquals(d.getKeyFingerprint(), keyFingerprint.trim()));
        if (found.isEmpty()) {
            return DeviceDtos.ChallengeResponse.status("UNKNOWN");
        }
        Device device = found.get();
        if (device.getStatus() != DeviceStatus.APPROVED) {
            return DeviceDtos.ChallengeResponse.status(device.getStatus().name());
        }

        deviceRepository.deleteExpiredChallenges(now);
        String nonce = randomNonce();
        long expiresAt = now + CHALLENGE_TTL_MS;
        deviceRepository.insertChallenge(nonce, device.getId(), now, expiresAt);
        deviceRepository.trimChallenges(device.getId(), MAX_CHALLENGES_PER_DEVICE);
        return new DeviceDtos.ChallengeResponse(true, DeviceStatus.APPROVED.name(), nonce, expiresAt);
    }

    /**
     * Checks a login's device proof. Called only while enrolment is on, and <b>before</b> the
     * password is checked — so an unenrolled device learns nothing about any account by trying.
     *
     * <p>The nonce is consumed whatever happens next ({@code noRollbackFor}): a refused login
     * cannot be retried with the same proof.
     *
     * @return the device id, for the session about to be registered
     */
    @Transactional(noRollbackFor = DeviceAuthException.class)
    public long authorizeLogin(Long deviceId, String nonce, String signature, String username, long now) {
        if (deviceId == null || isBlank(nonce) || isBlank(signature)) {
            throw new DeviceAuthException(DeviceAuthError.ENROLLMENT_REQUIRED);
        }
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new DeviceAuthException(DeviceAuthError.DEVICE_UNKNOWN));
        if (device.getStatus() == DeviceStatus.REVOKED) {
            throw new DeviceAuthException(DeviceAuthError.DEVICE_REVOKED);
        }
        if (device.getStatus() != DeviceStatus.APPROVED) {
            throw new DeviceAuthException(DeviceAuthError.DEVICE_NOT_APPROVED);
        }
        if (deviceRepository.consumeChallenge(nonce.trim(), device.getId(), now) != 1) {
            throw new DeviceAuthException(DeviceAuthError.CHALLENGE_INVALID);
        }
        DeviceKeys.ParsedKey key = DeviceKeys.parsePublicKey(device.getPublicKey())
                .orElseThrow(() -> new DeviceAuthException(DeviceAuthError.INVALID_KEY));
        if (!DeviceKeys.verify(key.key(), DeviceKeys.loginMessage(nonce.trim(), username), signature)) {
            throw new DeviceAuthException(DeviceAuthError.SIGNATURE_INVALID);
        }
        return device.getId();
    }

    // =====================================================================================
    // The panel
    // =====================================================================================

    /**
     * Issues a one-time code for one pending request. Replaces any earlier code for it.
     *
     * <p>Returned once and never stored: only its SHA-256 is kept. The request's own expiry is
     * pushed out to cover the code, so a code issued near the end of a request's life is not
     * dead on arrival.
     */
    @Transactional
    public DeviceDtos.IssuedCode issueCode(Long deviceId, Long actorUserId, long now) {
        Device device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("درخواست ثبت دستگاه پیدا نشد."));
        if (!device.isPendingAt(now)) {
            throw new IllegalStateException(device.getStatus() == DeviceStatus.PENDING
                    ? "مهلت این درخواست تمام شده است. از تبلت بخواهید دوباره درخواست ثبت بدهد."
                    : "کد ثبت فقط برای درخواستِ در انتظار صادر می‌شود.");
        }
        String code = String.format("%0" + CODE_DIGITS + "d", random.nextInt(CODE_BOUND));
        long expiresAt = now + CODE_TTL_MS;
        device.setCodeHash(DeviceKeys.sha256Hex(code));
        device.setCodeIssuedAt(now);
        device.setCodeExpiresAt(expiresAt);
        device.setCodeIssuedBy(actorUserId);
        device.setCodeFailedAttempts(0);
        device.setRequestExpiresAt(Math.max(device.getRequestExpiresAt(), expiresAt));
        deviceRepository.save(device);
        log.info("Enrolment code issued for device request {} by user {}", deviceId, actorUserId);
        return new DeviceDtos.IssuedCode(deviceId, code, expiresAt);
    }

    /** What a revoke did, for the panel's confirmation message. */
    public record RevokeResult(DeviceRevokeReason reason, int sessionsRevoked) {
    }

    /**
     * Withdraws an approved device, or turns down a pending request.
     *
     * <p>The device row is held FOR UPDATE for the whole transaction, and its sessions are revoked
     * inside it. That is what {@link ApiSessionService#register} relies on: a login racing this
     * either registered its session first — and is revoked here — or sees the device revoked and
     * registers nothing.
     */
    @Transactional
    public RevokeResult revoke(Long deviceId, Long actorUserId, long now) {
        Device device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("دستگاه پیدا نشد."));
        if (device.getStatus() == DeviceStatus.REVOKED) {
            throw new IllegalStateException("این دستگاه پیش‌تر لغو شده است.");
        }
        DeviceRevokeReason reason = device.getStatus() == DeviceStatus.PENDING
                ? DeviceRevokeReason.REJECTED
                : DeviceRevokeReason.ADMIN;
        device.setStatus(DeviceStatus.REVOKED);
        device.setRevokedAt(now);
        device.setRevokedBy(actorUserId);
        device.setRevokeReason(reason);
        device.clearCode();
        deviceRepository.saveAndFlush(device);
        int sessions = apiSessionService.revokeAllForDevice(deviceId, actorUserId, now);
        log.info("Device {} revoked ({}) by user {}; {} session(s) ended", deviceId, reason, actorUserId, sessions);
        return new RevokeResult(reason, sessions);
    }

    /** Pending requests that can still be completed, newest first — the approver's to-do list. */
    @Transactional(readOnly = true)
    public List<Device> livePending(long now) {
        Pageable newestFirst = PageRequest.of(0, MAX_LIVE_PENDING, Sort.by(Sort.Direction.DESC, "requestedAt"));
        return deviceRepository.findByStatusIn(List.of(DeviceStatus.PENDING), newestFirst)
                .getContent().stream()
                .filter(d -> d.isPendingAt(now))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<Device> list(Collection<DeviceStatus> statuses, Pageable pageable) {
        return deviceRepository.findByStatusIn(statuses, pageable);
    }

    @Transactional(readOnly = true)
    public long countApproved() {
        return deviceRepository.countByStatus(DeviceStatus.APPROVED);
    }

    // =====================================================================================

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new DeviceAuthException(DeviceAuthError.ENROLLMENT_DISABLED);
        }
    }

    private static DeviceDtos.EnrollmentState state(Device device) {
        Long expiresAt = device.getStatus() == DeviceStatus.PENDING ? device.getRequestExpiresAt() : null;
        return new DeviceDtos.EnrollmentState(device.getId(), device.getStatus().name(),
                device.getKeyFingerprint(), expiresAt);
    }

    private String randomNonce() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
