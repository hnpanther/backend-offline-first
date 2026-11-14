package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.DashboardPeriod;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.dto.DashboardRows.PeriodSummary;
import com.hnp.backendofflinefirst.service.DashboardService;
import com.hnp.backendofflinefirst.util.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The panel's landing page: a periodic management dashboard, broken down by operational unit.
 *
 * <p>It used to count inventory — locations, asset classes, assets, sub-functions, log sheets,
 * users, units — with seven plant-wide {@code count(*)} calls and no scope anywhere, which made
 * it an administrator's page by construction. {@code DashboardService} explains what replaced it
 * and why. Two of those counts — classes and units — are back, beside active log-sheet templates, as one line in the header card, and only for a viewer
 * whose scope is the whole plant ({@code DashboardService.plantStructureOrNull}).
 *
 * <p>Access is decided by the authority {@code GET:/} alone, as it always was. V16 grants that
 * authority to whoever already holds {@code GET:/reports}, which is how supervisors reach this
 * page — a migration rather than a code change, so an administrator can take it away again from
 * the Roles page. Nothing here asks about a role's code or a scope; <em>how much</em> the viewer
 * sees is the service's business and comes from {@code visibleUnitIds()}.
 */
@Controller
@RequestMapping("/")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final DateUtils dateUtils;
    private final com.hnp.backendofflinefirst.service.alarm.AlarmService alarmService;

    /**
     * @param period {@code DAY} / {@code WEEK} / {@code MONTH}; absent means the default.
     *               Anything else is <b>refused</b> rather than coerced — the rule
     *               {@code LogSheetDateField} and {@code IntegrationLogSheetQuery} both record,
     *               because answering 200 with a window nobody asked for is how somebody reads
     *               the wrong week and acts on it. {@code WebExceptionHandler} turns the refusal
     *               into a Persian flash message and a redirect back to the dashboard with no
     *               parameter, which is a page that works rather than a dead end.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('GET:/')")
    public String dashboard(@RequestParam(required = false) String period, Model model) {
        DashboardPeriod resolved = DashboardPeriod.fromNullableOrDefault(period);
        if (resolved == null) {
            throw new IllegalArgumentException("Unknown dashboard period: " + period);
        }

        PeriodSummary summary = dashboardService.summarise(
                resolved, DateUtils.TEHRAN, System.currentTimeMillis());

        model.addAttribute("activePage", "dashboard");
        model.addAttribute("period", resolved.name());
        model.addAttribute("dashboard", summary);

        // Formatted here rather than by `@dateUtils.…` in the template, for two reasons. The
        // zone is already this controller's decision — `DateUtils.TEHRAN` three lines up — and
        // splitting it across two layers is how a page ends up disagreeing with the window it
        // describes. And a bean reference in the markup needs a bean factory to render, which is
        // what `DashboardTemplateTest` deliberately does without: it renders this page with a
        // bare Thymeleaf engine, no Spring and no PostgreSQL, and that only stays possible while
        // the template reads plain model attributes.
        model.addAttribute("rangeFrom", dateUtils.format(summary.fromAt()));
        // The LAST INCLUDED instant, not the exclusive bound. Printing `toAt` would name a date
        // on which nothing in the window was raised — the window ends at that midnight.
        model.addAttribute("rangeTo", dateUtils.format(summary.lastIncludedAt()));
        // The drill-down to /log-sheets, in the Persian picker's own hidden-field format, so the
        // list opens on exactly this window. Both bounds go across as they are: the list applies
        // the same half-open rule to the same named column.
        model.addAttribute("rangeFromInput", dateUtils.formatInputHidden(summary.fromAt()));
        model.addAttribute("rangeToInput", dateUtils.formatInputHidden(summary.toAt()));
        // Each unit row links to the list filtered to that unit and this window (roadmap §26),
        // and its «عقب‌افتاده» figure to the same plus `overdue=true`. A plain boolean rather
        // than `sec:authorize` on every row, so the standalone render test can exercise both.
        model.addAttribute("canOpenLogSheets", SecurityUtils.hasPermission("GET:/log-sheets"));
        // The alarms this viewer has not decided on — the first thing a supervisor should see
        // after logging in. Counts only, each a link into the queue filtered to what it counts.
        // Only for whoever may open that queue; for everyone else the attribute is absent and the
        // section does not render. Not period-bound: an alarm raised last month and never looked
        // at is exactly the one this exists for.
        if (SecurityUtils.hasPermission("GET:/alarms")) {
            model.addAttribute("alarmPanel", alarmService.dashboardPanel());
        }
        // The plant at a glance, in the header card — for a plant-wide viewer only; absent (and
        // the header shows its two plain badges instead) for a unit-scoped one.
        model.addAttribute("plantStructure", dashboardService.plantStructureOrNull(summary));
        return "index";
    }
}
