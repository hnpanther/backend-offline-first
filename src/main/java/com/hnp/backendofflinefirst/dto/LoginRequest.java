package com.hnp.backendofflinefirst.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
    /** Optional client-supplied device name, shown in the admin session list. */
    private String deviceLabel;
}
