package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.security.JwtService;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.LookupDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A reference list for a tablet, one page at a time (roadmap §31 Phase 2).
 *
 * <p>{@code GET /api/lookups/{code}/items?since=&afterId=&limit=} — {@code since} / {@code afterId}
 * are the previous page's {@code nextSince} / {@code nextAfterId}, both 0 for a first fetch. The
 * authority is necessary and not sufficient: {@link LookupDeviceService} also requires an open
 * round assigned to the caller that draws from the list, and writes a download-log row per page.
 * See docs/security.md for the checklist this endpoint went through.
 */
@RestController
@RequestMapping("/api/lookups")
@RequiredArgsConstructor
public class LookupDeviceController {

    private final LookupDeviceService deviceService;
    private final JwtService jwtService;

    @GetMapping("/{code}/items")
    @PreAuthorize("hasAuthority('GET:/api/lookups/{code}/items')")
    public ResponseEntity<LookupDeviceService.DevicePage> items(
            @PathVariable String code,
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        // The session's jti names the device in the download log. Read from the token that has
        // already been verified to reach this point; a token that no longer verifies just logs none.
        String jti = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            jti = jwtService.verify(authorization.substring("Bearer ".length()).trim())
                    .map(JwtService.VerifiedToken::jti)
                    .orElse(null);
        }
        LookupDeviceService.DevicePage page = deviceService.page(
                SecurityUtils.currentUserId(), jti, code, since, afterId, limit);
        // Personal data: never cached by anything between the server and the device.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }
}
