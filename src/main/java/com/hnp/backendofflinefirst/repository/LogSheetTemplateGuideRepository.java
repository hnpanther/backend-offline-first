package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheetTemplateGuide;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Guide documents for log-sheet templates.
 *
 * <p>Groundwork: nothing calls these yet. The two finder shapes are here because they are the
 * two the future work will need — one template's guides for a sheet detail screen, and several
 * templates' guides at once for a mobile bundle, which must not become a query per sheet.
 */
public interface LogSheetTemplateGuideRepository extends JpaRepository<LogSheetTemplateGuide, Long> {

    List<LogSheetTemplateGuide> findByTemplateIdOrderBySortOrderAscIdAsc(Long templateId);

    /** Only what an operator should see — superseded revisions stay in the table but hidden. */
    List<LogSheetTemplateGuide> findByTemplateIdAndActiveTrueOrderBySortOrderAscIdAsc(Long templateId);

    List<LogSheetTemplateGuide> findByTemplateIdInAndActiveTrueOrderBySortOrderAscIdAsc(
            Collection<Long> templateIds);
}
