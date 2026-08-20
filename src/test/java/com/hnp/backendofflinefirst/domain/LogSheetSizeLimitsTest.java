package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The two thresholds that bound how many assets one log sheet may hold.
 *
 * <p>The distinction these tests protect is not arithmetic: it is that {@code exceedsMax} and
 * {@code deservesWarning} are asked by <b>different callers with different powers</b> — template
 * and custom-sheet creation refuse, the scheduler only warns. If the two ever collapse into one
 * answer, either a save stops refusing or a scheduled round starts disappearing.
 */
class LogSheetSizeLimitsTest {

    @Test
    void countsAtOrBelowTheMaximumAreAccepted() {
        LogSheetSizeLimits limits = new LogSheetSizeLimits(300, 150);

        assertThat(limits.exceedsMax(0)).isFalse();
        assertThat(limits.exceedsMax(299)).isFalse();
        // The maximum is inclusive: 300 is "300 allowed", not "299 allowed".
        assertThat(limits.exceedsMax(300)).isFalse();
        assertThat(limits.exceedsMax(301)).isTrue();
    }

    @Test
    void requireWithinMaxNamesBothNumbers() {
        LogSheetSizeLimits limits = new LogSheetSizeLimits(300, 150);

        assertThatCode(() -> limits.requireWithinMax(300)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limits.requireWithinMax(412))
                .isInstanceOf(IllegalArgumentException.class)
                // Both, deliberately: "too many" alone does not tell the person at the scope
                // picker whether to drop one asset or rethink the scope.
                .hasMessageContaining("412 assets")
                .hasMessageContaining("maximum is 300")
                .hasMessageStartingWith(LogSheetSizeLimits.EXCEEDED_MESSAGE_PREFIX);
    }

    @Test
    void theWarningThresholdIsIndependentOfTheMaximum() {
        LogSheetSizeLimits limits = new LogSheetSizeLimits(300, 150);

        assertThat(limits.deservesWarning(150)).isFalse();
        assertThat(limits.deservesWarning(151)).isTrue();
        // Still worth warning about when it is also over the maximum — the scheduler generates
        // that sheet anyway, so the log line is the only thing anyone will ever see.
        assertThat(limits.deservesWarning(400)).isTrue();
    }

    @Test
    void aWarningThresholdAboveTheMaximumIsPulledDownToIt() {
        LogSheetSizeLimits limits = new LogSheetSizeLimits(100, 500);

        assertThat(limits.warnAt()).isEqualTo(100);
        // Otherwise the warning could only fire after a refusal, so it would never fire at all.
        assertThat(limits.deservesWarning(120)).isTrue();
    }

    @Test
    void aMaximumOfZeroOrLessDisablesTheRefusalButNotTheWarning() {
        LogSheetSizeLimits limits = new LogSheetSizeLimits(0, 150);

        assertThat(limits.isUnlimited()).isTrue();
        assertThat(limits.exceedsMax(100_000)).isFalse();
        assertThatCode(() -> limits.requireWithinMax(100_000)).doesNotThrowAnyException();
        // "You asked for this" and "nobody should ever hear about it" are different statements.
        assertThat(limits.deservesWarning(100_000)).isTrue();
    }

    @Test
    void aWarningThresholdOfZeroOrLessSilencesTheWarning() {
        LogSheetSizeLimits limits = new LogSheetSizeLimits(300, 0);

        assertThat(limits.deservesWarning(299)).isFalse();
        // The refusal is untouched by silencing the warning; they are separate switches.
        assertThat(limits.exceedsMax(301)).isTrue();
    }

    @Test
    void anUnlimitedMaximumDoesNotSwallowTheWarningThreshold() {
        LogSheetSizeLimits limits = new LogSheetSizeLimits(-1, 150);

        // The clamp only applies when there IS a maximum; otherwise `warnAt > max` is true for
        // every threshold and the warning would be pinned to a meaningless number.
        assertThat(limits.warnAt()).isEqualTo(150);
        assertThat(limits.deservesWarning(151)).isTrue();
    }
}
