package com.hnp.backendofflinefirst.domain;

/**
 * Which alarms one webhook receiver is for (V21, {@code alarm_webhook_endpoints.rule_scope}).
 *
 * <p>Two values rather than a flag per kind of rule: the default rule is a row of
 * {@code alarm_rules} like any specific rule, so «only the default rule», «these three rules» and
 * «the default rule and the boiler rule» are all one {@link #SELECTED} list.
 */
public enum AlarmWebhookRuleScope {
    /** Every alarm, whichever rule raised it — including rules added after the receiver was. */
    ALL,
    /** Only alarms raised by the rules in {@code alarm_webhook_endpoint_rules}. */
    SELECTED;

    /** The form's value; anything but {@code SELECTED} is {@code ALL}, which loses nothing. */
    public static AlarmWebhookRuleScope parse(String raw) {
        return raw != null && raw.trim().equalsIgnoreCase(SELECTED.name()) ? SELECTED : ALL;
    }
}
