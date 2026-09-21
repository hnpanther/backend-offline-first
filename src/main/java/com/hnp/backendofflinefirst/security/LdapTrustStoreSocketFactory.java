package com.hnp.backendofflinefirst.security;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * The TLS socket factory JNDI uses for {@code ldaps://} when a truststore is configured for
 * Active Directory, so the domain controllers' self-signed certificates can be trusted without
 * touching the JVM's own {@code cacerts} or its command line.
 *
 * <p>JNDI has exactly one way to be given a socket factory: the class name in
 * {@code java.naming.ldap.factory.socket}, which it instantiates through a static
 * {@code getDefault()} with no arguments. That forces the configuration to be static — there is
 * no object to hand it to — which is why {@link #configure} exists and is called once, at
 * start-up, by {@link LdapAuthenticationService}. Nothing else should call it.
 *
 * <p>With a truststore the trust decision is "is this certificate, or its issuer, in it" and
 * nothing looser. A truststore holding the domain controllers' own self-signed certificates is
 * therefore a <em>pin</em>: only those servers can complete the handshake, whatever name was
 * used to reach them. That is what makes switching hostname verification off tolerable behind a
 * balancer name or a bare IP — see {@code app.auth.ldap.verify-hostname}.
 *
 * <p>The other mode — accept any certificate — is {@link TrustAllLdapSslSocketFactory}, which
 * predates this class and stays separate: it needs no configuration step, and keeping the two
 * apart means a deployment can never be told it has a pin when it has none.
 */
public final class LdapTrustStoreSocketFactory extends SSLSocketFactory {

    private static volatile SSLSocketFactory configured;

    private final SSLSocketFactory delegate;

    private LdapTrustStoreSocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    /**
     * What JNDI calls. Fails loudly if nobody configured a truststore, rather than trusting
     * nothing silently.
     */
    public static SocketFactory getDefault() {
        SSLSocketFactory factory = configured;
        if (factory == null) {
            throw new IllegalStateException("LdapTrustStoreSocketFactory used before a truststore was configured");
        }
        return new LdapTrustStoreSocketFactory(factory);
    }

    /**
     * Builds the trust from a keystore file. Called once at start-up; a second call replaces the
     * first, which is only useful to tests.
     *
     * @throws IllegalStateException when the file cannot be read or is not a keystore of the
     *         given type — a boot failure, because every Active Directory login would fail
     *         anyway, and a failed boot names the file where a failed login would not
     */
    public static void configure(Path trustStore, String password, String type) {
        try (InputStream in = Files.newInputStream(trustStore)) {
            KeyStore keyStore = KeyStore.getInstance(type == null || type.isBlank() ? "PKCS12" : type);
            keyStore.load(in, password == null ? new char[0] : password.toCharArray());
            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(keyStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            configured = context.getSocketFactory();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(
                    "cannot load the Active Directory truststore " + trustStore + ": " + e.getMessage(), e);
        }
    }

    static boolean isConfigured() {
        return configured != null;
    }

    /** Tests only: forget the pin so one test's truststore cannot leak into the next. */
    static void reset() {
        configured = null;
    }

    // ---------------------------------------------------------------- delegation

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        return delegate.createSocket(socket, host, port, autoClose);
    }

    @Override
    public Socket createSocket() throws IOException {
        return delegate.createSocket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return delegate.createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return delegate.createSocket(address, port, localAddress, localPort);
    }
}
