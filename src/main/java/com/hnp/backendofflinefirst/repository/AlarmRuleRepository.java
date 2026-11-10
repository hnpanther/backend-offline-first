package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.AlarmRuleScope;
import com.hnp.backendofflinefirst.entity.AlarmRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * The rules alone. A rule's parameter list lives in {@code alarm_rule_fields}; read rules through
 * {@code AlarmRuleService.all()}, which attaches it — a rule read straight from here has an empty
 * list, which for a specific rule is never true.
 */
public interface AlarmRuleRepository extends JpaRepository<AlarmRule, Long> {

    List<AlarmRule> findAllByOrderByIdAsc();

    /** The default rule, if one has been written. V18 allows at most one. */
    Optional<AlarmRule> findFirstByScope(AlarmRuleScope scope);
}
