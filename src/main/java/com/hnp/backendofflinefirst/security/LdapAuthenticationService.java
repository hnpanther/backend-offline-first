package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.config.LdapAuthProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Authenticates a user against Active Directory via LDAP simple bind using the user's own
 * credentials ({@code username@domain}).
 *
 * <h2>Built for the network it runs on</h2>
 *
 * <p>The same shape the file-management application arrived at against the same kind of
 * domain, so one deployment recipe serves both:
 *
 * <ul>
 *   <li><b>Several domain controllers.</b> {@code url} may list more than one, separated by
 *       spaces or commas. They are handed to JNDI as one space-separated provider URL, which
 *       JNDI treats as a fail-over list: each is tried in order until a connection succeeds.</li>
 *   <li><b>Bounded waits.</b> A controller that is down but not refusing — a black hole — would
 *       otherwise hold every login for the operating system's TCP timeout before the next one is
 *       tried. {@code timeout-ms} bounds both the connection and the bind's answer; see
 *       {@link LdapAuthProperties#getTimeoutMs()} for why it is one number, not two.</li>
 *   <li><b>Self-signed certificates.</b> A truststore holding the controllers' certificates is
 *       given to JNDI through {@link LdapTrustStoreSocketFactory} — a pin — without touching the
 *       JVM's {@code cacerts}. {@code trust-self-signed} remains the loud, explicit "verify
 *       nothing" mode it always was.</li>
 *   <li><b>A balancer name or a bare IP.</b> {@code verify-hostname=false} switches off the name
 *       check and only that check; with a pin, identity is still proven by the handshake. Without
 *       a pin it is refused at start-up.</li>
 * </ul>
 *
 * <h2>Never an exception, always a verdict</h2>
 *
 * <p>{@link #bind} answers with one of three {@link BindResult}s and never throws: the directory
 * said yes, the directory said no, or the directory could not be asked. The distinction is what
 * lets {@link AppAuthenticationProvider} keep a HYBRID account's local password working through
 * an outage and tell an operator "the directory is unreachable" instead of "wrong password".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LdapAuthenticationService {

    /**
     * JNDI reads this system property <b>once</b>, in a static initialiser of its LDAP
     * {@code Connection} class, so it has to be set before the first bind — which is why it is
     * set from {@link #prepare()} at start-up and nowhere else. It is also the only way to switch
     * the name check off: the JDK enforces endpoint identification for LDAPS even when the
     * socket factory's trust manager accepts every certificate.
     */
    static final String DISABLE_ENDPOINT_IDENTIFICATION =
            "com.sun.jndi.ldap.object.disableEndpointIdentification";

    /** What one bind attempt found out. */
    public enum BindResult {
        /** The directory accepted the credentials. */
        AUTHENTICATED,
        /** The directory answered and refused: wrong password, unknown, disabled, locked, expired. */
        REJECTED,
        /** The directory could not be asked: not configured, unreachable, timed out, TLS failed. */
        UNAVAILABLE
    }

    /** Active Directory's {@code data NNN} sub-codes on an {@code error code 49} refusal. */
    private static final Map<String, String> DIRECTORY_SUB_CODES = Map.of(
            "525", "no such user",
            "52e", "wrong password",
            "530", "logon not permitted at this time",
            "531", "logon not permitted from this workstation",
            "532", "password expired",
            "533", "account disabled",
            "701", "account expired",
            "773", "password must be changed",
            "775", "account locked out");

    private static final Pattern DIRECTORY_SUB_CODE = Pattern.compile("data ([0-9a-fA-F]{3})");

    private final LdapAuthProperties properties;

    /**
     * Turns the configuration into what JNDI needs, once, and says what it did at start-up —
     * the one moment somebody is looking at the log — rather than on the first failed login.
     *
     * @throws IllegalStateException for a configuration that would verify nothing while
     *         claiming to: {@code verify-hostname=false} with neither a truststore nor
     *         {@code trust-self-signed}, or a truststore that cannot be read. A boot failure that
     *         names the setting beats a login failure that names nothing.
     */
    @PostConstruct
    void prepare() {
        if (!properties.isEnabled()) {
            return;
        }
        String servers = providerUrlOf(properties.getUrl());
        if (servers.isEmpty()) {
            log.warn("Active Directory is enabled but app.auth.ldap.url is blank; every directory "
                    + "login will be reported as unavailable and local accounts are unaffected");
            return;
        }
        boolean ldaps = allLdaps(servers);
        if (!ldaps && Arrays.stream(servers.split(" ")).anyMatch(LdapAuthenticationService::isLdaps)) {
            log.warn("Active Directory servers mix ldaps:// and ldap:// ({}): the TLS trust settings "
                    + "are not applied to a mixed list, and every login that fails over to a plain "
                    + "ldap:// server sends the password in the clear", servers);
        }

        String trust;
        if (ldaps && properties.isTrustSelfSigned()) {
            System.setProperty(DISABLE_ENDPOINT_IDENTIFICATION, "true");
            trust = "none (trust-self-signed)";
            log.warn("Active Directory TLS verifies NOTHING (app.auth.ldap.trust-self-signed=true): the "
                    + "connection is encrypted to whichever server answers, and a machine on the path "
                    + "can read every password. Export the controllers' certificates into a truststore "
                    + "(app.auth.ldap.truststore) to close this.");
            if (properties.hasTruststore()) {
                log.warn("app.auth.ldap.truststore is set but ignored while trust-self-signed is true");
            }
        } else if (ldaps && properties.hasTruststore()) {
            LdapTrustStoreSocketFactory.configure(Path.of(properties.getTruststore()),
                    properties.getTruststorePassword(), properties.getTruststoreType());
            trust = "pinned to " + properties.getTruststore();
            log.info("Active Directory TLS trust: {} ({})", properties.getTruststore(),
                    properties.getTruststoreType());
            if (!properties.isVerifyHostname()) {
                System.setProperty(DISABLE_ENDPOINT_IDENTIFICATION, "true");
                log.warn("Active Directory hostname verification is off; the servers are identified "
                        + "by the truststore alone");
            }
        } else if (ldaps && !properties.isVerifyHostname()) {
            throw new IllegalStateException("app.auth.ldap.verify-hostname=false requires "
                    + "app.auth.ldap.truststore (or trust-self-signed=true): without one the server's "
                    + "identity would be checked by nothing at all");
        } else {
            trust = ldaps ? "JVM default truststore" : "n/a (plaintext ldap://)";
        }

        log.info("Active Directory authentication: domain={}, servers={}, timeout {} ms per controller, trust={}",
                properties.getDomain(), servers, properties.getTimeoutMs(), trust);
    }

    /** {@code true} only when the directory accepted the credentials. See {@link #bind}. */
    public boolean authenticate(String username, String password) {
        return bind(username, password) == BindResult.AUTHENTICATED;
    }

    /**
     * One bind, one verdict, no exception.
     *
     * <p>An empty password is refused here, before the network: LDAP treats a bind with a name
     * and no password as an <em>anonymous</em> bind, which Active Directory accepts — so
     * forwarding it would have signed in anyone who knew a username.
     */
    public BindResult bind(String username, String password) {
        if (!properties.isEnabled()) {
            log.warn("Active Directory bind attempted but app.auth.ldap.enabled=false");
            return BindResult.UNAVAILABLE;
        }
        String servers = providerUrlOf(properties.getUrl());
        String domain = properties.getDomain();
        if (servers.isEmpty() || domain == null || domain.isBlank()) {
            log.warn("Active Directory misconfigured: app.auth.ldap.url or app.auth.ldap.domain is blank");
            return BindResult.UNAVAILABLE;
        }
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return BindResult.REJECTED;
        }

        String principal = username.trim() + "@" + domain.trim();
        Hashtable<String, Object> env = contextEnvironment();
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, password);

        log.debug("Active Directory bind as {} via {}", principal, servers);
        try {
            new InitialDirContext(env).close();
            return BindResult.AUTHENTICATED;
        } catch (AuthenticationException e) {
            log.warn("Active Directory refused {} via {}: {}", principal, servers,
                    describeDirectoryError(e.getMessage()));
            return BindResult.REJECTED;
        } catch (NamingException e) {
            log.error("Active Directory is unreachable via {} — local passwords still work ({}: {})",
                    servers, e.getClass().getSimpleName(), e.getMessage());
            return BindResult.UNAVAILABLE;
        } catch (RuntimeException e) {
            // A socket factory that refused to build, a JNDI bug — nothing a password could
            // have caused. Logged with its stack because it is a defect, not an outage.
            log.error("Active Directory bind failed before reaching the directory ({}) — local "
                    + "passwords still work", servers, e);
            return BindResult.UNAVAILABLE;
        }
    }

    /**
     * The JNDI environment every bind is made with, before the credentials go in.
     * Package-private so a test can read what JNDI is handed rather than infer it from a bind.
     */
    Hashtable<String, Object> contextEnvironment() {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, providerUrlOf(properties.getUrl()));
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        // One value for both. JNDI uses the CONNECT timeout for the initial bind's answer too,
        // so the read timeout never governs anything this service does; it is set only so a
        // future operation on the context would not wait forever.
        if (properties.getTimeoutMs() > 0) {
            String timeout = String.valueOf(properties.getTimeoutMs());
            env.put("com.sun.jndi.ldap.connect.timeout", timeout);
            env.put("com.sun.jndi.ldap.read.timeout", timeout);
        }
        String socketFactory = socketFactoryClass();
        if (socketFactory != null) {
            env.put("java.naming.ldap.factory.socket", socketFactory);
        }
        return env;
    }

    /**
     * Which TLS socket factory JNDI gets, or null for the JVM's default.
     *
     * <p>Only on an all-{@code ldaps://} list, for the reason {@link #shouldTrustAllCertificates}
     * documents; then {@code trust-self-signed} wins over a truststore, and a truststore over the
     * JVM's own trust.
     */
    String socketFactoryClass() {
        String servers = providerUrlOf(properties.getUrl());
        if (!allLdaps(servers)) {
            return null;
        }
        if (properties.isTrustSelfSigned()) {
            return TrustAllLdapSslSocketFactory.class.getName();
        }
        if (properties.hasTruststore()) {
            return LdapTrustStoreSocketFactory.class.getName();
        }
        return null;
    }

    /** Spaces or commas between servers in the configuration; one space between them for JNDI. */
    public static String providerUrlOf(String configured) {
        if (configured == null) {
            return "";
        }
        return Arrays.stream(configured.trim().split("[\\s,]+"))
                .filter(server -> !server.isBlank())
                .collect(Collectors.joining(" "));
    }

    /**
     * Whether to hand JNDI the trust-everything SSL socket factory.
     *
     * <p><b>The scheme has to be part of this decision, and leaving it out was a real trap.</b>
     * {@code java.naming.ldap.factory.socket} is used by the JNDI LDAP provider for <em>every</em>
     * connection, not only for {@code ldaps://}. Setting it on a plain {@code ldap://} URL makes
     * the provider open an <b>SSL</b> socket to port 389 — verified on the wire: the first bytes
     * sent become {@code 16 03 03 …}, a TLS ClientHello, where a domain controller is waiting for
     * a plaintext BER BindRequest ({@code 30 …}). The bind then fails every time, with a TLS
     * handshake error that says nothing about the actual cause.
     *
     * <p>Since {@code trust-self-signed} ships as {@code true}, that made "point this at a
     * plaintext DC" a configuration nobody could get working without also knowing to turn off a
     * flag whose name only mentions certificates. The scheme check removes the interaction: the
     * factory is a property of LDAPS, so it is only applied to LDAPS — and with a list, only when
     * <em>every</em> server is LDAPS, because the factory applies to all of them alike.
     *
     * <p>Package-private so {@code LdapSocketFactorySelectionTest} can assert each combination
     * directly rather than inferring it from a failed bind.
     */
    static boolean shouldTrustAllCertificates(String url, boolean trustSelfSigned) {
        return trustSelfSigned && allLdaps(providerUrlOf(url));
    }

    /** True when the list is non-empty and every server on it is {@code ldaps://}. */
    static boolean allLdaps(String providerUrl) {
        if (providerUrl == null || providerUrl.isBlank()) {
            return false;
        }
        return Arrays.stream(providerUrl.split(" ")).allMatch(LdapAuthenticationService::isLdaps);
    }

    /** Case-insensitive {@code ldaps://} test that does not depend on the default locale. */
    private static boolean isLdaps(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        return trimmed.regionMatches(true, 0, "ldaps://", 0, "ldaps://".length());
    }

    /**
     * Sorts a bind failure into a verdict. Package-private for the test that pins the table.
     *
     * <p>Only {@link AuthenticationException} — LDAP result code 49 — is a refusal by the
     * directory. Everything else, a {@code CommunicationException}, a
     * {@code ServiceUnavailableException}, an {@code AuthenticationNotSupportedException} from a
     * controller that insists on signing, or a runtime failure of our own, is the directory
     * <em>not having been asked</em>, and must never be reported as a wrong password.
     */
    static BindResult classify(Throwable failure) {
        if (failure instanceof AuthenticationException) {
            return BindResult.REJECTED;
        }
        return BindResult.UNAVAILABLE;
    }

    /**
     * The refusal in words, for the log. Active Directory answers every refusal with result code
     * 49 and hides the actual reason in a {@code data NNN} sub-code inside the diagnostic message;
     * this names it, so "wrong password" and "account locked out" stop looking identical in the
     * log. Never shown to the user — the login page must not reveal account state.
     */
    static String describeDirectoryError(String message) {
        if (message == null || message.isBlank()) {
            return "no diagnostic message";
        }
        Matcher m = DIRECTORY_SUB_CODE.matcher(message);
        if (m.find()) {
            String code = m.group(1).toLowerCase(Locale.ROOT);
            String meaning = DIRECTORY_SUB_CODES.get(code);
            if (meaning != null) {
                return meaning + " (data " + code + ")";
            }
        }
        return message.trim();
    }
}
