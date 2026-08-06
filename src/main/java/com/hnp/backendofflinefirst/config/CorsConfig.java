package com.hnp.backendofflinefirst.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS for the mobile REST API ({@code /api/**}). Allowed origins come from
 * {@code app.cors.allowed-origins} (comma-separated) — default {@code *}. Restrict it
 * to the real frontend origin(s) before production.
 * <p>
 * This is exposed as a {@link CorsConfigurationSource} bean and wired into the API
 * security chain via {@code http.cors(...)}, <em>not</em> as
 * {@code WebMvcConfigurer.addCorsMappings} as it used to be. The distinction matters:
 * {@code addCorsMappings} is applied by Spring MVC, so it never runs for a response the
 * security filter chain writes on its own — a 401 from the authentication entry point
 * went back with no {@code Access-Control-Allow-Origin} header at all. The browser then
 * blocked that response and the PWA saw an opaque network failure instead of a readable
 * 401, so an expired or revoked session surfaced as "could not reach the server" rather
 * than "your session ended — sign in again". Registering the source as a bean puts
 * Spring Security's own {@code CorsFilter} at the front of the chain, where it decorates
 * error responses too.
 * <p>
 * This only bites when the PWA and the API are on different origins, i.e. local
 * development. In production nginx serves both from one origin and CORS never applies.
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(resolveAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Credentials stay off: the API authenticates with a bearer token in the
        // Authorization header, never a cookie — which is also what keeps the
        // wildcard origin default legal.
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private List<String> resolveAllowedOrigins() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }
}
