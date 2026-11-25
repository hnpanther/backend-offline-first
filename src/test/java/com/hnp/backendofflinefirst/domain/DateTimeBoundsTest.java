package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DateTimeBoundsTest {
    @Test
    void inclusiveFixedAndRelativeLimitsBothApply() {
        long now = 1_800_000_000_000L;
        var bounds = Map.<String, Object>of(
                "dateTimeRange", Map.of("min", now - 120_000, "max", now + 120_000),
                "dateTimeRelative", Map.of("beforeMinutes", 1, "afterMinutes", 1));
        assertThat(FieldValidationSupport.dateTimeBoundsViolation(now - 60_000, bounds, now)).isNull();
        assertThat(FieldValidationSupport.dateTimeBoundsViolation(now + 60_000, bounds, now)).isNull();
        assertThat(FieldValidationSupport.dateTimeBoundsViolation(now - 60_001, bounds, now)).isNotNull();
        assertThat(FieldValidationSupport.dateTimeBoundsViolation(now + 120_001, bounds, now)).isNotNull();
    }

    @Test
    void editorRejectsInvalidRangesAndMinutes() {
        assertThatThrownBy(() -> FieldValidationSupport.DateTimeBounds.fromForm(
                "2026-11-25T10:00", "2026-11-24T10:00", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldValidationSupport.DateTimeBounds.fromForm(null, null, -1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldValidationSupport.DateTimeBounds.fromForm(null, null, 5256001, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
