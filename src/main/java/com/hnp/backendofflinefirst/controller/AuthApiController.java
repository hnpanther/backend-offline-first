package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.ApiErrorResponse;
import com.hnp.backendofflinefirst.dto.LoginRequest;
import com.hnp.backendofflinefirst.dto.LoginResponse;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.JwtService;
import com.hnp.backendofflinefirst.service.ApiSessionService;
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

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
            LoginResponse response = buildLoginResponse(auth, request, httpRequest);
            return response != null ? ResponseEntity.ok(response) : ResponseEntity.ok().build();
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiErrorResponse(ErrorTranslator.toFa(e.getMessage())));
        }
    }

    private LoginResponse buildLoginResponse(Authentication auth, LoginRequest request,
                                             HttpServletRequest httpRequest) {
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
                httpRequest.getHeader(HttpHeaders.USER_AGENT), clientIp(httpRequest));
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

    /** Prefers the proxy-forwarded address so sessions behind a reverse proxy stay identifiable. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
