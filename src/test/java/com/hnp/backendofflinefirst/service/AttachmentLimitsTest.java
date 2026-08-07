package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AttachmentKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ceiling lookup and the validation rails around it.
 *
 * <p>The lookup matters because every enforcement point — the two fill pages, the upload
 * endpoint — asks the same object the same question. If it answered with the wrong kind's
 * ceiling, an operator could be stopped at three voice notes or allowed thirty photos, and
 * nothing else in the stack would notice.
 */
class AttachmentLimitsTest {

    private static AppSettingsService.AttachmentLimits limits() {
        return new AppSettingsService.AttachmentLimits(3, 1, 2, 120, 90);
    }

    @Test
    void returnsTheCountForEachKind() {
        assertThat(limits().maxCountFor(AttachmentKind.IMAGE)).isEqualTo(3);
        assertThat(limits().maxCountFor(AttachmentKind.AUDIO)).isEqualTo(1);
        assertThat(limits().maxCountFor(AttachmentKind.VIDEO)).isEqualTo(2);
    }

    @Test
    void treatsAnAbsentKindAsAllowingNothing() {
        // Reached when a field's data type is not a media type at all. Zero is the safe answer:
        // any count check then refuses, rather than silently defaulting to a real ceiling.
        assertThat(limits().maxCountFor(null)).isZero();
    }

    @Test
    void returnsDurationsInMillisecondsForTimedKindsOnly() {
        assertThat(limits().maxDurationMsFor(AttachmentKind.AUDIO)).isEqualTo(120_000L);
        assertThat(limits().maxDurationMsFor(AttachmentKind.VIDEO)).isEqualTo(90_000L);
    }

    @Test
    void hasNoDurationForAPhoto() {
        // Null rather than zero, so the caller can tell "no limit applies" from "limit is zero".
        assertThat(limits().maxDurationMsFor(AttachmentKind.IMAGE)).isNull();
        assertThat(limits().maxDurationMsFor(null)).isNull();
    }

    @Test
    void rejectsACountOutsideTheAllowedRange() {
        AppSettingsService service = new AppSettingsService(null);
        assertThatThrownBy(() -> service.saveAttachmentLimits(
                new AppSettingsService.AttachmentLimits(0, 1, 1, 120, 120)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("تصویر");
        assertThatThrownBy(() -> service.saveAttachmentLimits(
                new AppSettingsService.AttachmentLimits(99, 1, 1, 120, 120)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsADurationOutsideTheAllowedRange() {
        AppSettingsService service = new AppSettingsService(null);
        // The upper rail is what stops a mis-set value quietly turning an 11 MB video into a
        // multi-hundred-megabyte one.
        assertThatThrownBy(() -> service.saveAttachmentLimits(
                new AppSettingsService.AttachmentLimits(3, 1, 1, 120, 99_999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ویدئو");
        assertThatThrownBy(() -> service.saveAttachmentLimits(
                new AppSettingsService.AttachmentLimits(3, 1, 1, 1, 120)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesBeforeWritingAnything() {
        // The repository is null here on purpose: a valid-looking call would NPE on the first
        // write. Reaching the exception without one proves nothing was persisted.
        AppSettingsService service = new AppSettingsService(null);
        assertThatThrownBy(() -> service.saveAttachmentLimits(
                new AppSettingsService.AttachmentLimits(3, 1, 1, 120, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
