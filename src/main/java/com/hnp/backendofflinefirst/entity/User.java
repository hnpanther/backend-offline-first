package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "username", unique = true, nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false)
    private UserAuthType authType = UserAuthType.LOCAL;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "national_code", length = 15)
    private String nationalCode;

    @Column(name = "phone_number", length = 15)
    private String phoneNumber;

    @Column(name = "nfc_tag_id", length = 50)
    private String nfcTagId;

    @Column(name = "active")
    private boolean active;
    @Column(name = "created_at")
    private Long createdAt;
    @Column(name = "updated_at")
    private Long updatedAt;
}
