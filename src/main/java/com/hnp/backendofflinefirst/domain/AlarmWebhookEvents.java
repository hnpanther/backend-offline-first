package com.hnp.backendofflinefirst.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The events an alarm webhook sends, and how an endpoint's subscription to them is stored (V21:
 * {@code alarm_webhook_endpoints.event_types}, comma-separated, null for every alarm event).
 */
public final class AlarmWebhookEvents {

    public static final String ALARM_RAISED = "ALARM_RAISED";
    public static final String ALARM_ESCALATED = "ALARM_ESCALATED";
    /**
     * Sent only by «ارسال آزمایشی» in the panel, to one endpoint. Never subscribed to and never
     * caused by an alarm, so a receiver can safely acknowledge it and do nothing.
     */
    public static final String WEBHOOK_TEST = "WEBHOOK_TEST";

    /** What an endpoint may subscribe to, in the order the panel lists them. */
    public static final List<String> SUBSCRIBABLE = List.of(ALARM_RAISED, ALARM_ESCALATED);

    private AlarmWebhookEvents() {
    }

    /** Whether an endpoint storing {@code column} receives {@code eventType}. */
    public static boolean subscribes(String column, String eventType) {
        return column == null || column.isBlank() || parse(column).contains(eventType);
    }

    public static Set<String> parse(String column) {
        Set<String> out = new LinkedHashSet<>();
        if (column != null) {
            Arrays.stream(column.split(",")).map(String::trim).filter(SUBSCRIBABLE::contains).forEach(out::add);
        }
        return out;
    }

    /**
     * The stored form of what an administrator ticked: null when nothing — or everything — is
     * ticked, so an endpoint subscribed to «all» also receives an event added in a later version.
     *
     * @throws IllegalArgumentException for a name that is not a subscribable event
     */
    public static String column(Collection<String> chosen) {
        if (chosen == null) {
            return null;
        }
        Set<String> picked = new LinkedHashSet<>();
        for (String name : chosen) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = name.trim();
            if (!SUBSCRIBABLE.contains(trimmed)) {
                throw new IllegalArgumentException("Unknown webhook event type.");
            }
            picked.add(trimmed);
        }
        if (picked.isEmpty() || picked.containsAll(SUBSCRIBABLE)) {
            return null;
        }
        return String.join(",", SUBSCRIBABLE.stream().filter(picked::contains).toList());
    }
}
