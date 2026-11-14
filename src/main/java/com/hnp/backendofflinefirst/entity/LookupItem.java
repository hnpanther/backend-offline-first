package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry of a {@link LookupList} (V22, roadmap §31 Phase 2).
 *
 * <p>The three rules the module rests on:
 * <ul>
 *   <li><b>{@link #code} is the identity</b> — unique in its list case-insensitively, never changed,
 *       never reused for another person. A record that stores {@code {code, label}} must still
 *       mean the same entry next year.</li>
 *   <li><b>«Delete» is deactivation</b> — {@link #deactivatedAt} is the instant it left service,
 *       kept apart from {@link #updatedAt} because every later edit moves that one.</li>
 *   <li><b>A record keeps its own label</b>, so renaming or deactivating an item never rewrites
 *       history — the reason removing a list from a device can ever be safe.</li>
 * </ul>
 */
@Entity
@Table(name = "lookup_items")
@Data
public class LookupItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "list_id", nullable = false)
    private Long listId;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "label", nullable = false)
    private String label;

    /** The list's declared columns, by key. An empty value is absent, never stored as "". */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> attributes = new LinkedHashMap<>();

    /** An item of the list's {@code parent_list_id}, never of this list. */
    @Column(name = "parent_item_id")
    private Long parentItemId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "deactivated_at")
    private Long deactivatedAt;

    /** Set once, by «ناشناس‌سازی»; an anonymised item can never be edited or reactivated. */
    @Column(name = "anonymised_at")
    private Long anonymisedAt;

    /** Derived: the normalised code, label and searchable columns. Never shown. */
    @Column(name = "search_text", nullable = false)
    private String searchText = "";

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    public boolean isAnonymised() {
        return anonymisedAt != null;
    }
}
