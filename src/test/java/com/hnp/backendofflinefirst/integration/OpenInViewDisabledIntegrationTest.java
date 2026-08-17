package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import jakarta.persistence.Basic;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Running with {@code spring.jpa.open-in-view=false}.
 *
 * <p>Open Session In View keeps a database connection attached to the request for its whole
 * life, rendering included. Turning it off releases the connection when the service layer
 * finishes — which on the report pages is well before the slowest part of the request — and it
 * is what stops a view from issuing queries nobody wrote.
 *
 * <p>It is only safe because of a specific property of this codebase: **no entity declares a JPA
 * association**. Placement and ownership are plain id columns (`sub_function_id`, `class_id`) and
 * labels are resolved through explicit batch lookups, so there is nothing lazy for a template to
 * touch. That property is an assumption, and assumptions rot — so it is asserted here rather than
 * left as a comment. The day somebody adds an `@ManyToOne`, this test fails and names the
 * decision they need to revisit, instead of a page failing in production with
 * `LazyInitializationException`.
 *
 * <p>The second half renders the panel end to end with the setting off, which is the conclusion
 * rather than the premise.
 */
class OpenInViewDisabledIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;

    @Value("${spring.jpa.open-in-view}")
    boolean openInView;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void theSettingIsActuallyOff() {
        // Everything below only means something while this holds.
        assertThat(openInView).isFalse();
    }

    @Test
    void noEntityDeclaresAJpaAssociationOrALazyField() {
        List<Class<? extends Annotation>> lazyCapable =
                List.of(OneToMany.class, ManyToOne.class, OneToOne.class,
                        ManyToMany.class, ElementCollection.class, Basic.class);

        List<String> offenders = new ArrayList<>();
        for (Class<?> entity : entityClasses()) {
            for (Field field : entity.getDeclaredFields()) {
                for (Class<? extends Annotation> annotation : lazyCapable) {
                    if (field.isAnnotationPresent(annotation)) {
                        offenders.add(entity.getSimpleName() + "." + field.getName()
                                + " @" + annotation.getSimpleName());
                    }
                }
            }
        }

        // Cannot pass by scanning nothing.
        assertThat(entityClasses()).as("entities found by the scan").hasSizeGreaterThan(10);
        assertThat(offenders)
                .as("associations that could be lazy — revisit spring.jpa.open-in-view before adding one")
                .isEmpty();
    }

    /**
     * Every panel list renders with no session open during view rendering.
     *
     * <p>A lazy access during rendering fails here as a 500, which is exactly what would reach an
     * operator. Broad on purpose: the value is in covering every page at once, not in what any
     * single one contains.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/asset-entries", "/asset-classes", "/locations", "/plant-systems",
            "/main-functions", "/sub-functions", "/operational-units", "/users", "/roles",
            "/log-sheets", "/log-sheet-templates", "/nfc-fault-reports", "/asset-status-requests",
            "/audit-logs", "/api-sessions", "/web-sessions", "/login-attempts", "/settings",
            "/reports", "/reports/overview", "/reports/compliance", "/reports/exceptions",
            "/reports/data-quality", "/reports/workforce", "/reports/actions",
            "/reports/asset-parameters", "/reports/asset-history"
    })
    @WithAppUser(username = "oiv-admin", roles = "ADMIN", authorities = {
            "GET:/", "GET:/asset-entries", "GET:/asset-classes", "GET:/locations",
            "GET:/plant-systems", "GET:/main-functions", "GET:/sub-functions",
            "GET:/operational-units", "GET:/users", "GET:/roles", "GET:/log-sheets",
            "GET:/log-sheet-templates", "GET:/nfc-fault-reports", "GET:/asset-status-requests",
            "GET:/audit-logs", "GET:/api-sessions", "GET:/web-sessions", "GET:/login-attempts",
            "GET:/settings", "GET:/reports"
    })
    void everyPanelPageRendersWithoutASessionOpenDuringRendering(String path) throws Exception {
        var result = mockMvc.perform(get(path)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("GET " + path + " -> redirect: " + result.getResponse().getRedirectedUrl())
                .isEqualTo(200);
    }

    private List<Class<?>> entityClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<Class<?>> found = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.hnp.backendofflinefirst.entity")) {
            try {
                found.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return found;
    }
}
