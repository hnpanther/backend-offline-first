package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "role_permissions")
@IdClass(RolePermissionId.class)
@Data
public class RolePermission {
    @Id
    @Column(name = "role_id")
    private Long roleId;

    @Id
    @Column(name = "permission_id")
    private Long permissionId;
}
