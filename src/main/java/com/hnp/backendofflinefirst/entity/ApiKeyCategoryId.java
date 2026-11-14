package com.hnp.backendofflinefirst.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class ApiKeyCategoryId implements Serializable {
    private Long apiKeyId;
    private Long categoryId;
}
