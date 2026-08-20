package com.hnp.backendofflinefirst.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boot-time reading of "is anything here still the value that ships in the repository?".
 *
 * <p>Each rule is asserted on its own, because the failure this guards against is a rule that
 * silently stops firing — and a warning that no longer appears looks exactly like a system that
 * is correctly configured.
 */
class ProductionReadinessRunnerTest {

    private static final String REAL_SECRET =
            "b7f3c1a9e04d5f26b8c7d1e93a4f60528c9d7e1b3a5f8c204e6d9b1a7c3f5e08";

    private ProductionReadinessRunner runner(String jwtSecret, String cors,
                                             boolean ldapEnabled, boolean trustSelfSigned,
                                             String dbPassword) {
        return new ProductionReadinessRunner(jwtSecret, cors, ldapEnabled, trustSelfSigned, dbPassword);
    }

    private ProductionReadinessRunner configured() {
        return runner(REAL_SECRET, "https://pwa.plant.local", true, false, "a-real-password");
    }

    @Test
    void aProperlyConfiguredDeploymentReportsNothing() {
        assertThat(configured().findings()).isEmpty();
    }

    @Test
    void theShippedJwtSecretIsReported() {
        // The one that matters most: it is published here, so a token for any user can be forged
        // by anyone who can read the repository, and nothing downstream would notice.
        List<String> findings = runner(ProductionReadinessRunner.SHIPPED_JWT_SECRET,
                "https://pwa.plant.local", true, false, "a-real-password").findings();

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst()).contains("APP_AUTH_JWT_SECRET", "forge");
    }

    @Test
    void aShortButChangedSecretIsMentionedWithoutBeingConfusedForTheShippedOne() {
        List<String> findings = runner("0123456789abcdef0123456789abcdef",
                "https://pwa.plant.local", true, false, "a-real-password").findings();

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst()).contains("shorter than 64 bytes");
        // Not the "published in this repository" wording — that would send somebody looking for
        // a secret they have already replaced.
        assertThat(findings.getFirst()).doesNotContain("forge");
    }

    @Test
    void aWildcardCorsOriginIsReported() {
        List<String> findings = runner(REAL_SECRET, "*", true, false, "a-real-password").findings();

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst()).contains("APP_CORS_ALLOWED_ORIGINS");
    }

    @Test
    void surroundingWhitespaceDoesNotHideAWildcardOrigin() {
        assertThat(runner(REAL_SECRET, "  *  ", true, false, "a-real-password").findings())
                .hasSize(1);
    }

    @Test
    void anExplicitOriginListIsAccepted() {
        assertThat(runner(REAL_SECRET, "https://a.local,https://b.local", true, false, "pw").findings())
                .isEmpty();
    }

    @Test
    void trustingSelfSignedLdapIsReportedOnlyWhenLdapIsOn() {
        assertThat(runner(REAL_SECRET, "https://pwa.plant.local", true, true, "pw").findings())
                .singleElement().asString().contains("LDAP_TRUST_SELF_SIGNED");
        // With LDAP off the flag decides nothing, and reporting it would be noise that trains
        // people to skim the block.
        assertThat(runner(REAL_SECRET, "https://pwa.plant.local", false, true, "pw").findings())
                .isEmpty();
    }

    @Test
    void theDefaultDatabasePasswordIsReported() {
        assertThat(runner(REAL_SECRET, "https://pwa.plant.local", true, false, "postgres").findings())
                .singleElement().asString().contains("SPRING_DATASOURCE_PASSWORD");
    }

    @Test
    void everyFindingIsReportedAtOnceRatherThanOnePerBoot() {
        // A check that reports only the first problem takes four restarts to reveal four
        // problems, which in practice means three of them are never seen.
        List<String> findings = runner(ProductionReadinessRunner.SHIPPED_JWT_SECRET,
                "*", true, true, "postgres").findings();

        assertThat(findings).hasSize(4);
    }

    @Test
    void aNullSecretIsNotMistakenForTheShippedOne() {
        // JwtService refuses to start on this; the point here is only that the check does not
        // throw on the way to that refusal.
        assertThat(runner(null, "https://pwa.plant.local", true, false, "pw").findings()).isEmpty();
    }
}
