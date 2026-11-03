package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * A question that belongs to the <strong>round</strong> rather than to an asset on it.
 *
 * <p>«آیا همه لوله‌ها بازدید شد؟» is asked once per sheet, not once per pump. Structurally this
 * is a {@link FieldDefinition} with a different owner — same key, label, type, unit, validation
 * and order — which is the whole reason the feature is small: {@code FieldValidationSupport},
 * {@code FormDataValidationSupport} and {@code FieldDefinitionSnapshot} all take a bare
 * definition list and never ask where it came from.
 *
 * <h2>The owner is exactly one of two things</h2>
 *
 * <ul>
 *   <li>{@link #templateId} — every round raised from that template asks these;</li>
 *   <li>{@link #logSheetId} — this one round asks these (a custom, template-less sheet).</li>
 * </ul>
 *
 * <p>A database CHECK enforces that precisely one is set. A row owned by both would be asked
 * twice on the same round and edited from two pages, and neither failure announces itself.
 *
 * <h2>{@code deleted} is a switch, not a tombstone</h2>
 *
 * <p>Same meaning as on {@link FieldDefinition}: a switched-off question is not captured into a
 * sheet raised from now on, while a sheet raised earlier keeps rendering it from its own frozen
 * snapshot. Switching off and on again is a round trip, not a reset.
 *
 * @see com.hnp.backendofflinefirst.service.LogSheetFieldService
 */
@Entity
@Table(name = "log_sheet_fields")
@Data
public class LogSheetField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Set when the template owns this question; null when a single sheet does. */
    @Column(name = "template_id")
    private Long templateId;

    /** Set when one sheet owns this question; null when a template does. */
    @Column(name = "log_sheet_id")
    private Long logSheetId;

    @Column(name = "field_key", nullable = false)
    private String key;

    @Column(name = "label")
    private String label;

    @Column(name = "data_type")
    private String dataType;

    @Column(name = "unit")
    private String unit;

    /**
     * Honoured by the validator, and <strong>nothing sets it in this release</strong>.
     *
     * <p>Deliberate: a required question an older tablet does not know about is never answered,
     * so the server refuses the completion and the operator is stuck at the equipment with
     * nothing they can do. Switching this on is safe once every device carries the field —
     * roadmap §24, "the rollout constraint".
     */
    @Column(name = "required")
    private boolean required;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation", columnDefinition = "jsonb")
    private Map<String, Object> validation;

    @Column(name = "sort_order")
    private Integer order;

    @Column(name = "version")
    private Integer version;

    /** Switched off: not offered on new rounds, still rendered on rounds that froze it. */
    @Column(name = "deleted")
    private boolean deleted;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    /**
     * The same question as a {@link FieldDefinition}, so every existing renderer and validator
     * can take it unchanged.
     *
     * <p>{@code classId} stays null on purpose. The per-asset paths filter a definition list by
     * {@code classId} to keep two classes' identically-keyed fields apart; a sheet-level list
     * belongs to no class and is never mixed with one, so there is nothing to filter by. Any
     * path that filters by class must therefore not be handed these — which is why they travel
     * in their own snapshot column and their own answers column rather than being appended to
     * the per-asset ones.
     */
    public FieldDefinition toFieldDefinition() {
        FieldDefinition fd = new FieldDefinition();
        fd.setId(id);
        fd.setClassId(null);
        fd.setKey(key);
        fd.setLabel(label);
        fd.setDataType(dataType);
        fd.setUnit(unit);
        fd.setRequired(required);
        fd.setValidation(validation);
        fd.setOrder(order);
        fd.setVersion(version);
        fd.setDeleted(deleted);
        return fd;
    }
}
