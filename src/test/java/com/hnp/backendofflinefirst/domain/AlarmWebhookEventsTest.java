package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlarmWebhookEventsTest {

    @Test
    void nothingTickedAndEverythingTickedAreBothStoredAsEveryEvent() {
        assertThat(AlarmWebhookEvents.column(null)).isNull();
        assertThat(AlarmWebhookEvents.column(List.of())).isNull();
        assertThat(AlarmWebhookEvents.column(List.of("ALARM_ESCALATED", "ALARM_RAISED"))).isNull();
        assertThat(AlarmWebhookEvents.subscribes(null, "ALARM_RAISED")).isTrue();
    }

    @Test
    void aSubsetIsStoredInTheListedOrderAndOnlyItIsReceived() {
        String column = AlarmWebhookEvents.column(List.of("ALARM_ESCALATED"));
        assertThat(column).isEqualTo("ALARM_ESCALATED");
        assertThat(AlarmWebhookEvents.subscribes(column, "ALARM_ESCALATED")).isTrue();
        assertThat(AlarmWebhookEvents.subscribes(column, "ALARM_RAISED")).isFalse();
    }

    @Test
    void theTestEventIsNeverSubscribable() {
        assertThatThrownBy(() -> AlarmWebhookEvents.column(List.of("WEBHOOK_TEST")))
                .hasMessage("Unknown webhook event type.");
        assertThat(AlarmWebhookEvents.subscribes("ALARM_RAISED", "WEBHOOK_TEST")).isFalse();
    }
}
