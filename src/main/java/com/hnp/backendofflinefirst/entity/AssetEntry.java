package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "asset_entries")
@Data
public class AssetEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "asset_code", nullable = false)
    private String assetCode;

    @Column(name = "nfc_tag_id")
    private String nfcTagId;

    /**
     * Physical NFC chip serial / UID, e.g. {@code 00:aa:34:9f:12:cd}. Optional, but unique
     * when supplied. Unlike {@link #nfcTagId} it is never inherited from the sub-function and
     * never released when the asset goes inactive — it identifies the piece of hardware, not
     * the position it is mounted on.
     */
    @Column(name = "nfc_serial")
    private String nfcSerial;

    @Column(name = "class_id")
    private Long classId;
    @Column(name = "asset_name")
    private String assetName;
    /** Optional secondary (Persian) title; display-only, never used for lookups. */
    @Column(name = "asset_name_fa")
    private String assetNameFa;
    @Column(name = "sub_function_id", nullable = false)
    private Long subFunctionId;
    @Column(name = "description")
    private String description;
    /** When false, asset is excluded from log-sheet template preview and generation. */
    @Column(name = "active")
    private boolean active = true;

    /**
     * Current operational state, e.g. «در سرویس» / «خارج از سرویس».
     *
     * <p>Written by {@link com.hnp.backendofflinefirst.service.AssetStatusService} when a log
     * sheet carrying a {@code status} field is completed, and restored when that completion is
     * undone. Every change is recorded in {@code asset_status_history} — never edit this column
     * directly without writing that history, or the reversal logic loses its anchor.
     */
    @Column(name = "status")
    private String status;
    @Column(name = "created_at")
    private Long createdAt;
    @Column(name = "updated_at")
    private Long updatedAt;

    /**
     * Where this asset's form schema comes from — see {@link com.hnp.backendofflinefirst.domain.FieldSource}.
     *
     * <p>A {@code String} rather than {@code @Enumerated}, matching how every other enum-shaped
     * column in this schema is stored, and constrained in the database so an unreadable value
     * cannot arrive. Parse it with {@code FieldSource.from(...)}, which is null-safe.
     */
    @Column(name = "field_source", nullable = false)
    private String fieldSource = com.hnp.backendofflinefirst.domain.FieldSource.INHERIT.name();

    /**
     * This asset's own parameter overrides, keyed by {@code field_key}. Null for
     * {@code INHERIT}, and the database enforces that pairing.
     *
     * <p>For {@code RANGES_ONLY} each value carries only the bands:
     * <pre>{"Ampere": {"warning": {"min": 18, "max": 42}, "danger": {"min": 12, "max": 55}}}</pre>
     * For {@code FULL} it may also carry {@code label}, {@code dataType}, {@code unit},
     * {@code required}, {@code sortOrder}, {@code options}, and {@code "removed": true} to drop
     * one of the class's fields for this asset alone.
     *
     * <p>JSONB on the asset rather than a child table because these are always read as a whole
     * for one asset and never queried individually — and because the {@code AssetEntry} row is
     * already loaded when a sheet is generated, so resolution costs no extra query.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_overrides", columnDefinition = "jsonb")
    private Map<String, Object> fieldOverrides;
}
