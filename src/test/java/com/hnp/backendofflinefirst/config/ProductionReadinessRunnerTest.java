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

    /**
     * The LDAPS URL these cases assume. Kept out of the shorter helper below so the existing
     * cases keep asserting what they always did: an encrypted URL means the plaintext rule stays
     * silent, and each of those tests still sees exactly the findings it was written for.
     */
    private static final String LDAPS_URL = "ldaps://dc.site.hnp:636";

    private ProductionReadinessRunner runner(String jwtSecret, String cors,
                                             boolean ldapEnabled, boolean trustSelfSigned,
                                             String dbPassword) {
        return runner(jwtSecret, cors, ldapEnabled, trustSelfSigned, LDAPS_URL, dbPassword);
    }

    private ProductionReadinessRunner runner(String jwtSecret, String cors,
                                             boolean ldapEnabled, boolean trustSelfSigned,
                                             String ldapUrl, String dbPassword) {
        return new ProductionReadinessRunner(jwtSecret, cors, ldapEnabled, trustSelfSigned,
                ldapUrl, dbPassword);
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
    void aPlaintextLdapUrlIsReportedBecauseThePasswordCrossesTheNetworkInClear() {
        List<String> findings = runner(REAL_SECRET, "https://pwa.plant.local",
                true, false, "ldap://172.29.76.9", "pw").findings();

        assertThat(findings).singleElement().asString()
                .contains("plaintext ldap:// URL")
                .contains("unencrypted");
    }

    @Test
    void aPlaintextUrlReplacesTheCertificateWarningRatherThanAddingToIt() {
        // Both flags are "wrong", but there is no certificate on a plaintext connection, so the
        // truststore advice would send the reader after a problem that does not exist. One
        // finding, and it says the trust flag is inert here.
        List<String> findings = runner(REAL_SECRET, "https://pwa.plant.local",
                true, true, "ldap://172.29.76.9", "pw").findings();

        assertThat(findings).singleElement().asString()
                .contains("plaintext ldap:// URL")
                .contains("does nothing")
                .doesNotContain("import the CA");
    }

    @Test
    void anLdapsUrlIsNotMistakenForAPlaintextOne() {
        // "ldaps://" also starts with "ldap", so a prefix test alone reports every LDAPS
        // deployment as plaintext — the one way this check could do real harm.
        assertThat(runner(REAL_SECRET, "https://pwa.plant.local",
                true, false, "ldaps://dc.site.hnp:636", "pw").findings()).isEmpty();

        assertThat(runner(REAL_SECRET, "https://pwa.plant.local",
                true, false, "LDAPS://DC.SITE.HNP:636", "pw").findings()).isEmpty();
    }

    @Test
    void aPlaintextUrlIsIgnoredWhileLdapIsOff() {
        // Same reasoning as the trust-self-signed rule: with LDAP disabled the URL decides
        // nothing, and a warning about it trains people to skim the block.
        assertThat(runner(REAL_SECRET, "https://pwa.plant.local",
                false, true, "ldap://172.29.76.9", "pw").findings()).isEmpty();
    }

    @Test
    void anAbsentLdapUrlIsNotReportedHere() {
        // LdapAuthenticationService already refuses a blank URL by name at bind time; a second
        // message would be noise, and "" must certainly not read as plaintext.
        assertThat(runner(REAL_SECRET, "https://pwa.plant.local",
                true, false, "", "pw").findings()).isEmpty();
        assertThat(runner(REAL_SECRET, "https://pwa.plant.local",
                true, false, null, "pw").findings()).isEmpty();
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
