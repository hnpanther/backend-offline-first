package com.hnp.backendofflinefirst.config;

import com.hnp.backendofflinefirst.security.ApiAccessDeniedHandler;
import com.hnp.backendofflinefirst.security.ApiAuthenticationEntryPoint;
import com.hnp.backendofflinefirst.security.AppAuthenticationProvider;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.security.IntegrationApiKeyFilter;
import com.hnp.backendofflinefirst.security.IntegrationAuthenticationEntryPoint;
import com.hnp.backendofflinefirst.security.JwtAuthenticationFilter;
import com.hnp.backendofflinefirst.security.PermissionCodes;
import com.hnp.backendofflinefirst.security.WebAccessDeniedHandler;
import com.hnp.backendofflinefirst.security.WebSessionMetadataStore;
import com.hnp.backendofflinefirst.logging.UserMdcFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
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
    private final IntegrationApiKeyFilter integrationApiKeyFilter;
    private final IntegrationAuthenticationEntryPoint integrationAuthenticationEntryPoint;

    @Bean
    public AuthenticationManager authenticationManager(AppAuthenticationProvider authenticationProvider) {
        return new ProviderManager(authenticationProvider);
    }

    /**
     * Stops Boot from registering {@link IntegrationApiKeyFilter} as a <b>global servlet
     * filter</b>, on top of its place in the chain below.
     *
     * <p><b>This is load-bearing, and leaving it out took the entire application down.</b> Boot
     * auto-registers every {@code Filter} bean against {@code /*}. For
     * {@link JwtAuthenticationFilter} that is harmless — it only ever tries to authenticate and
     * always calls {@code doFilter}, so a second pass changes nothing. The integration filter
     * <em>terminates</em> the request with 401 when there is no {@code X-API-Key} header, so
     * once auto-registered it answered that 401 to <b>every URL in the application</b>: the
     * login page, {@code /api/health}, the static assets. Nothing could be reached at all.
     *
     * <p>Not one of the 1,356 tests saw it. {@code MockMvcBuilders.webAppContextSetup(...)}
     * .{@code apply(springSecurity())} wires the Spring Security chain and nothing else, so the
     * auto-registered copy does not exist in a MockMvc test — the same blind spot that hid this
     * class of fault before. It took one live request to {@code /login} to find.
     *
     * <p>{@code UserMdcFilter} solves the same problem by not being a {@code @Component} at
     * all. That works for a filter with no dependencies; this one has three, so it stays a bean
     * and its auto-registration is disabled explicitly instead. <b>Any future filter that can
     * write a response needs one of the two.</b>
     */
    @Bean
    public FilterRegistrationBean<IntegrationApiKeyFilter> integrationApiKeyFilterRegistration(
            IntegrationApiKeyFilter filter) {
        FilterRegistrationBean<IntegrationApiKeyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Third-party integration API — an {@code X-API-Key} header and nothing else.
     *
     * <p><b>Ahead of the API chain on purpose.</b> {@code @Order(0)} means a request to
     * {@code /integration/**} can only ever be decided here: it cannot fall through to the JWT
     * chain, and a browser session cannot reach it. That is what "third-party endpoints must be
     * separate from normal user authentication APIs" means once it is written down as
     * configuration rather than as a naming convention.
     *
     * <p>Four things are deliberately absent, and each removal is a decision:
     * <ul>
     *   <li><b>No CORS.</b> This is a server-to-server API. Enabling CORS would invite somebody
     *       to put the key in browser JavaScript, where it is public.</li>
     *   <li><b>No session.</b> {@code STATELESS}, so no {@code JSESSIONID} is ever minted and a
     *       key cannot be traded for a cookie that outlives its revocation.</li>
     *   <li><b>No CSRF.</b> There is no ambient credential to ride on — that is the whole of
     *       what CSRF exploits — and every endpoint here is a GET.</li>
     *   <li><b>No form login and no entry point that redirects.</b> A machine client must get
     *       JSON with a status code, never a 302 to an HTML login page.</li>
     * </ul>
     *
     * <p>The filter authenticates and answers 401 itself, so the chain never reaches the
     * authorization stage for an unauthenticated caller. {@code authenticated()} below is
     * therefore a second lock on the same door, kept because a chain whose authorization rule
     * says "permitAll" is one refactor away from being exactly that.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain integrationSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/integration/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(RequestCacheConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(integrationApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(integrationAuthenticationEntryPoint)
                        .accessDeniedHandler(integrationAuthenticationEntryPoint));

        return http.build();
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
            // Asks which page this user may open, not how their data is scoped. The two are not
            // the same question: a plant-wide role without GET:/ used to be redirected to the
            // dashboard and refused by it, so a correct login ended on an access-denied message.
            // SecurityUtils.homePath() is the same rule the navbar brand and every breadcrumb
            // use, so where login puts someone is where "home" keeps taking them.
            response.sendRedirect(SecurityUtils.homePath());
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
