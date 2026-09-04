package com.hnp.backendofflinefirst.security;

import org.junit.jupiter.api.Test;

import javax.naming.Context;
import javax.naming.directory.InitialDirContext;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Hashtable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.hnp.backendofflinefirst.security.LdapAuthenticationService.shouldTrustAllCertificates;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which socket the LDAP bind actually opens.
 *
 * <h2>The defect this locks down</h2>
 *
 * <p>{@code java.naming.ldap.factory.socket} is used by the JNDI LDAP provider for every
 * connection, not only for {@code ldaps://}. The trust-all factory was applied whenever
 * {@code trust-self-signed} was true, regardless of scheme — so pointing the application at a
 * plaintext domain controller made it send a <b>TLS ClientHello to port 389</b> and fail every
 * bind, with a handshake error that names nothing relevant. Since {@code trust-self-signed}
 * ships as {@code true}, "use a plaintext DC" was a configuration that could not be reached
 * without also knowing to change a flag whose name only mentions certificates.
 *
 * <h2>Why it is tested twice</h2>
 *
 * <p>{@link #theSchemeDecidesWhetherTheTrustAllFactoryIsUsed()} asserts the decision for every
 * combination, which is cheap and exhaustive. It cannot, however, prove that the decision
 * reaches JNDI — so {@link #aPlaintextUrlPutsAnUnencryptedBindOnTheWire()} and
 * {@link #anLdapsUrlStillNegotiatesTls()} read the first bytes off a real socket. A TLS
 * ClientHello begins {@code 16 03}; an LDAP BER BindRequest begins {@code 30}. That is the
 * difference the domain controller sees, and it is the only evidence that settles it.
 */
class LdapSocketFactorySelectionTest {

    private static final byte TLS_HANDSHAKE = 0x16;
    private static final byte BER_SEQUENCE = 0x30;

    @Test
    void theSchemeDecidesWhetherTheTrustAllFactoryIsUsed() {
        // The only combination whose behaviour this change alters. It could not connect before.
        assertThat(shouldTrustAllCertificates("ldap://172.29.76.9", true))
                .as("a plaintext URL must never be given an SSL socket factory")
                .isFalse();

        // The three that must be left exactly as they were.
        assertThat(shouldTrustAllCertificates("ldaps://dc.site.hnp:636", true))
                .as("LDAPS with trust-self-signed is what the factory exists for")
                .isTrue();
        assertThat(shouldTrustAllCertificates("ldaps://dc.site.hnp:636", false))
                .as("LDAPS without the flag keeps the JVM's default certificate validation")
                .isFalse();
        assertThat(shouldTrustAllCertificates("ldap://172.29.76.9", false))
                .as("plaintext without the flag was already correct")
                .isFalse();
    }

    @Test
    void theSchemeTestIsNotFooledByCaseSpacingOrTheSharedPrefix() {
        // "ldaps://" also starts with "ldap", so a careless prefix test would treat every LDAPS
        // deployment as plaintext and silently drop certificate handling.
        assertThat(shouldTrustAllCertificates("LDAPS://DC.SITE.HNP:636", true)).isTrue();
        assertThat(shouldTrustAllCertificates("  ldaps://dc.site.hnp:636  ", true)).isTrue();
        assertThat(shouldTrustAllCertificates("LDAP://172.29.76.9", true)).isFalse();
        assertThat(shouldTrustAllCertificates("  ldap://172.29.76.9  ", true)).isFalse();

        // Absent configuration must not be read as "encrypted".
        assertThat(shouldTrustAllCertificates(null, true)).isFalse();
        assertThat(shouldTrustAllCertificates("", true)).isFalse();
        assertThat(shouldTrustAllCertificates("   ", true)).isFalse();
    }

    @Test
    void aPlaintextUrlPutsAnUnencryptedBindOnTheWire() throws Exception {
        byte[] first = firstBytesOfBindTo("ldap://127.0.0.1:%d", true);

        assertThat(first).isNotEmpty();
        assertThat(first[0])
                .as("expected an LDAP BindRequest (0x30); 0x16 would mean a TLS handshake was "
                        + "sent to a plaintext port, which is the bug this guards")
                .isEqualTo(BER_SEQUENCE);
    }

    @Test
    void anLdapsUrlStillNegotiatesTls() throws Exception {
        byte[] first = firstBytesOfBindTo("ldaps://127.0.0.1:%d", true);

        assertThat(first).isNotEmpty();
        assertThat(first[0])
                .as("LDAPS must still start with a TLS handshake — the guard must not have "
                        + "disabled encryption for the scheme that needs it")
                .isEqualTo(TLS_HANDSHAKE);
    }

    /**
     * Binds against a socket that accepts and never answers, and returns what was sent first.
     *
     * <p>The bind cannot succeed and is not meant to: the question is only what the client put on
     * the wire before it gave up, which is decided entirely by the socket factory.
     */
    private byte[] firstBytesOfBindTo(String urlTemplate, boolean trustSelfSigned) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(10_000);
            CompletableFuture<byte[]> captured = CompletableFuture.supplyAsync(() -> {
                try (Socket accepted = server.accept(); InputStream in = accepted.getInputStream()) {
                    byte[] buffer = new byte[8];
                    int read = in.read(buffer);
                    if (read <= 0) {
                        return new byte[0];
                    }
                    byte[] out = new byte[read];
                    System.arraycopy(buffer, 0, out, 0, read);
                    return out;
                } catch (Exception e) {
                    return new byte[0];
                }
            });

            String url = String.format(urlTemplate, server.getLocalPort());
            Hashtable<String, Object> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            env.put(Context.PROVIDER_URL, url);
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, "probe@site.hnp");
            env.put(Context.SECURITY_CREDENTIALS, "not-a-real-password");
            env.put("com.sun.jndi.ldap.connect.timeout", "3000");
            env.put("com.sun.jndi.ldap.read.timeout", "3000");
            // Exactly the line under test, applied the way the service applies it.
            if (shouldTrustAllCertificates(url, trustSelfSigned)) {
                env.put("java.naming.ldap.factory.socket", TrustAllLdapSslSocketFactory.class.getName());
            }

            try {
                new InitialDirContext(env).close();
            } catch (Exception expected) {
                // Nothing is listening for a real conversation; only the first bytes matter.
            }
            return captured.get(15, TimeUnit.SECONDS);
        }
    }
}
