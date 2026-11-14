package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing the log-sheet list's filters (roadmap §26). What the parser refuses matters as much as
 * what it accepts: a value that cannot be read is refused rather than ignored, because ignoring it
 * answers with every row under a heading that implies a filter.
 */
class LogSheetListFilterTest {

    private static final long NOW = 1_800_000_000_000L;

    private static LogSheetListFilter parse(Map<String, String> params) {
        return LogSheetListFilter.fromParameters(params, null, null, NOW);
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void nothingIsNoFilter() {
        LogSheetListFilter f = parse(params());
        assertThat(f.unitId()).isNull();
        assertThat(f.templateId()).isNull();
        assertThat(f.origin()).isNull();
        assertThat(f.assigneeUserId()).isNull();
        assertThat(f.overdueOnly()).isFalse();
        assertThat(f.categoryId()).isNull();
        assertThat(f.activeFilterCount()).isZero();
    }

    @Test
    void anEmptyValueIsWhatAClearedSelectPostsAndMeansNoFilter() {
        LogSheetListFilter f = parse(params("unitId", "", "templateId", " ", "origin", "", "categoryId", ""));
        assertThat(f.activeFilterCount()).isZero();
    }

    @Test
    void everyFilterIsReadAndCounted() {
        LogSheetListFilter f = parse(params("unitId", "3", "templateId", "4", "origin", "scheduled",
                "assigneeUserId", "5", "overdue", "true", "categoryId", "6", "status", "PENDING"));
        assertThat(f.unitId()).isEqualTo(3L);
        assertThat(f.templateId()).isEqualTo(4L);
        assertThat(f.origin()).isEqualTo(GenerationMode.SCHEDULED);
        assertThat(f.assigneeUserId()).isEqualTo(5L);
        assertThat(f.overdueAsOf()).isEqualTo(NOW);
        assertThat(f.categoryId()).isEqualTo(6L);
        assertThat(f.activeFilterCount()).isEqualTo(7);
    }

    @Test
    void anUnreadableIdIsRefusedNotIgnored() {
        assertThatThrownBy(() -> parse(params("unitId", "abc")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unitId");
        assertThatThrownBy(() -> parse(params("templateId", "-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(params("assigneeUserId", "0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(params("categoryId", "1".repeat(19))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownOriginIsRefused() {
        assertThatThrownBy(() -> parse(params("origin", "IMPORTED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("origin");
    }

    @Test
    void overdueIsOnlyOnForAYes() {
        assertThat(parse(params("overdue", "on")).overdueOnly()).isTrue();
        assertThat(parse(params("overdue", "false")).overdueOnly()).isFalse();
        assertThat(parse(params("overdue", "maybe")).overdueOnly()).isFalse();
    }

    @Test
    void aWholeNumberSearchIsAlsoASheetIdInEitherDigits() {
        assertThat(parse(params("q", "4711")).termAsId()).isEqualTo(4711L);
        assertThat(parse(params("q", "۴۷۱۱")).termAsId()).isEqualTo(4711L);
        assertThat(parse(params("q", "٤٧١١")).termAsId()).isEqualTo(4711L);
        assertThat(parse(params("q", "pump 12")).termAsId()).isNull();
        assertThat(parse(params("q", "1".repeat(19))).termAsId()).isNull();
    }

    @Test
    void theOldConstructorShapeStillMeansNoNarrowing() {
        LogSheetListFilter f = new LogSheetListFilter(null, null, LogSheetDateField.CREATED_AT, null, null);
        assertThat(f.activeFilterCount()).isZero();
        assertThat(f.overdueOnly()).isFalse();
    }
}
