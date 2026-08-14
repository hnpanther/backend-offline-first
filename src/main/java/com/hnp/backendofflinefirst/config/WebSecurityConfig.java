package com.hnp.backendofflinefirst.config;

import com.hnp.backendofflinefirst.security.ApiAccessDeniedHandler;
import com.hnp.backendofflinefirst.security.ApiAuthenticationEntryPoint;
import com.hnp.backendofflinefirst.security.AppAuthenticationProvider;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.JwtAuthenticationFilter;
import com.hnp.backendofflinefirst.security.PermissionCodes;
import com.hnp.backendofflinefirst.security.WebAccessDeniedHandler;
import com.hnp.backendofflinefirst.security.WebSessionMetadataStore;
import com.hnp.backendofflinefirst.logging.UserMdcFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class WebSecurityConfig {

    private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;
    private final ApiAccessDeniedHandler apiAccessDeniedHandler;
    private final WebAccessDeniedHandler webAccessDeniedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public AuthenticationManager authenticationManager(AppAuthenticationProvider authenticationProvider) {
        return new ProviderManager(authenticationProvider);
    }

    /** Stateless JWT auth for mobile/tablet API clients. */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                // Uses the CorsConfigurationSource bean from CorsConfig. Enabling CORS on the
                // chain (rather than only via MVC) is what puts the headers on 401/403 responses
                // too — without it the browser blocks them and the client cannot tell an expired
                // session apart from an unreachable server. See CorsConfig's javadoc.
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .requestMatchers("/api/health", "/api/auth/login").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Straight after the JWT filter has populated the context, so a rejection
                // handled further down the chain still logs who was rejected.
                .addFilterAfter(new UserMdcFilter(), JwtAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(apiAccessDeniedHandler));

        return http.build();
    }

    /** Session + form login for admin web panel. */
    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http, SessionRegistry sessionRegistry,
                                                      AuthenticationSuccessHandler loginSuccessHandler,
                                                      AuthenticationManager authenticationManager) throws Exception {
        http
                // Explicit manager (same single-provider, no-parent instance the API chain uses)
                // instead of .authenticationProvider(...) — the latter lets HttpSecurity's builder
                // silently attach the auto-configured *global* AuthenticationManager as a parent,
                // which also resolves to this same provider bean. On a failed login that means
                // additionalAuthenticationChecks() (and LoginAttemptService.recordFailure) runs
                // twice: once against the local provider, once again via parent fallback.
                .authenticationManager(authenticationManager)
                // Immediately after the security context is restored, and therefore *around*
                // CsrfFilter and AuthorizationFilter. Those two produce the denials worth
                // logging, and both are handled inside this chain — an ordinary servlet filter
                // ordered after the chain never sees them, which is why "Access denied" used
                // to name `anonymous` for a user who was plainly logged in.
                .addFilterAfter(new UserMdcFilter(), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // favicon.png must be public or the browser requests it on the login
                        // page, gets redirected to /login, and shows no icon at all.
                        .requestMatchers("/login", "/favicon.png", "/css/**", "/js/**", "/fonts/**", "/vendor/**", "/webjars/**").permitAll()
                        // Public probes for load balancers / watchdogs — status only, no component detail.
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        // Everything else under /actuator/** (full health, metrics) is admin-only.
                        .requestMatchers("/actuator/**").hasAuthority(PermissionCodes.GET_ACTUATOR)
                        // Swagger UI / OpenAPI spec — enabled in every environment, admin-only.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                        .hasAuthority(PermissionCodes.GET_API_DOCS)
                        .anyRequest().authenticated())
                // One web session per user: a new login expires the previous one (same
                // "supersede" semantics as the mobile api_sessions registry). Registry is
                // in-memory, matching the in-memory (non-persistent) session store.
                .sessionManagement(session -> session
                        .sessionConcurrency(concurrency -> concurrency
                                .maximumSessions(1)
                                .sessionRegistry(sessionRegistry)
                                .expiredUrl("/login?expired")))
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(loginSuccessHandler)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(webAccessDeniedHandler));

        return http.build();
    }

    @Bean
    public AuthenticationSuccessHandler loginSuccessHandler(WebSessionMetadataStore webSessionMetadataStore) {
        return (request, response, authentication) -> {
            webSessionMetadataStore.recordLogin(request);
            if (authentication.getPrincipal() instanceof AppUserDetails user
                    && user.isUnitScopedOnly()) {
                response.sendRedirect("/my-inbox");
            } else {
                response.sendRedirect("/");
            }
        };
    }

    /** Tracks live web sessions so concurrency control and the admin page can see them. */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Publishes session created/destroyed events — required for {@link SessionRegistryImpl}. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}
