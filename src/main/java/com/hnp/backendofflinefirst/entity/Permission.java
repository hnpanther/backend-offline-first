package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One permission per HTTP endpoint. {@link #code} is {@code METHOD:path} (e.g. {@code GET:/locations})
 * and is checked via Spring Security {@code hasAuthority()}.
 */
@Entity
@Table(name = "permissions")
@Data
public class Permission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Authority string: METHOD + ':' + path pattern. */
    @Column(name = "code", unique = true, nullable = false)
    private String code;

    /** Persian label shown in role editor. */
    @Column(name = "name")
    private String name;

    /** UI grouping: general, admin, organization, master-data, operational, reports, api. */
    @Column(name = "category")
    private String category;

    /** HTTP verb: GET, POST, … */
    @Column(name = "http_method")
    private String httpMethod;

    /** Path pattern, e.g. /locations/{id}/delete */
    @Column(name = "endpoint_path")
    private String endpointPath;
}
