package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.AlarmWebhookEndpointRule;
import com.hnp.backendofflinefirst.entity.AlarmWebhookEndpointRuleId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AlarmWebhookEndpointRuleRepository
        extends JpaRepository<AlarmWebhookEndpointRule, AlarmWebhookEndpointRuleId> {

    List<AlarmWebhookEndpointRule> findByEndpointId(Long endpointId);

    /** Every listed receiver's rules in one statement — for an event's fan-out and for the page. */
    List<AlarmWebhookEndpointRule> findByEndpointIdIn(Collection<Long> endpointIds);

    /** The rule delete guard. */
    List<AlarmWebhookEndpointRule> findByAlarmRuleId(Long alarmRuleId);
}
