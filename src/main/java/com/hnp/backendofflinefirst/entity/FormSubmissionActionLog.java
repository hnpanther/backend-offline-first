package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.FormSubmissionActionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "form_submission_action_log")
public class FormSubmissionActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "form_submission_id", nullable = false)
    private Long formSubmissionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private FormSubmissionActionType action;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private ActionSource source;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "action_at", nullable = false)
    private Long actionAt;

    @Column(name = "recorded_at", nullable = false)
    private Long recordedAt;

    @Column(name = "comment", length = 2000)
    private String comment;
}
