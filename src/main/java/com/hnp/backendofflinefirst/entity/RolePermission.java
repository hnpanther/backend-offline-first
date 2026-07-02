package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "role_permissions")
@IdClass(RolePermissionId.class)
@Data
public class RolePermission {
    @Id
    private Long roleId;

    @Id
    private Long permissionId;
}
