package com.hnp.backendofflinefirst.service.alarm;

import com.hnp.backendofflinefirst.domain.AlarmCloseReason;
import com.hnp.backendofflinefirst.domain.AlarmEventType;
import com.hnp.backendofflinefirst.domain.AlarmState;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.entity.Alarm;
import com.hnp.backendofflinefirst.entity.AlarmEvent;
import com.hnp.backendofflinefirst.repository.AlarmEventRepository;
import com.hnp.backendofflinefirst.repository.AlarmRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.OperationalUnitScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * The supervisor's side of alarms: who sees which, and the two decisions they can take.
 *
 * <h2>Scope is the log sheet's scope</h2>
 *
 * <p>An alarm belongs to the unit responsible for the round that raised it, and a person sees it
 * exactly when they could see that unit's rounds — {@code getAccessibleUnitIds}, which includes the
 * units below the ones they supervise. An unrestricted reader ({@code isUnitScopedOnly() == false})
 * sees every alarm, including one whose round had no unit. The same rule decides who may
 * acknowledge or close: seeing an alarm and being allowed to act on it are the same question,
 * with the permission ({@code POST:/alarms/{id}/…}) in front of it.
 *
 * <h2>Acknowledge, and close</h2>
 *
 * <p>Acknowledging is ISA-18.2's: «I have seen this and it is being dealt with». It is one click and
 * an optional note, and the alarm stays live until its condition clears — at which point it closes
 * itself. Closing is the decision «this is settled» (تعیین تکلیف) and needs a reason in words,
 * because a closed alarm whose parameter is still out of range is a statement somebody may later
 * be asked to defend.
 */
@Service
@RequiredArgsConstructor
public class AlarmService {

    private final AlarmRepository alarmRepository;
    private final AlarmEventRepository eventRepository;
    private final OperationalUnitScopeService unitScopeService;
    private final AlarmLifecycle lifecycle;

    /**
     * The dashboard's alarm section: three counts, scoped exactly as the queue is, so a number
     * on the dashboard and the length of the list it links to cannot disagree.
     */
    @Transactional(readOnly = true)
    public com.hnp.backendofflinefirst.dto.DashboardAlarmPanel dashboardPanel() {
        Collection<Long> unitIds = visibleUnitIdsOrNull();
        if (unitIds != null && unitIds.isEmpty()) {
            return new com.hnp.backendofflinefirst.dto.DashboardAlarmPanel(0, 0, 0);
        }
        return new com.hnp.backendofflinefirst.dto.DashboardAlarmPanel(
                alarmRepository.countInScope(unitIds, AlarmState.OPEN),
                alarmRepository.countInScope(unitIds, AlarmState.ACKNOWLEDGED),
                alarmRepository.countLiveInScopeWithSeverity(unitIds, FieldValidationSeverity.DANGER));
    }

    /** {@code null} = unrestricted; empty = nothing; otherwise the unit ids in scope. */
    public Collection<Long> visibleUnitIdsOrNull() {
        if (!SecurityUtils.isUnitScopedOnly()) {
            return null;
        }
        return unitScopeService.getAccessibleUnitIds(SecurityUtils.currentUserId());
    }

    @Transactional(readOnly = true)
    public Page<Alarm> findVisible(Collection<AlarmState> states, FieldValidationSeverity severity,
                                   Pageable pageable) {
        Collection<Long> unitIds = visibleUnitIdsOrNull();
        if (unitIds != null && unitIds.isEmpty()) {
            // An empty IN () is not portable; a reader with no units sees nothing.
            return Page.empty(pageable);
        }
        return alarmRepository.search(unitIds, states == null || states.isEmpty() ? null : states,
                severity, pageable);
    }

    @Transactional(readOnly = true)
    public long countVisible(AlarmState state) {
        Collection<Long> unitIds = visibleUnitIdsOrNull();
        if (unitIds != null && unitIds.isEmpty()) {
            return 0L;
        }
        return alarmRepository.countInScope(unitIds, state);
    }

    @Transactional(readOnly = true)
    public Alarm requireVisible(Long id) {
        Alarm alarm = alarmRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("آلارم پیدا نشد."));
        Collection<Long> unitIds = visibleUnitIdsOrNull();
        if (unitIds != null && (alarm.getOperationalUnitId() == null
                || !unitIds.contains(alarm.getOperationalUnitId()))) {
            // Deliberately the same answer as a missing id for a scoped reader would give if it
            // were a 404 — but the panel's convention is a refused action, and this is one.
            throw new AccessDeniedException("این آلارم در محدوده واحدهای شما نیست.");
        }
        return alarm;
    }

    @Transactional(readOnly = true)
    public List<AlarmEvent> timeline(Long alarmId) {
        return eventRepository.findByAlarmIdOrderByOccurredAtAscIdAsc(alarmId);
    }

    /**
     * «I have seen this.» Only an unacknowledged alarm can be acknowledged. One that has already
     * returned to normal is closed by the acknowledgement — there is nothing left to watch.
     */
    @Transactional
    public Alarm acknowledge(Long id, Long actorUserId, String comment) {
        Alarm alarm = requireVisible(id);
        if (alarm.getState() != AlarmState.OPEN) {
            throw new IllegalStateException(alarm.getState() == AlarmState.ACKNOWLEDGED
                    ? "این آلارم قبلاً دیده و تأیید شده است."
                    : "این آلارم بسته شده است.");
        }
        long now = System.currentTimeMillis();
        alarm.setState(AlarmState.ACKNOWLEDGED);
        alarm.setAcknowledgedByUserId(actorUserId);
        alarm.setAcknowledgedAt(now);
        alarm.setAckComment(AlarmLifecycle.trim(comment));
        alarm.setUpdatedAt(now);
        lifecycle.record(alarm, AlarmEventType.ACKNOWLEDGED, actorUserId, null, null, null, comment, now);
        if (alarm.getReturnedToNormalAt() != null) {
            lifecycle.close(alarm, AlarmCloseReason.RETURNED_TO_NORMAL, actorUserId, null, now);
        }
        return alarmRepository.save(alarm);
    }

    /** «This is settled.» Needs a reason in words. */
    @Transactional
    public Alarm close(Long id, Long actorUserId, String comment) {
        String reason = AlarmLifecycle.trim(comment);
        if (reason == null) {
            throw new IllegalArgumentException("برای بستن آلارم، تصمیم گرفته‌شده را بنویسید.");
        }
        Alarm alarm = requireVisible(id);
        if (!alarm.isLive()) {
            throw new IllegalStateException("این آلارم بسته شده است.");
        }
        long now = System.currentTimeMillis();
        if (alarm.getState() == AlarmState.OPEN) {
            // Closing implies having seen it; the timeline should say so rather than show a
            // decision with no acknowledgement before it.
            alarm.setAcknowledgedByUserId(actorUserId);
            alarm.setAcknowledgedAt(now);
        }
        lifecycle.close(alarm, AlarmCloseReason.RESOLVED, actorUserId, reason, now);
        return alarmRepository.save(alarm);
    }
}
