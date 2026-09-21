package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.config.LdapAuthProperties;
import com.hnp.backendofflinefirst.security.LdapAuthenticationService.BindResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.naming.AuthenticationException;
import javax.naming.AuthenticationNotSupportedException;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.ServiceUnavailableException;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Hashtable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the Active Directory connection is shaped for a real network — several controllers,
 * bounded waits, self-signed certificates pinned in a truststore, a name check that can be
 * switched off only when that pin exists — and what one bind answers with.
 *
 * <p>No directory server is involved. What is tested is what the service hands JNDI, what the
 * TLS trust actually accepts and refuses on a real handshake, and what verdict comes back from
 * a socket that behaves like a controller: one that says yes, one that says no with Active
 * Directory's own diagnostic, one that never answers, and one that is not there.
 */
class LdapConnectionShapeTest {

    private static final Path FIXTURES = Path.of("src/test/resources/ldap-tls");
    private static final char[] PASSWORD = "changeit".toCharArray();

    /** Active Directory's refusal, verbatim in shape: the sub-code is the only word that matters. */
    private static final String AD_WRONG_PASSWORD =
            "80090308: LdapErr: DSID-0C09042A, comment: AcceptSecurityContext error, data 52e, v3839";

    @AfterEach
    void clearStaticState() {
        System.clearProperty(LdapAuthenticationService.DISABLE_ENDPOINT_IDENTIFICATION);
        LdapTrustStoreSocketFactory.reset();
    }

    // ---------------------------------------------------------------- the URL list

    @Test
    @DisplayName("servers may be separated by spaces or commas; JNDI gets them space-separated, in order")
    void serversAreNormalisedIntoAFailoverList() {
        assertThat(LdapAuthenticationService.providerUrlOf("ldaps://dc1:636, ldaps://dc2:636 ,ldaps://dc3:636"))
                .isEqualTo("ldaps://dc1:636 ldaps://dc2:636 ldaps://dc3:636");
        assertThat(LdapAuthenticationService.providerUrlOf("  ldaps://dc1:636  ")).isEqualTo("ldaps://dc1:636");
        assertThat(LdapAuthenticationService.providerUrlOf(null)).isEmpty();
        assertThat(LdapAuthenticationService.providerUrlOf(" , ")).isEmpty();
    }

    @Test
    void theListReachesJndiAsOneProviderUrl() {
        LdapAuthenticationService service = service(p -> p.setUrl("ldaps://dc1:636,ldaps://dc2:636"));

        assertThat(service.contextEnvironment())
                .containsEntry(Context.PROVIDER_URL, "ldaps://dc1:636 ldaps://dc2:636");
    }

    // ---------------------------------------------------------------- timeouts

    @Test
    void theOneTimeoutIsHandedToJndiAsBoth() {
        LdapAuthenticationService service = service(p -> p.setTimeoutMs(7000));

        assertThat(service.contextEnvironment())
                .containsEntry("com.sun.jndi.ldap.connect.timeout", "7000")
                .containsEntry("com.sun.jndi.ldap.read.timeout", "7000");
    }

    // ---------------------------------------------------------------- which trust, and when

    @Test
    void trustSelfSignedHandsJndiTheTrustAllFactoryAndSwitchesTheNameCheckOff() {
        LdapAuthenticationService service = service(p -> p.setTrustSelfSigned(true));

        service.prepare();

        assertThat(service.contextEnvironment())
                .containsEntry("java.naming.ldap.factory.socket", TrustAllLdapSslSocketFactory.class.getName());
        // The JDK checks the name even through a trust-everything factory; without this the
        // flag "did nothing" against a bare IP or a balancer name.
        assertThat(System.getProperty(LdapAuthenticationService.DISABLE_ENDPOINT_IDENTIFICATION)).isEqualTo("true");
    }

    @Test
    void aTruststorePutsThePinnedFactoryIntoTheEnvironment() {
        LdapAuthenticationService service = service(p -> p.setTruststore(fixture("truststore-dc1.p12")));

        service.prepare();

        assertThat(service.contextEnvironment())
                .containsEntry("java.naming.ldap.factory.socket", LdapTrustStoreSocketFactory.class.getName());
        assertThat(LdapTrustStoreSocketFactory.isConfigured()).isTrue();
        assertThat(System.getProperty(LdapAuthenticationService.DISABLE_ENDPOINT_IDENTIFICATION))
                .as("hostname verification stays on unless asked")
                .isNull();
    }

    @Test
    void trustSelfSignedWinsOverATruststore() {
        LdapAuthenticationService service = service(p -> {
            p.setTrustSelfSigned(true);
            p.setTruststore(fixture("truststore-dc1.p12"));
        });

        service.prepare();

        assertThat(service.contextEnvironment())
                .containsEntry("java.naming.ldap.factory.socket", TrustAllLdapSslSocketFactory.class.getName());
    }

    @Test
    void withNeitherTheJvmsOwnTrustIsUsed() {
        LdapAuthenticationService service = service(p -> { });

        service.prepare();

        assertThat(service.contextEnvironment()).doesNotContainKey("java.naming.ldap.factory.socket");
        assertThat(System.getProperty(LdapAuthenticationService.DISABLE_ENDPOINT_IDENTIFICATION)).isNull();
    }

    @Test
    void noTlsFactoryOnAPlaintextUrlWhateverTheTrustSettings() {
        // The trap LdapSocketFactorySelectionTest reads off the wire: an SSL factory on ldap://
        // sends a TLS ClientHello to port 389. Neither trust mode may reintroduce it.
        LdapAuthenticationService trustAll = service(p -> {
            p.setUrl("ldap://172.29.76.9");
            p.setTrustSelfSigned(true);
        });
        LdapAuthenticationService pinned = service(p -> {
            p.setUrl("ldap://172.29.76.9");
            p.setTruststore(fixture("truststore-dc1.p12"));
        });

        trustAll.prepare();
        pinned.prepare();

        assertThat(trustAll.contextEnvironment()).doesNotContainKey("java.naming.ldap.factory.socket");
        assertThat(pinned.contextEnvironment()).doesNotContainKey("java.naming.ldap.factory.socket");
    }

    @Test
    void aMixedListGetsNoTlsFactoryEither() {
        // The factory applies to every connection alike, so a list that mixes schemes cannot be
        // given one without breaking its plaintext member. The service warns at start-up.
        assertThat(LdapAuthenticationService.shouldTrustAllCertificates("ldaps://dc1:636 ldap://dc2:389", true))
                .isFalse();
        assertThat(LdapAuthenticationService.shouldTrustAllCertificates("ldaps://dc1:636 ldaps://dc2:636", true))
                .isTrue();
    }

    @Test
    void hostnameVerificationCannotBeSwitchedOffWithoutAPin() {
        LdapAuthenticationService service = service(p -> p.setVerifyHostname(false));

        assertThatThrownBy(service::prepare)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verify-hostname=false requires");
        assertThat(System.getProperty(LdapAuthenticationService.DISABLE_ENDPOINT_IDENTIFICATION)).isNull();
    }

    @Test
    void hostnameVerificationOffWithAPin() {
        LdapAuthenticationService service = service(p -> {
            p.setVerifyHostname(false);
            p.setTruststore(fixture("truststore-dc1.p12"));
        });

        service.prepare();

        assertThat(System.getProperty(LdapAuthenticationService.DISABLE_ENDPOINT_IDENTIFICATION)).isEqualTo("true");
    }

    @Test
    void aTruststoreThatCannotBeReadFailsTheBootByName() {
        LdapAuthenticationService service = service(p -> p.setTruststore(fixture("no-such-file.p12")));

        assertThatThrownBy(service::prepare)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-file.p12");
    }

    @Test
    void disabledPreparesNothing() {
        LdapAuthenticationService service = service(p -> {
            p.setEnabled(false);
            p.setVerifyHostname(false); // would be refused if it were looked at
        });

        service.prepare();

        assertThat(System.getProperty(LdapAuthenticationService.DISABLE_ENDPOINT_IDENTIFICATION)).isNull();
    }

    // ---------------------------------------------------------------- what the pin accepts

    /**
     * The pin, exercised with a real TLS handshake: a server presenting the certificate in the
     * truststore is accepted, one presenting a different self-signed certificate is not — even
     * though both are "valid" certificates and neither name is checked here.
     */
    @Test
    @DisplayName("the truststore accepts the controller whose certificate it holds and refuses any other")
    void theTrustIsAPin() throws Exception {
        service(p -> p.setTruststore(fixture("truststore-dc1.p12"))).prepare();

        assertThat(handshakeAgainst("dc1-key.p12")).as("dc1 is in the truststore").isNull();
        assertThat(handshakeAgainst("dc2-key.p12")).as("dc2 is not").isInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void theFactoryRefusesToBeUsedBeforeAnyPinIsConfigured() {
        assertThatThrownBy(LdapTrustStoreSocketFactory::getDefault)
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- the verdict table

    @Test
    void onlyTheDirectorysOwnRefusalIsARejection() {
        assertThat(LdapAuthenticationService.classify(new AuthenticationException("[LDAP: error code 49 - " + AD_WRONG_PASSWORD + "]")))
                .isEqualTo(BindResult.REJECTED);

        // Everything else is the directory not having been asked.
        assertThat(LdapAuthenticationService.classify(new CommunicationException("simple bind failed: dc1:636")))
                .isEqualTo(BindResult.UNAVAILABLE);
        assertThat(LdapAuthenticationService.classify(new ServiceUnavailableException("dc1:636")))
                .isEqualTo(BindResult.UNAVAILABLE);
        assertThat(LdapAuthenticationService.classify(new AuthenticationNotSupportedException("strongAuthRequired")))
                .isEqualTo(BindResult.UNAVAILABLE);
        assertThat(LdapAuthenticationService.classify(new IllegalStateException("socket factory")))
                .isEqualTo(BindResult.UNAVAILABLE);
    }

    @Test
    void activeDirectorysSubCodesAreNamedInTheLog() {
        assertThat(LdapAuthenticationService.describeDirectoryError("[LDAP: error code 49 - " + AD_WRONG_PASSWORD + "]"))
                .isEqualTo("wrong password (data 52e)");
        assertThat(LdapAuthenticationService.describeDirectoryError("... comment: AcceptSecurityContext error, data 775, v4563"))
                .isEqualTo("account locked out (data 775)");
        assertThat(LdapAuthenticationService.describeDirectoryError("... data 533, v4563"))
                .isEqualTo("account disabled (data 533)");
        // Not Active Directory, or a code this build does not know: the message, untouched.
        assertThat(LdapAuthenticationService.describeDirectoryError("[LDAP: error code 49 - Invalid Credentials]"))
                .isEqualTo("[LDAP: error code 49 - Invalid Credentials]");
        assertThat(LdapAuthenticationService.describeDirectoryError(null)).isEqualTo("no diagnostic message");
    }

    // ---------------------------------------------------------------- one bind, one verdict

    @Test
    void aControllerThatSaysYes() throws Exception {
        try (FakeController dc = FakeController.answering(0, "")) {
            LdapAuthenticationService service = service(p -> p.setUrl(dc.url()));

            assertThat(service.bind("probe", "right-password")).isEqualTo(BindResult.AUTHENTICATED);
        }
    }

    @Test
    void aControllerThatSaysNoIsARejectionNotAnOutage() throws Exception {
        try (FakeController dc = FakeController.answering(49, AD_WRONG_PASSWORD)) {
            LdapAuthenticationService service = service(p -> p.setUrl(dc.url()));

            assertThat(service.bind("probe", "wrong-password")).isEqualTo(BindResult.REJECTED);
        }
    }

    @Test
    void aControllerThatIsNotThereIsAnOutage() throws Exception {
        int closedPort = closedPort();
        LdapAuthenticationService service = service(p -> p.setUrl("ldap://127.0.0.1:" + closedPort));

        assertThat(service.bind("probe", "password")).isEqualTo(BindResult.UNAVAILABLE);
    }

    /**
     * A controller that accepts the connection and never answers the bind. Bounded by
     * {@code timeout-ms}, and the assertion is deliberately tighter than the OS's TCP timeout:
     * this is also the proof that JNDI applies the <em>connect</em> timeout to the bind's answer
     * (there is no separate read timeout in play here, and none is offered — see
     * {@code LdapAuthProperties#getTimeoutMs()}).
     */
    @Test
    void aControllerThatNeverAnswersIsBoundedByTheTimeout() throws Exception {
        try (FakeController dc = FakeController.blackHole()) {
            LdapAuthenticationService service = service(p -> {
                p.setUrl(dc.url());
                p.setTimeoutMs(500);
            });

            long started = System.nanoTime();
            BindResult result = service.bind("probe", "password");
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(result).isEqualTo(BindResult.UNAVAILABLE);
            assertThat(elapsedMs)
                    .as("a black-hole controller must not hold the login for the OS's TCP timeout")
                    .isLessThan(3000);
        }
    }

    @Test
    void theSecondControllerIsTriedWhenTheFirstRefusesTheConnection() throws Exception {
        int closedPort = closedPort();
        try (FakeController dc = FakeController.answering(0, "")) {
            LdapAuthenticationService service = service(p -> p.setUrl(
                    "ldap://127.0.0.1:" + closedPort + ", " + dc.url()));

            assertThat(service.bind("probe", "right-password")).isEqualTo(BindResult.AUTHENTICATED);
            assertThat(dc.bindsSeen()).as("the second controller did the bind").isEqualTo(1);
        }
    }

    /**
     * An empty password never leaves the process. LDAP treats a bind with a name and no password
     * as an anonymous bind, which Active Directory accepts — so forwarding it would sign in
     * anyone who knew a username. The controller here would say yes to anything; it must not
     * even be contacted.
     */
    @Test
    void anEmptyPasswordIsRefusedWithoutContactingTheDirectory() throws Exception {
        try (FakeController dc = FakeController.answering(0, "")) {
            LdapAuthenticationService service = service(p -> p.setUrl(dc.url()));

            assertThat(service.bind("probe", "")).isEqualTo(BindResult.REJECTED);
            assertThat(service.bind("probe", "   ")).isEqualTo(BindResult.REJECTED);
            assertThat(service.bind("probe", null)).isEqualTo(BindResult.REJECTED);
            assertThat(dc.bindsSeen()).isZero();
        }
    }

    @Test
    void disabledOrUnconfiguredIsUnavailableNotAWrongPassword() {
        assertThat(service(p -> p.setEnabled(false)).bind("probe", "password")).isEqualTo(BindResult.UNAVAILABLE);
        assertThat(service(p -> p.setUrl("")).bind("probe", "password")).isEqualTo(BindResult.UNAVAILABLE);
        assertThat(service(p -> p.setDomain("")).bind("probe", "password")).isEqualTo(BindResult.UNAVAILABLE);
    }

    // ---------------------------------------------------------------- helpers

    private static LdapAuthenticationService service(java.util.function.Consumer<LdapAuthProperties> configure) {
        LdapAuthProperties properties = new LdapAuthProperties();
        properties.setEnabled(true);
        properties.setDomain("site.test");
        properties.setUrl("ldaps://dc1.site.test:636");
        properties.setTimeoutMs(3000);
        properties.setTrustSelfSigned(false);
        properties.setVerifyHostname(true);
        properties.setTruststorePassword("changeit");
        configure.accept(properties);
        return new LdapAuthenticationService(properties);
    }

    private static String fixture(String name) {
        return FIXTURES.resolve(name).toString();
    }

    /** A port nothing listens on: bound once to learn its number, then released. */
    private static int closedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    /** Runs a TLS server with the given key, connects through the pinned factory, returns the failure or null. */
    private static Throwable handshakeAgainst(String serverKeyStore) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(FIXTURES.resolve(serverKeyStore))) {
            keys.load(in, PASSWORD);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);

        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> serverSide = CompletableFuture.runAsync(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.startHandshake();
                } catch (IOException ignored) {
                    // the client's verdict is what the test reads
                }
            });
            try (SSLSocket client = (SSLSocket) LdapTrustStoreSocketFactory.getDefault()
                    .createSocket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.startHandshake();
                return null;
            } catch (SSLHandshakeException e) {
                return e;
            } finally {
                serverSide.join();
            }
        }
    }

    /**
     * Enough of a domain controller to answer one bind: reads the BindRequest, answers with a
     * BindResponse carrying the given result code and diagnostic message, then closes. A
     * black hole accepts and never answers.
     */
    private static final class FakeController implements AutoCloseable {

        private final ServerSocket server;
        private final CompletableFuture<Void> loop;
        private final AtomicInteger binds = new AtomicInteger();
        private volatile boolean closed;

        private FakeController(Integer resultCode, String diagnostic) throws IOException {
            server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            loop = CompletableFuture.runAsync(() -> serve(resultCode, diagnostic));
        }

        static FakeController answering(int resultCode, String diagnostic) throws IOException {
            return new FakeController(resultCode, diagnostic);
        }

        static FakeController blackHole() throws IOException {
            return new FakeController(null, null);
        }

        String url() {
            return "ldap://127.0.0.1:" + server.getLocalPort();
        }

        int bindsSeen() {
            return binds.get();
        }

        private void serve(Integer resultCode, String diagnostic) {
            while (!closed) {
                try (Socket client = server.accept()) {
                    if (resultCode == null) {
                        // Black hole: hold the connection open until the client gives up.
                        while (!closed) {
                            Thread.sleep(50);
                        }
                        continue;
                    }
                    InputStream in = client.getInputStream();
                    byte[] request = readOneMessage(in);
                    int messageId = messageIdOf(request);
                    binds.incrementAndGet();
                    OutputStream out = client.getOutputStream();
                    out.write(bindResponse(messageId, resultCode, diagnostic));
                    out.flush();
                    // The client either unbinds (success) or drops the connection (refusal);
                    // both end at EOF, and the socket closes on the way out.
                    readOneMessageQuietly(in);
                } catch (Exception e) {
                    if (closed) {
                        return;
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            server.close();
            loop.join();
        }

        // -- just enough BER --------------------------------------------------------------

        /** A complete top-level SEQUENCE, however it was split across reads. */
        private static byte[] readOneMessage(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int first = in.read();
            if (first < 0) {
                throw new IOException("EOF before a message");
            }
            buffer.write(first);
            int lengthByte = in.read();
            buffer.write(lengthByte);
            int length;
            if (lengthByte < 0x80) {
                length = lengthByte;
            } else {
                int count = lengthByte & 0x7f;
                length = 0;
                for (int i = 0; i < count; i++) {
                    int b = in.read();
                    buffer.write(b);
                    length = (length << 8) | b;
                }
            }
            byte[] body = in.readNBytes(length);
            buffer.write(body);
            return buffer.toByteArray();
        }

        private static void readOneMessageQuietly(InputStream in) {
            try {
                readOneMessage(in);
            } catch (IOException ignored) {
                // EOF is the expected ending
            }
        }

        /** The INTEGER right after the outer SEQUENCE header. */
        private static int messageIdOf(byte[] message) {
            int i = 1;
            int lengthByte = message[i++] & 0xff;
            if (lengthByte >= 0x80) {
                i += lengthByte & 0x7f;
            }
            if (message[i++] != 0x02) {
                throw new IllegalStateException("expected an INTEGER message id");
            }
            int idLength = message[i++] & 0xff;
            int id = 0;
            for (int n = 0; n < idLength; n++) {
                id = (id << 8) | (message[i++] & 0xff);
            }
            return id;
        }

        private static byte[] bindResponse(int messageId, int resultCode, String diagnostic) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(ber(0x0a, new byte[]{(byte) resultCode}));                   // resultCode ENUMERATED
            body.write(ber(0x04, new byte[0]));                                     // matchedDN
            body.write(ber(0x04, diagnostic.getBytes(StandardCharsets.UTF_8)));    // diagnosticMessage

            ByteArrayOutputStream message = new ByteArrayOutputStream();
            message.write(ber(0x02, new byte[]{(byte) messageId}));                 // messageID
            message.write(ber(0x61, body.toByteArray()));                            // BindResponse [APPLICATION 1]
            return ber(0x30, message.toByteArray());
        }

        private static byte[] ber(int tag, byte[] content) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(tag);
            int length = content.length;
            if (length < 0x80) {
                out.write(length);
            } else if (length < 0x100) {
                out.write(0x81);
                out.write(length);
            } else {
                out.write(0x82);
                out.write(length >> 8);
                out.write(length & 0xff);
            }
            out.write(content);
            return out.toByteArray();
        }
    }
}
