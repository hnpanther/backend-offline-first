package com.hnp.backendofflinefirst.domain;

/**
 * How soon a supervisor should look at an alarm, decided when the rule is written — ISA-18.2's
 * priority from consequence × time to respond, set once per parameter rather than per reading.
 */
public enum AlarmPriority {
    LOW,
    MEDIUM,
    HIGH;

    /** Persian label for the panel. */
    public String labelFa() {
        return switch (this) {
            case LOW -> "کم";
            case MEDIUM -> "متوسط";
            case HIGH -> "زیاد";
        };
    }

    public static AlarmPriority fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (AlarmPriority p : values()) {
            if (p.name().equalsIgnoreCase(raw.trim())) {
                return p;
            }
        }
        return null;
    }
}
