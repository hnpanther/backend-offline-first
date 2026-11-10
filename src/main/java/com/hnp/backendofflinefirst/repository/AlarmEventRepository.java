package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.AlarmEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlarmEventRepository extends JpaRepository<AlarmEvent, Long> {

    List<AlarmEvent> findByAlarmIdOrderByOccurredAtAscIdAsc(Long alarmId);
}
