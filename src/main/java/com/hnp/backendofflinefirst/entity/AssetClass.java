package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "asset_classes")
@Data
public class AssetClass {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @Column(name = "name", nullable = false)
    private String name;

    /**
     * The parent class when this row is a <strong>sub-class</strong>, null when it is top-level.
     *
     * <p><strong>One level, and the database enforces it</strong> (trigger
     * {@code trg_asset_classes_single_level}, migration V5): a class that already has a parent
     * may not become one. The scope queries in {@code AssetEntryRepository} look exactly one
     * level down, so a two-level chain would silently return the wrong assets.
     *
     * <p>A sub-class <em>inherits</em> its parent's field definitions and overrides them by
     * {@code field_key} — see {@code AssetClassFieldResolver}. It does not copy them, so a field
     * added to the parent reaches every sub-class.
     */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "created_at")
    private Long createdAt;
    @Column(name = "updated_at")
    private Long updatedAt;
}
