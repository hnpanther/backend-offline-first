package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.DashboardPeriod;
import com.hnp.backendofflinefirst.dto.DashboardRows.PeriodSummary;
import com.hnp.backendofflinefirst.dto.DashboardRows.UnitPeriodRow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the dashboard itself without starting the application or touching PostgreSQL.
 *
 * <p><b>This is why the template reads plain model attributes and never {@code @someBean.…}.</b>
 * A bean reference needs a bean factory, and a bare Thymeleaf engine has none — the expression
 * fails while the template is still being parsed, so the whole page 500s rather than one value
 * rendering empty. The dates are therefore formatted in {@code DashboardController} and arrive
 * here as strings. Keeping that property is what makes this test possible at all, and it costs
 * seconds instead of the half-minute a Testcontainers boot costs.
 */
class DashboardTemplateTest {

    @Test
    void theDashboardRendersItsKpiCardsItsUnitRowsAndOnlyLocalNavigation() {
        UnitPeriodRow unit = new UnitPeriodRow(
                7L, "OP-UNIT07 — واحد هفت",
                /* generated */ 40, /* completed */ 30, /* approved */ 18,
                /* open */ 6, /* overdueOpen */ 2,
                /* expired */ 3, /* cancelled */ 1, /* voided */ 0,
                /* warning */ 9, /* danger */ 4);
        List<UnitPeriodRow> rows = List.of(unit);

        PeriodSummary summary = new PeriodSummary(
                DashboardPeriod.WEEK, 1_000L, 2_000L, 1_999L,
                rows, UnitPeriodRow.totalOf(rows),
                /* unitsInScope */ 5, /* unitsWithRounds */ 1, /* unassignedRounds */ false);

        String html = render(summary, "WEEK");

        assertThat(html)
                // The KPI cards read the totals line, so a card and the table's last row cannot
                // disagree; 40 appears in both.
                .contains("dashboard-metric-value\">40</div>")
                .contains("dashboard-metric-value\">4</div>")
                .contains(">سرویس: فعال</strong>")
                // The row itself, and the two columns that are derived rather than queried.
                .contains("OP-UNIT07 — واحد هفت")
                // The window's own bounds, formatted by the controller and printed as given.
                .contains("۱۴۰۴/۰۸/۱۹ ۰۰:۰۰")
                .contains("۱۴۰۴/۰۸/۲۵ ۲۳:۵۹")
                // 30 completed − 18 approved = 12 awaiting review.
                .contains(">12<")
                // 4 of the viewer's 5 units raised nothing, and the page says so.
                .contains("4 واحد از 5 واحد")
                .contains("جمع کل");

        assertThat(html)
                .as("the panel is offline-first: nothing on this page may reach the internet")
                .doesNotContain("http://", "https://");

        assertThat(html)
                .as("the period selector must show the window that is actually in force")
                .contains("value=\"WEEK\" selected=\"selected\"");
    }

    @Test
    void anEmptyWindowRendersTheEmptyRowRatherThanAnEmptyTable() {
        // The state a supervisor of a quiet unit sees on a Friday, and the one a scoped user
        // with no units sees always. It must be a sentence, not a blank page.
        PeriodSummary empty = PeriodSummary.noAccess(
                DashboardPeriod.DAY, DashboardPeriod.DAY.window(
                        com.hnp.backendofflinefirst.util.DateUtils.TEHRAN, 1_700_000_000_000L));

        String html = render(empty, "DAY");

        assertThat(html)
                .contains("در این بازه هیچ لاگ‌شیتی")
                .contains("colspan=\"13\"")
                .as("no totals line under an empty table — there is nothing to total")
                .doesNotContain("جمع کل");
    }

    /** Thirteen headers, which is also what the column picker and the empty row depend on. */
    @Test
    void theEmptyRowSpansEveryColumn() {
        String html = render(PeriodSummary.noAccess(
                DashboardPeriod.WEEK, DashboardPeriod.WEEK.window(
                        com.hnp.backendofflinefirst.util.DateUtils.TEHRAN, 1_700_000_000_000L)),
                "WEEK");

        long headers = html.lines().filter(line -> line.contains("<th ") || line.contains("<th>"))
                .count();
        assertThat(headers)
                .as("the colspan on the empty row must match the header count or the table "
                        + "misaligns exactly when somebody is looking at it")
                .isEqualTo(13);
    }

    private static String render(PeriodSummary summary, String period) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        WebContext context = new WebContext(application.buildExchange(request, response));
        context.setVariable("dashboard", summary);
        context.setVariable("period", period);
        context.setVariable("rangeFrom", "۱۴۰۴/۰۸/۱۹ ۰۰:۰۰");
        context.setVariable("rangeTo", "۱۴۰۴/۰۸/۲۵ ۲۳:۵۹");
        context.setVariable("rangeFromInput", "2025-11-10T00:00");
        context.setVariable("rangeToInput", "2025-11-17T00:00");

        return engine.process("index", Set.of("#pageContent"), context);
    }
}
