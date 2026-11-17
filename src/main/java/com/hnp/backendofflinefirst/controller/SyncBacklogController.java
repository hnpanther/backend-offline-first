package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.repository.ApiSessionRepository;
import com.hnp.backendofflinefirst.security.JwtService;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tablet's report of the rounds it holds whose readings the server has received in no form at
 * all — neither applied nor kept for review (V25).
 *
 * <p>Why the server wants to know: with the purge of undelivered rounds off by default (V24),
 * those rounds stay on the tablet until they are delivered, and nothing else tells a supervisor
 * that a tablet in the field is holding a week of readings nobody has seen. The number is shown
 * per mobile session on «نشست‌های اپ موبایل».
 *
 * <p>It is the device's own word and changes nothing on the server but three columns of the
 * caller's own session row — which session is taken from the verified token, never from the body.
 */
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncBacklogController {

    /** A sanity rail, not a target: no tablet holds anywhere near this many rounds. */
    static final int MAX_REPORTED_COUNT = 100_000;

    private final ApiSessionRepository apiSessionRepository;
    private final JwtService jwtService;

    @Data
    public static class BacklogReport {
        private Integer undeliveredCount;
        /** Device time the oldest of those rounds was last worked on; null when there are none. */
        private Long oldestUndeliveredAt;
    }

    @PostMapping("/backlog")
    @PreAuthorize("hasAuthority('POST:/api/sync/backlog')")
    @Transactional
    public ResponseEntity<Void> report(
            @RequestBody BacklogReport body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (body == null || body.getUndeliveredCount() == null
                || body.getUndeliveredCount() < 0 || body.getUndeliveredCount() > MAX_REPORTED_COUNT) {
            throw new IllegalArgumentException("Invalid backlog report.");
        }
        String jti = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            jti = jwtService.verify(authorization.substring("Bearer ".length()).trim())
                    .map(JwtService.VerifiedToken::jti)
                    .orElse(null);
        }
        if (jti == null) {
            // Only a mobile session has a row to write to; nothing else can report.
            return ResponseEntity.noContent().build();
        }
        Long oldest = body.getUndeliveredCount() == 0 ? null : body.getOldestUndeliveredAt();
        apiSessionRepository.recordBacklog(jti, SecurityUtils.currentUserId(),
                body.getUndeliveredCount(), oldest, System.currentTimeMillis());
        return ResponseEntity.noContent().build();
    }
}
