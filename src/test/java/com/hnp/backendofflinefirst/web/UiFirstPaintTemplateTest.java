package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.util.DateUtils;
import com.hnp.backendofflinefirst.util.LogSheetViewHelper;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual composed templates, no application startup, schedulers, database or internet. */
class UiFirstPaintTemplateTest {
    private static final LogSheetViewHelper VIEW = new LogSheetViewHelper();

    @Test
    void everyLogSheetStatusHasItsFinalPresentationBeforeScripts() throws IOException {
        List<LogSheet> sheets = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            LogSheet sheet = new LogSheet();
            sheet.setId((long) i + 1);
            sheet.setTemplateName("بازدید تجهیزات واحد عملیاتی — " + (i + 1));
            sheet.setOperatorName("اپراتور آزمایشی");
            sheet.setStatus(LogSheetStatus.values()[i % LogSheetStatus.values().length]);
            sheets.add(sheet);
        }
        String html = render(sheets);
        for (LogSheetStatus status : LogSheetStatus.values()) {
            assertThat(html).contains("class=\"badge status-badge " + VIEW.statusBadge(status) + "\"");
            assertThat(html).contains(VIEW.statusLabel(status));
        }
        assertThat(html).contains("enterprise-page-actions", "enterprise-filter-bar",
                "enterprise-primary-cell", "enterprise-technical-cell", "enterprise-operation-column",
                "enterprise-data-card", "data-enterprise-table-key=\"0\"", "data-enterprise-tools-slot");
        assertThat(html.indexOf("/js/ui-preferences.js")).isLessThan(html.indexOf("<body"));
        assertThat(html).contains("/fonts/vazirmatn/Vazirmatn-Medium.woff2");
        assertThat(html).doesNotContain("data-enterprise-badge", "data-state=", "cdn.", "fonts.googleapis");
        // Optional, real-template fixtures for visual QA. Never reconstruct markup by hand.
        if (Boolean.getBoolean("ui.preview")) writePreview("log-sheets", html);
    }

    @Test
    void emptyListAlreadyHasItsFinalEmptyState() throws IOException {
        String html = render(List.of());
        assertThat(html).contains("enterprise-empty-row", "enterprise-empty-cell", "هیچ لاگ شیتی یافت نشد");
        if (Boolean.getBoolean("ui.preview")) writePreview("log-sheets-empty", html);
    }

    /**
     * Every badge class the server can emit is one the stylesheet actually dresses.
     *
     * <p>Status colour used to be decided in the browser by matching the *Persian label* against a
     * regular expression, which is why this matters. That coupled the look of a status to its
     * translation: renaming a label, or adding a status whose wording did not match any pattern,
     * silently produced an undecorated badge. The mapping now runs one way — status →
     * {@code bg-*} class in {@link LogSheetViewHelper}, class → colour in the stylesheet — and the
     * gap this guards is a status added to the enum with no rule at the end of that chain.
     *
     * <p>Asserted against the enum rather than against a list written here, so a new status is
     * covered the moment it exists.
     */
    @Test
    void everyStatusBadgeClassTheServerEmitsIsStyled() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/static/css/enterprise.css"));
        assertThat(css).contains(".badge.status-badge");

        for (LogSheetStatus status : LogSheetStatus.values()) {
            for (String token : VIEW.statusBadge(status).split("\\s+")) {
                if (!token.startsWith("bg-")) {
                    continue;                       // text-dark and friends are Bootstrap's own
                }
                assertThat(Pattern.compile("\\.badge\\.status-badge[^{]*\\." + Pattern.quote(token) + "\\b")
                        .matcher(css).find())
                        .as("%s renders %s, which no .badge.status-badge rule styles", status, token)
                        .isTrue();
            }
        }
    }

    private String render(List<LogSheet> sheets) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        MockServletContext servlet = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servlet);
        request.setRequestURI("/log-sheets");
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(request, new MockHttpServletResponse());
        WebContext context = new WebContext(exchange);
        try (StaticApplicationContext beans = new StaticApplicationContext()) {
            beans.getBeanFactory().registerSingleton("logSheetView", VIEW);
            beans.getBeanFactory().registerSingleton("dateUtils", new DateUtils());
            context.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                    new ThymeleafEvaluationContext(beans, null));
            context.setVariable("logSheets", sheets);
            context.setVariable("templates", List.of());
            context.setVariable("customUnits", List.of());
            context.setVariable("scopeLabels", Map.of());
            return engine.process("log-sheets", context);
        }
    }

    private void writePreview(String name, String html) throws IOException {
        Path target = Path.of("target/ui-preview");
        Files.createDirectories(target);
        Files.writeString(target.resolve(name + ".html"), html);
        // Only the tiny head preference reader remains: this represents the pre-enhancement frame.
        String initial = html.replaceAll("(?s)<script(?![^>]*ui-preferences\\.js)[^>]*>.*?</script>", "");
        Files.writeString(target.resolve(name + "-initial.html"), initial);
        Path staticRoot = Path.of("src/main/resources/static");
        try (var files = Files.walk(staticRoot)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path destination = target.resolve(staticRoot.relativize(source));
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (String asset : List.of("bootstrap/5.3.3/css/bootstrap.rtl.min.css",
                "bootstrap/5.3.3/js/bootstrap.bundle.min.js", "bootstrap-icons/1.11.3/font/bootstrap-icons.min.css",
                "bootstrap-icons/1.11.3/font/fonts/bootstrap-icons.woff2")) {
            Path destination = target.resolve("webjars/" + asset);
            Files.createDirectories(destination.getParent());
            try (var input = getClass().getClassLoader().getResourceAsStream("META-INF/resources/webjars/" + asset)) {
                assertThat(input).as(asset).isNotNull();
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
