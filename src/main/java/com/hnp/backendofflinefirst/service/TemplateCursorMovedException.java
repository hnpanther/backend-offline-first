package com.hnp.backendofflinefirst.service;

/**
 * Another writer advanced a template's schedule cursor while this tick was generating from it.
 *
 * <p>Thrown so that the surrounding transaction rolls back and the rounds this tick had already
 * inserted disappear with it — the whole point of the compare-and-set in
 * {@link LogSheetGenerationService#runScheduled}. **It is not an error**: it is what correct
 * behaviour looks like when two instances of this application run against one database, which is
 * forbidden (see {@code docs/deployment.md}) but happens — a manual run left pointing at the
 * production database, or an upgrade where the old JVM has not exited. {@code LogSheetScheduler}
 * answers it with a WARN naming the template, not with the ERROR a real fault gets, because a log
 * that cries wolf is a log nobody reads.
 *
 * <p>It is also raised, harmlessly, when an administrator edits a template's schedule in the
 * moment a tick is running: the tick loses, one occurrence is skipped, and the administrator's
 * cursor stands. That is the right winner.
 */
public class TemplateCursorMovedException extends RuntimeException {

    private final Long templateId;

    public TemplateCursorMovedException(Long templateId, Long expected) {
        super("Template " + templateId + " was no longer parked on " + expected
                + " when this tick tried to advance it");
        this.templateId = templateId;
    }

    public Long getTemplateId() {
        return templateId;
    }
}
