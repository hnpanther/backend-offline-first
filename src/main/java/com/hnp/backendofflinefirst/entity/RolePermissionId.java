package com.hnp.backendofflinefirst.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class RolePermissionId implements Serializable {
    private Long roleId;
    private Long permissionId;
}
