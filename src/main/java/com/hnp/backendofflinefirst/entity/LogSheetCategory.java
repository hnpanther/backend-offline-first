package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * «Which kind of work is this round» — a safety checklist, a security patrol, a pump round
 * (V19, roadmap §28).
 *
 * <p>A template names one, and every round it raises freezes both the id and the name
 * ({@code log_sheets.category_id} / {@code category_name}). Grouping uses the id; display uses
 * the frozen name, so renaming a category next year does not rewrite what this year's rounds
 * were called. A category still named by a template or a round cannot be deleted — only made
 * inactive, which takes it out of the pickers and keeps the history.
 *
 * <p>The {@code code} is fixed once created: it is what a report or an integration groups by,
 * and a code that can change silently splits one group into two.
 */
@Entity
@Table(name = "log_sheet_categories")
@Data
public class LogSheetCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;
}
