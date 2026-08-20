package com.hnp.backendofflinefirst.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Says out loud, on every boot, which settings are still the ones that ship in the repository.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every value it checks is deliberately convenient for a developer's first run and wrong for a
 * plant. The README lists them, and a list in a README is not a control: it is read once, by
 * whoever set the system up, and never again — including by the person who copies that host's
 * configuration to a second site a year later.
 *
 * <p>The one that matters most is the JWT secret. It is published in this repository, so a
 * deployment still using it can have a valid token for any user forged by anyone who can read
 * GitHub. Nothing else in the system helps: the token verifies, the session registry accepts it,
 * every permission check passes.
 *
 * <h2>Why it warns rather than refuses</h2>
 *
 * <p>Refusing to start would be the stronger control and the wrong one here. This application is
 * run locally with exactly these defaults many times a day, and a boot that fails on them makes
 * the check something to switch off rather than something to satisfy. A plant that is already
 * running must also never be prevented from restarting by a configuration rule that was fine
 * yesterday.
 *
 * <p>So it is loud instead: a bordered block at WARN, naming each finding and what to set. It
 * appears in the log file the operations team already collects, and it appears every single boot
 * until the configuration is fixed — which is the property a README does not have.
 *
 * <p>What it does <b>not</b> do is check whether the bootstrap admin's password is still
 * {@code admin123}: that is a hashed value in the database, and reading it here to compare would
 * mean this class handling credentials. {@link AdminBootstrapRunner} already logs a warning at
 * the moment it creates that account.
 */
@Component
@Slf4j
public class ProductionReadinessRunner implements ApplicationRunner {

    /** The value in {@code application.properties}. Matching it exactly is the whole check. */
    static final String SHIPPED_JWT_SECRET = "dev-only-change-me-use-long-random-secret-key!!";

    /** HS256 needs 256 bits. {@code JwtService} refuses to start below this; this explains why. */
    static final int MIN_JWT_SECRET_BYTES = 32;

    private final String jwtSecret;
    private final String corsAllowedOrigins;
    private final boolean ldapEnabled;
    private final boolean ldapTrustSelfSigned;
    private final String datasourcePassword;

    public ProductionReadinessRunner(
            @Value("${app.auth.jwt.secret}") String jwtSecret,
            @Value("${app.cors.allowed-origins}") String corsAllowedOrigins,
            @Value("${app.auth.ldap.enabled:true}") boolean ldapEnabled,
            @Value("${app.auth.ldap.trust-self-signed:false}") boolean ldapTrustSelfSigned,
            @Value("${spring.datasource.password:}") String datasourcePassword) {
        this.jwtSecret = jwtSecret;
        this.corsAllowedOrigins = corsAllowedOrigins;
        this.ldapEnabled = ldapEnabled;
        this.ldapTrustSelfSigned = ldapTrustSelfSigned;
        this.datasourcePassword = datasourcePassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> findings = findings();
        if (findings.isEmpty()) {
            log.info("Production readiness: no shipped defaults left in place.");
            return;
        }
        log.warn("""

                ***************************************************************
                *  CONFIGURATION STILL ON ITS SHIPPED DEFAULTS                *
                *  Fine for a developer's machine. Not for a plant.           *
                ***************************************************************
                {}
                See README — "Before production — the settings that must not stay as they ship".
                """, String.join(System.lineSeparator(), findings));
    }

    /**
     * The findings, as lines. Package-private so a test can assert each rule on its own rather
     * than by scraping a log.
     */
    List<String> findings() {
        List<String> out = new ArrayList<>();

        if (SHIPPED_JWT_SECRET.equals(jwtSecret)) {
            out.add("  - APP_AUTH_JWT_SECRET is the value published in this repository. Anyone who "
                    + "can read it can forge a token for any user. Set at least "
                    + MIN_JWT_SECRET_BYTES + " bytes of real randomness, unique per environment.");
        } else if (jwtSecret != null && jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 64) {
            // Above JwtService's hard floor, below what a signing key should be. Worth saying;
            // not worth refusing, since it is a real choice somebody made.
            out.add("  - APP_AUTH_JWT_SECRET is shorter than 64 bytes. It is accepted, but a "
                    + "signing key should be longer than a password.");
        }

        if ("*".equals(corsAllowedOrigins == null ? null : corsAllowedOrigins.trim())) {
            out.add("  - APP_CORS_ALLOWED_ORIGINS is \"*\". Set the PWA's actual origin(s).");
        }

        if (ldapEnabled && ldapTrustSelfSigned) {
            out.add("  - APP_AUTH_LDAP_TRUST_SELF_SIGNED is true while LDAP is enabled: the domain "
                    + "controller's certificate is not verified, so the bind is interceptable. Set "
                    + "it false and import the CA into the JVM truststore.");
        }

        if ("postgres".equals(datasourcePassword)) {
            out.add("  - SPRING_DATASOURCE_PASSWORD is still \"postgres\".");
        }

        return out;
    }
}
