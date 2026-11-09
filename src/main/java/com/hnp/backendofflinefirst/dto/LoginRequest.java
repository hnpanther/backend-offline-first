package com.hnp.backendofflinefirst.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
    /** Optional client-supplied device name, shown in the admin session list. */
    private String deviceLabel;

    // ---- device proof, read only while device enrolment is on ----
    //
    // All three are optional and, with enrolment off, ignored entirely: a tablet that has never
    // heard of enrolment sends none of them and logs in exactly as before.

    /** The enrolled device's id — the request number it was approved under. */
    private Long deviceId;
    /** A nonce from {@code /api/auth/challenge}, single-use. */
    private String nonce;
    /** ECDSA P-256 signature (P1363, base64) over {@code DeviceKeys.loginMessage(nonce, username)}. */
    private String signature;
}
