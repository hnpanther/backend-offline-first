package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.ApiErrorResponse;
import com.hnp.backendofflinefirst.dto.LoginRequest;
import com.hnp.backendofflinefirst.dto.LoginResponse;
import com.hnp.backendofflinefirst.dto.device.DeviceDtos;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.JwtService;
import com.hnp.backendofflinefirst.service.ApiSessionService;
import com.hnp.backendofflinefirst.service.DeviceAuthException;
import com.hnp.backendofflinefirst.service.DeviceEnrollmentService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthApiController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final ApiSessionService apiSessionService;
    private final DeviceEnrollmentService deviceEnrollmentService;

    /**
     * Logs a user in on a tablet.
     *
     * <h2>With device enrolment on</h2>
     *
     * <p>The device proof is checked <b>before</b> the password. An unenrolled or revoked device
     * is refused with a device error whatever password it sent, so trying passwords from a
     * device that is not allowed in reveals nothing about any account — and costs that account
     * no lockout strike either, because the password is never evaluated.
     *
     * <p>With it off, none of the device fields is read and this is the login it always was.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        Long deviceId = null;
        if (deviceEnrollmentService.isEnabled()) {
            try {
                deviceId = deviceEnrollmentService.authorizeLogin(request.getDeviceId(),
                        request.getNonce(), request.getSignature(), request.getUsername(),
                        System.currentTimeMillis());
            } catch (DeviceAuthException e) {
                return deviceError(e);
            }
        }
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
            LoginResponse response = buildLoginResponse(auth, request, httpRequest, deviceId);
            return response != null ? ResponseEntity.ok(response) : ResponseEntity.ok().build();
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiErrorResponse(ErrorTranslator.toFa(e.getMessage())));
        } catch (DeviceAuthException e) {
            // The device was revoked between the proof check and the session insert.
            return deviceError(e);
        }
    }

    /**
     * A nonce for an enrolled device to sign at login — or, with enrolment off, just
     * {@code {"enforced": false}}, which tells the tablet to log in as it always has.
     */
    @PostMapping("/challenge")
    public DeviceDtos.ChallengeResponse challenge(@RequestBody DeviceDtos.ChallengeRequest request) {
        return deviceEnrollmentService.issueChallenge(request.deviceId(), request.keyFingerprint(),
                System.currentTimeMillis());
    }

    private LoginResponse buildLoginResponse(Authentication auth, LoginRequest request,
                                             HttpServletRequest httpRequest, Long deviceId) {
        if (!(auth.getPrincipal() instanceof AppUserDetails user)) {
            return null;
        }
        List<String> roles = new ArrayList<>(user.getRoleCodes());
        List<String> permissions = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .toList();
        JwtService.JwtToken token = jwtService.issueToken(user);
        // Registering also supersedes any session the user still holds on another device.
        apiSessionService.register(user, token, request.getDeviceLabel(),
                httpRequest.getHeader(HttpHeaders.USER_AGENT), clientIp(httpRequest), deviceId);
        return new LoginResponse(
                user.getUsername(),
                user.getUser().getFullName(),
                roles,
                permissions,
                token.accessToken(),
                "Bearer",
                token.expiresAt()
        );
    }

    static ResponseEntity<DeviceDtos.ErrorResponse> deviceError(DeviceAuthException e) {
        return ResponseEntity.status(e.getError().status()).body(DeviceDtos.ErrorResponse.of(e));
    }

    /** Prefers the proxy-forwarded address so sessions behind a reverse proxy stay identifiable. */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
