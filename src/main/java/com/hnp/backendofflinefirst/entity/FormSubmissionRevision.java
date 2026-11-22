package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * An answer map that a later edit of a form submission replaced.
 *
 * <p>Sheet-form twin of {@link LogSheetRevision}: append-only, holds the <em>old</em> value,
 * excluded from repository audit (this table <em>is</em> the trail).
 */
@Data
@Entity
@Table(name = "form_submission_revisions")
public class FormSubmissionRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "form_submission_id", nullable = false)
    private Long formSubmissionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_data", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> formData;

    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;

    @Column(name = "recorded_at")
    private Long recordedAt;

    @Column(name = "superseded_by_user_id")
    private Long supersededByUserId;

    @Column(name = "superseded_at", nullable = false)
    private Long supersededAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "superseded_source", nullable = false, length = 20)
    private ActionSource supersededSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "submission_status", nullable = false, length = 20)
    private FormSubmissionStatus submissionStatus;
}
