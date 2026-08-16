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

    /** Required staff number — unique case-insensitively (ux_users_personnel_code_lower). */
    @Column(name = "personnel_code", length = 50, nullable = false)
    private String personnelCode;

    /** Optional free-text shift label; intentionally unvalidated beyond length. */
    @Column(name = "shift", length = 100)
    private String shift;

    /**
     * Organizational unit from the org chart, e.g. «مهندسی نگهداری و تعمیرات».
     *
     * <p>Optional free text, and <b>not</b> a link to {@code operational_units}. That table is
     * an access-control structure — it decides which log sheets this user can reach — while
     * this is a personnel attribute that grants nothing. Keeping them separate is what stops a
     * typo in an HR spreadsheet from changing somebody's access scope.
     */
    @Column(name = "org_unit", length = 150)
    private String orgUnit;

    /** Optional free-text job title, e.g. «کارشناس ارشد ابزار دقیق». Grants nothing; roles do. */
    @Column(name = "org_position", length = 150)
    private String orgPosition;

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
