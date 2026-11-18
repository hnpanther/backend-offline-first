package com.hnp.backendofflinefirst.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String username;
    private String fullName;
    private List<String> roles;
    private List<String> permissions;
    /** JWT access token — send as {@code Authorization: Bearer <token>} on API calls. */
    private String accessToken;
    private String tokenType;
    /** Token expiry time (epoch millis, UTC). */
    private Long expiresAt;
    /**
     * The server's clock when it answered (epoch millis, V28). Lets the tablet measure how far
     * its own clock is off at the moment it signs in, before the first bootstrap or inbox pull.
     * Additive: an older app ignores it.
     */
    private Long serverTime;
}
