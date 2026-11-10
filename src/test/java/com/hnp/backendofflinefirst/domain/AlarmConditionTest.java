package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.domain.AlarmCondition.Reading;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.hnp.backendofflinefirst.domain.FieldValidationSeverity.DANGER;
import static com.hnp.backendofflinefirst.domain.FieldValidationSeverity.OK;
import static com.hnp.backendofflinefirst.domain.FieldValidationSeverity.WARNING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic of an alarm condition, without a database.
 *
 * <p>Readings are listed newest first, the way the history query returns them. Day numbers are
 * the reading's day; {@code null} is a round on which the parameter was not answered.
 */
class AlarmConditionTest {

    private static final long DAY = 86_400_000L;

    @Test
    void okNeverBreachesAndWarningBreachesOnlyAWarningRule() {
        assertThat(AlarmCondition.breaches(OK, WARNING)).isFalse();
        assertThat(AlarmCondition.breaches(null, WARNING)).isFalse();
        assertThat(AlarmCondition.breaches(WARNING, WARNING)).isTrue();
        assertThat(AlarmCondition.breaches(DANGER, WARNING)).isTrue();
        // A danger rule asked about danger: a warning is a normal reading for it.
        assertThat(AlarmCondition.breaches(WARNING, DANGER)).isFalse();
        assertThat(AlarmCondition.breaches(DANGER, DANGER)).isTrue();
    }

    @Test
    void theRunIsTheConsecutiveBreachesAtTheHead() {
        List<Reading> history = history(5, WARNING, 4, WARNING, 3, OK, 2, WARNING);

        List<Reading> run = AlarmCondition.leadingBreachRun(history, WARNING, null);

        assertThat(run).extracting(Reading::readAt).containsExactly(day(5), day(4));
    }

    @Test
    void anUnreadReadingNeitherCountsNorResets() {
        List<Reading> history = history(4, WARNING, 3, null, 2, WARNING, 1, WARNING);

        List<Reading> run = AlarmCondition.leadingBreachRun(history, WARNING, null);

        assertThat(run).extracting(Reading::readAt).containsExactly(day(4), day(2), day(1));
    }

    @Test
    void theWindowIsAnchoredAtTheNewestBreach() {
        List<Reading> history = history(13, WARNING, 12, WARNING, 11, WARNING, 1, WARNING);

        assertThat(AlarmCondition.leadingBreachRun(history, WARNING, 7))
                .extracting(Reading::readAt).containsExactly(day(13), day(12), day(11));
        assertThat(AlarmCondition.leadingBreachRun(history, WARNING, null)).hasSize(4);
    }

    @Test
    void theConditionIsMetByTheNthOldestOfTheRun() {
        List<Reading> run = AlarmCondition.leadingBreachRun(
                history(5, WARNING, 4, WARNING, 3, WARNING, 2, WARNING), WARNING, null);

        // Four in a row with consecutive = 3: it became true on day 4, the third of them.
        assertThat(AlarmCondition.conditionMetBy(run, 3).readAt()).isEqualTo(day(4));
        assertThat(AlarmCondition.conditionMetBy(run, 5)).isNull();
    }

    @Test
    void clearingNeedsClearAfterNormalReadingsInARow() {
        List<Reading> normals = AlarmCondition.leadingNormalRun(
                history(6, OK, 5, null, 4, OK, 3, WARNING), WARNING);

        assertThat(normals).extracting(Reading::readAt).containsExactly(day(6), day(4));
        assertThat(AlarmCondition.clearedBy(normals, 2).readAt()).isEqualTo(day(6));
        assertThat(AlarmCondition.clearedBy(normals, 1).readAt()).isEqualTo(day(4));
        assertThat(AlarmCondition.clearedBy(normals, 3)).isNull();
    }

    @Test
    void aWarningIsNormalForADangerRule() {
        List<Reading> history = history(3, WARNING, 2, DANGER);

        assertThat(AlarmCondition.leadingBreachRun(history, DANGER, null)).isEmpty();
        assertThat(AlarmCondition.leadingNormalRun(history, DANGER)).hasSize(1);
    }

    @Test
    void theWorstSeverityWins() {
        assertThat(AlarmCondition.worst(history(3, WARNING, 2, DANGER, 1, OK))).isEqualTo(DANGER);
        assertThat(AlarmCondition.worst(List.of())).isNull();
    }

    private static long day(int n) {
        return 1_700_000_000_000L + n * DAY;
    }

    /** Pairs of (day, severity), newest first. */
    private static List<Reading> history(Object... pairs) {
        List<Reading> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            int d = (Integer) pairs[i];
            FieldValidationSeverity severity = (FieldValidationSeverity) pairs[i + 1];
            out.add(new Reading((long) d, (long) d, day(d), 1L, severity, severity == null ? null : "v" + d));
        }
        return out;
    }
}
