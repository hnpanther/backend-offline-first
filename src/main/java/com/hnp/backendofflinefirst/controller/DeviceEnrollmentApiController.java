package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.domain.DeviceAuthError;
import com.hnp.backendofflinefirst.dto.device.DeviceDtos;
import com.hnp.backendofflinefirst.service.DeviceAuthException;
import com.hnp.backendofflinefirst.service.DeviceEnrollmentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tablet's half of enrolment. Unauthenticated by necessity — a device asking to be enrolled
 * is by definition not yet trusted — and refused outright while enrolment is off.
 *
 * <p>Nothing here grants anything by itself. A request does nothing until an approver issues a
 * code for it in the panel, and a code does nothing without a signature from the key that made
 * the request. See {@link DeviceEnrollmentService}.
 */
@RestController
@RequestMapping("/api/devices/enrollment-requests")
@RequiredArgsConstructor
public class DeviceEnrollmentApiController {

    private final DeviceEnrollmentService deviceEnrollmentService;

    @PostMapping
    public ResponseEntity<?> request(@RequestBody DeviceDtos.EnrollmentRequest body,
                                     HttpServletRequest httpRequest) {
        try {
            return ResponseEntity.ok(deviceEnrollmentService.requestEnrollment(
                    body.publicKey(), body.label(),
                    httpRequest.getHeader(HttpHeaders.USER_AGENT),
                    AuthApiController.clientIp(httpRequest),
                    System.currentTimeMillis()));
        } catch (DeviceAuthException e) {
            return AuthApiController.deviceError(e);
        }
    }

    /**
     * Completes an enrolment. The request id travels in the body — see
     * {@link DeviceDtos.EnrollmentConfirm} for why a path variable would have been a hole.
     */
    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody DeviceDtos.EnrollmentConfirm body) {
        try {
            if (body.requestId() == null) {
                throw new DeviceAuthException(DeviceAuthError.REQUEST_UNKNOWN);
            }
            return ResponseEntity.ok(deviceEnrollmentService.confirmEnrollment(
                    body.requestId(), body.enrollmentCode(), body.signature(), System.currentTimeMillis()));
        } catch (DeviceAuthException e) {
            return AuthApiController.deviceError(e);
        }
    }

    /**
     * A revoked tablet confirming that it has deleted its data (V28, wipe on revoke).
     *
     * <p>Under this prefix rather than a path of its own for one practical reason: the PWA's nginx
     * already rate-limits {@code ^~ /api/devices/enrollment-requests} as a pre-sign-in endpoint, so
     * an installation running an nginx image older than this change still limits it. It is the end
     * of the same lifecycle the other two calls begin.
     */
    @PostMapping("/wipe-confirm")
    public ResponseEntity<?> confirmWipe(@RequestBody DeviceDtos.WipeConfirm body) {
        try {
            return ResponseEntity.ok(deviceEnrollmentService.confirmWipe(
                    body.deviceId(), body.wipeOrderedAt(), body.signature(), System.currentTimeMillis()));
        } catch (DeviceAuthException e) {
            return AuthApiController.deviceError(e);
        }
    }
}
