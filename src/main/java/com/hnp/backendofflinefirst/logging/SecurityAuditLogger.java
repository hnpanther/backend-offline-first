package com.hnp.backendofflinefirst.logging;

import com.hnp.backendofflinefirst.security.AppUserDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Structured audit trail for login success and failure (web form + API).
 */
@Component
@Slf4j
public class SecurityAuditLogger {

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        String username = auth.getName();
        String roles = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .limit(20)
                .reduce((a, b) -> a + "," + b)
                .orElse("-");
        if (auth.getPrincipal() instanceof AppUserDetails details) {
            log.info("[AUDIT] LOGIN_SUCCESS user={} userId={} roles={} permissions={}",
                    username, details.getUserId(), details.getRoleCodes(), roles);
        } else {
            log.info("[AUDIT] LOGIN_SUCCESS user={} authorities={}", username, roles);
        }
    }

    @EventListener
    public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
        log.warn("[AUDIT] LOGIN_FAILURE user={} reason={}",
                event.getAuthentication().getName(),
                event.getException().getMessage());
    }
}
