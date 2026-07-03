package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.ApiErrorResponse;
import com.hnp.backendofflinefirst.dto.LoginRequest;
import com.hnp.backendofflinefirst.dto.LoginResponse;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
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

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
            LoginResponse response = buildLoginResponse(auth, httpRequest);
            return response != null ? ResponseEntity.ok(response) : ResponseEntity.ok().build();
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiErrorResponse(ErrorTranslator.toFa(e.getMessage())));
        }
    }

    private LoginResponse buildLoginResponse(Authentication auth, HttpServletRequest httpRequest) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        HttpSession session = httpRequest.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        if (auth.getPrincipal() instanceof AppUserDetails user) {
            List<String> roles = new ArrayList<>(user.getRoleCodes());
            List<String> permissions = user.getAuthorities().stream().map(a -> a.getAuthority()).toList();
            return new LoginResponse(
                    user.getUsername(),
                    user.getUser().getFullName(),
                    roles,
                    permissions
            );
        }
        return null;
    }
}
