package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.LookupColumn;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * A reference-data list — personnel, contractors, violation types (V22, roadmap §31 Phase 2).
 *
 * <p>The list declares its own {@link #columns}; every item carries their values in
 * {@code attributes}. One generic module instead of a table per kind of base data: a table per
 * kind is a migration, an importer, a sync path, an admin page and a device store each time.
 *
 * <p>{@link #revision} is mapped <b>read-only</b>. It moves only through
 * {@code LookupListRepository.bumpRevision} ({@code SET revision = revision + 1}), so two
 * administrators editing one list at once cannot both write «revision 6» and lose a change a
 * device would otherwise have fetched. Read a fresh value with
 * {@code LookupListRepository.currentRevision}, never from an entity loaded earlier in the same
 * transaction.
 */
@Entity
@Table(name = "lookup_lists")
@Data
public class LookupList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Fixed once created — upper-cased, {@code [A-Za-z0-9_-]}. */
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", columnDefinition = "jsonb", nullable = false)
    private List<LookupColumn> columns = new ArrayList<>();

    /** The list whose items this list's items may name as their parent; never this list. */
    @Column(name = "parent_list_id")
    private Long parentListId;

    @Column(name = "revision", nullable = false, insertable = false, updatable = false)
    private long revision;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
