package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DateTimeValuesTest {

    @Test
    void recognisesTheType() {
        assertThat(DateTimeValues.isDateTimeField("datetime")).isTrue();
        assertThat(DateTimeValues.isDateTimeField("date")).isFalse();
        assertThat(DateTimeValues.isDateTimeField(null)).isFalse();
    }

    @Test
    void parsesCanonicalObjectBareEpochAndPanelString() {
        long epoch = 1_732_420_200_000L;
        assertThat(DateTimeValues.parseEpoch(Map.of("type", "datetime", "epochMs", epoch))).isEqualTo(epoch);
        assertThat(DateTimeValues.parseEpoch(epoch)).isEqualTo(epoch);
        assertThat(DateTimeValues.parseEpoch(String.valueOf(epoch))).isEqualTo(epoch);

        // 2024-11-24 07:30 Asia/Tehran
        Long fromPanel = DateTimeValues.parseEpoch("2024-11-24T07:30");
        assertThat(fromPanel).isNotNull();
        assertThat(DateTimeValues.canonical(fromPanel))
                .containsEntry("type", "datetime")
                .containsEntry("epochMs", fromPanel);
    }

    @Test
    void rejectsJunkAndOutOfRange() {
        assertThat(DateTimeValues.parseEpoch(-1L)).isNull();
        assertThat(DateTimeValues.parseEpoch(Map.of("type", "datetime"))).isNull();
        assertThat(DateTimeValues.parseEpoch("not-a-date")).isNull();
        assertThat(DateTimeValues.parseEpoch(null)).isNull();
        assertThat(DateTimeValues.canonical("garbage")).isNull();
    }
}
