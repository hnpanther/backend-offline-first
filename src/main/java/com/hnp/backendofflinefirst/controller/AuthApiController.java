package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.ApiErrorResponse;
import com.hnp.backendofflinefirst.dto.LoginRequest;
import com.hnp.backendofflinefirst.dto.LoginResponse;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.JwtService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import lombok.RequiredArgsConstructor;
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

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
            LoginResponse response = buildLoginResponse(auth);
            return response != null ? ResponseEntity.ok(response) : ResponseEntity.ok().build();
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiErrorResponse(ErrorTranslator.toFa(e.getMessage())));
        }
    }

    private LoginResponse buildLoginResponse(Authentication auth) {
        if (!(auth.getPrincipal() instanceof AppUserDetails user)) {
            return null;
        }
        List<String> roles = new ArrayList<>(user.getRoleCodes());
        List<String> permissions = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .toList();
        JwtService.JwtToken token = jwtService.issueToken(user);
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
}
