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
    private Long id;

    @Column(unique = true, nullable = false)
    private String code;

    private String name;
    private String description;
    private boolean systemRole;
    private Long createdAt;
    private Long updatedAt;
}
