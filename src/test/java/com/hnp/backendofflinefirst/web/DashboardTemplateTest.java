package com.hnp.backendofflinefirst.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Renders the dashboard itself without starting the application or touching PostgreSQL. */
class DashboardTemplateTest {

    @Test
    void dashboardRendersExistingCountsAndOnlyLocalNavigation() {
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
        context.setVariable("locationCount", 11L);
        context.setVariable("operationalUnitCount", 12L);
        context.setVariable("assetClassCount", 13L);
        context.setVariable("assetEntryCount", 14L);
        context.setVariable("subFunctionCount", 15L);
        context.setVariable("logSheetCount", 16L);
        context.setVariable("userCount", 17L);

        String html = engine.process("index", Set.of("#pageContent"), context);

        assertThat(html)
                .contains("dashboard-metric-value\">11</div>")
                .contains("dashboard-metric-value\">17</div>")
                .contains(">سرویس: فعال</strong>")
                .contains("href=\"/locations\"")
                .contains("href=\"/reports\"")
                .doesNotContain("رابط کاملاً محلی", "بدون وابستگی به اینترنت")
                .doesNotContain("http://", "https://");
    }
}
