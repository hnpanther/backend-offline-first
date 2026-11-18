package com.hnp.backendofflinefirst.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three sentences a round's page says about the tablet's clock (issue B-17, V28). Worded as
 * what each fact proves, and silent when there is nothing worth saying — a page that mentions the
 * clock on every round teaches supervisors to ignore it.
 */
class LogSheetViewHelperClockNoteTest {

    private final LogSheetViewHelper view = new LogSheetViewHelper();

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    @Test
    void anOffsetOfDriftSaysNothing() {
        assertThat(view.clockOffsetNote(null)).isNull();
        assertThat(view.clockOffsetNote(0L)).isNull();
        assertThat(view.clockOffsetNote(90_000L)).isNull();
        assertThat(view.clockOffsetNote(-119_999L)).isNull();
    }

    @Test
    void theSignOfTheOffsetSaysWhichWayTheClockWasWrong() {
        // Server minus device: positive means the tablet was behind.
        assertThat(view.clockOffsetNote(10 * MINUTE)).contains("۱۰ دقیقه عقب");
        assertThat(view.clockOffsetNote(-(2 * HOUR + 5 * MINUTE))).contains("۲ ساعت و ۵ دقیقه جلو");
        assertThat(view.clockOffsetNote(-3 * DAY)).contains("۳ روز جلو");
    }

    @Test
    void aReplacedClaimIsReportedWithHowFarAheadItWas() {
        assertThat(view.reportedCompletionNote(null, 1000L)).isNull();

        long stored = 1_000_000_000L;
        String note = view.reportedCompletionNote(stored + 2 * DAY + 4 * HOUR, stored);
        assertThat(note).contains("۲ روز و ۴ ساعت جلوتر").contains("زمان دریافت سرور");
        assertThat(view.reportedCompletionNote(stored + DAY, null)).doesNotContain("جلوتر)");
    }

    @Test
    void lateDeliveryIsSaidOnlyPastTheThreshold() {
        long completed = 1_000_000_000L;
        assertThat(view.lateDeliveryNote(null, completed)).isNull();
        assertThat(view.lateDeliveryNote(completed, null)).isNull();
        assertThat(view.lateDeliveryNote(completed, completed + 71 * HOUR)).isNull();
        assertThat(view.lateDeliveryNote(completed, completed + 72 * HOUR)).contains("۳ روز");
        // A completion after its own receipt (inside the drift tolerance) is not «late».
        assertThat(view.lateDeliveryNote(completed + MINUTE, completed)).isNull();
    }

    @Test
    void durationsUseTheTwoLargestUnitsAndPersianDigits() {
        assertThat(LogSheetViewHelper.durationFa(0)).isEqualTo("۱ دقیقه");
        assertThat(LogSheetViewHelper.durationFa(59 * MINUTE)).isEqualTo("۵۹ دقیقه");
        assertThat(LogSheetViewHelper.durationFa(HOUR)).isEqualTo("۱ ساعت");
        assertThat(LogSheetViewHelper.durationFa(25 * HOUR + 30 * MINUTE)).isEqualTo("۱ روز و ۱ ساعت");
        assertThat(LogSheetViewHelper.durationFa(-5 * MINUTE)).isEqualTo("۱ دقیقه");
        // To the nearest minute: two days less a moment is two days, not «one day and 23 hours».
        assertThat(LogSheetViewHelper.durationFa(2 * DAY - 150)).isEqualTo("۲ روز");
        assertThat(LogSheetViewHelper.durationFa(90 * 1000)).isEqualTo("۲ دقیقه");
    }
}
