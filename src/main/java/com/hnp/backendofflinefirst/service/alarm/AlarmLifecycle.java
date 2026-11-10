package com.hnp.backendofflinefirst.service.alarm;

import com.hnp.backendofflinefirst.domain.AlarmCloseReason;
import com.hnp.backendofflinefirst.domain.AlarmEventType;
import com.hnp.backendofflinefirst.domain.AlarmState;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.entity.Alarm;
import com.hnp.backendofflinefirst.entity.AlarmEvent;
import com.hnp.backendofflinefirst.repository.AlarmEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The two things every writer of an alarm does: record a line on its timeline, and close it.
 *
 * <p>One place, because three writers close alarms — the evaluation, a supervisor, and an
 * administrator switching a rule off — and the closing has a rule of its own (the CHECK in V17:
 * a closed alarm always has a reason and a time, a live one never does). Three copies of that
 * would be three chances to leave a row the constraint refuses at commit time, where the error
 * no longer says which writer it was.
 */
@Component
@RequiredArgsConstructor
public class AlarmLifecycle {

    private static final int MAX_TEXT = 1000;

    private final AlarmEventRepository eventRepository;

    public void record(Alarm alarm, AlarmEventType type, Long actorUserId, Long logSheetId,
                       FieldValidationSeverity severity, String value, String comment, long at) {
        AlarmEvent event = new AlarmEvent();
        event.setAlarmId(alarm.getId());
        event.setEventType(type);
        event.setOccurredAt(at);
        event.setActorUserId(actorUserId);
        event.setLogSheetId(logSheetId);
        event.setSeverity(severity);
        event.setValueText(trim(value));
        event.setComment(trim(comment));
        eventRepository.save(event);
    }

    /**
     * Closes a live alarm. The caller saves it.
     *
     * @param actorUserId null when the system closed it
     */
    public void close(Alarm alarm, AlarmCloseReason reason, Long actorUserId, String comment, long now) {
        alarm.setState(AlarmState.CLOSED);
        alarm.setCloseReason(reason);
        alarm.setClosedAt(now);
        alarm.setClosedByUserId(actorUserId);
        alarm.setCloseComment(trim(comment));
        alarm.setUpdatedAt(now);
        record(alarm, AlarmEventType.CLOSED, actorUserId, null, null, null,
                comment == null || comment.isBlank() ? reason.labelFa() : reason.labelFa() + " — " + comment.trim(),
                now);
    }

    static String trim(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_TEXT ? trimmed : trimmed.substring(0, MAX_TEXT);
    }
}
