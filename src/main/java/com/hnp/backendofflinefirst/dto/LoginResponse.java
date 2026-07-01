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
}
