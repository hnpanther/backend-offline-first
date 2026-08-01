package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Named group of {@link Permission} codes assigned to users via {@code user_roles}.
 * System roles (ADMIN, HIGH_USER, SUPERVISOR, OPERATOR) cannot be deleted.
 */
@Entity
@Table(name = "roles")
@Data
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", unique = true, nullable = false)
    private String code;

    @Column(name = "name")
    private String name;
    @Column(name = "description")
    private String description;
    @Column(name = "system_role")
    private boolean systemRole;
    @Column(name = "created_at")
    private Long createdAt;
    @Column(name = "updated_at")
    private Long updatedAt;
}
