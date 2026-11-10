package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.AlarmRuleField;
import com.hnp.backendofflinefirst.entity.AlarmRuleFieldId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlarmRuleFieldRepository extends JpaRepository<AlarmRuleField, AlarmRuleFieldId> {

    /** Every link, in a stable order — rules are tens and their lists short, so one read serves all. */
    List<AlarmRuleField> findAllByOrderByAlarmRuleIdAscFieldDefinitionIdAsc();

    List<AlarmRuleField> findByAlarmRuleId(Long alarmRuleId);
}
