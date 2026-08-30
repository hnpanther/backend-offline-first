package com.hnp.backendofflinefirst.web;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the shared list pager without starting Spring or PostgreSQL.
 *
 * <p>This pins the template expressions as well as the visual navigation structure. A CSS-only
 * check cannot catch a malformed Thymeleaf expression, while the full page integration tests
 * need Docker solely because their controllers query PostgreSQL.
 */
class ListToolbarTemplateTest {

    @Test
    void pagerRendersTheVisibleRangeAccessibleCurrentPageAndFilterPreservingLinks() {
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

        context.setVariable("basePath", "/users");
        context.setVariable("pageSize", 10);
        context.setVariable("filterQuery", "&q=pump");
        context.setVariable("listPage",
                new PageImpl<>(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), PageRequest.of(4, 10), 120));

        String html = engine.process("fragments/list-toolbar", Set.of("pagination"), context);

        assertThat(html)
                .contains("نمایش")
                .contains(">41</strong>")
                .contains(">50</strong>")
                .contains("aria-current=\"page\"")
                .contains("enterprise-pagination-ellipsis")
                .contains("/users?page=5&amp;size=10&amp;q=pump");

        // Where each gap sits, not merely that one exists. Both markers used to be emitted
        // outside the page loop, which put them on the wrong side of the boundary numbers: the
        // pager read «… ۱ ۳ ۴ ۵ ۶ ۷ ۱۲ …», claiming pages were missing before the first and after
        // the last. Asserting only `contains("…")` passed throughout.
        assertThat(pagerSequence(html))
                .containsExactly("»", "قبلی", "1", "…", "3", "4", "5", "6", "7", "…", "12", "بعدی", "»");
    }

    @Test
    void aPagerShortEnoughToListEveryPageShowsNoGapAtAll() {
        // Five pages, sitting on the third: 0..4 all fall inside number±2, so neither gap
        // condition may fire. A marker here would claim hidden pages that do not exist.
        String html = renderPager(new PageImpl<>(List.of(1, 2, 3, 4, 5), PageRequest.of(2, 5), 25));

        assertThat(pagerSequence(html))
                .containsExactly("»", "قبلی", "1", "2", "3", "4", "5", "بعدی", "»");
    }

    @Test
    void aPageRequestedBeyondTheEndDoesNotStateAnImpossibleRange() {
        // Nothing clamps `page`: /locations?page=999 is served as an empty page whose totalPages
        // still spans the data, so the pager renders. Reading the range off number * size gave
        // «نمایش ۷۹۹۳ تا ۷۹۹۲ از ۱۸۱ مورد» — a start past both the end and the total.
        String html = renderPager(new PageImpl<>(List.of(), PageRequest.of(999, 10), 120));

        assertThat(html)
                .doesNotContain(">9991</strong>")
                .contains("این صفحه موردی ندارد")
                .contains(">120</strong>");
    }

    /** The pager's items in document order; an ellipsis reads as «…», an icon-only control as «»». */
    private static List<String> pagerSequence(String html) {
        Matcher list = Pattern.compile("<ul class=\"pagination[^\"]*\"[^>]*>(.*?)</ul>", Pattern.DOTALL)
                .matcher(html);
        assertThat(list.find()).as("the pager should have rendered").isTrue();

        List<String> items = new ArrayList<>();
        Matcher item = Pattern.compile("<li[^>]*>(.*?)</li>", Pattern.DOTALL).matcher(list.group(1));
        while (item.find()) {
            if (item.group(1).contains("enterprise-pagination-ellipsis")) {
                items.add("…");
                continue;
            }
            String label = item.group(1).replaceAll("<[^>]+>", "").trim();
            items.add(label.isEmpty() ? "»" : label);
        }
        return items;
    }

    private static String renderPager(PageImpl<Integer> page) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        MockServletContext servletContext = new MockServletContext();
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        WebContext context = new WebContext(application.buildExchange(
                new MockHttpServletRequest(servletContext), new MockHttpServletResponse()));
        context.setVariable("basePath", "/users");
        context.setVariable("pageSize", page.getSize());
        context.setVariable("listPage", page);
        return engine.process("fragments/list-toolbar", Set.of("pagination"), context);
    }
}
