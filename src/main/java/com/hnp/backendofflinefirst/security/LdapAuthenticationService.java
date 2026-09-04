package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.config.LdapAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;

/**
 * Authenticates a user against Active Directory via LDAP simple bind
 * using the user's own credentials ({@code username@domain}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LdapAuthenticationService {

    private final LdapAuthProperties properties;

    public boolean authenticate(String username, String password) {
        if (!properties.isEnabled()) {
            log.warn("LDAP authentication attempted but app.auth.ldap.enabled=false");
            return false;
        }
        String url = properties.getUrl();
        String domain = properties.getDomain();
        if (url == null || url.isBlank() || domain == null || domain.isBlank()) {
            log.warn("LDAP authentication misconfigured: url or domain is missing");
            return false;
        }
        if (password == null || password.isBlank()) {
            return false;
        }

        String principal = username.trim() + "@" + domain.trim();
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url.trim());
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, password);
        if (properties.getTimeoutMs() > 0) {
            String timeout = String.valueOf(properties.getTimeoutMs());
            env.put("com.sun.jndi.ldap.connect.timeout", timeout);
            env.put("com.sun.jndi.ldap.read.timeout", timeout);
        }
        if (shouldTrustAllCertificates(url, properties.isTrustSelfSigned())) {
            env.put("java.naming.ldap.factory.socket", TrustAllLdapSslSocketFactory.class.getName());
        }

        try {
            InitialDirContext ctx = new InitialDirContext(env);
            ctx.close();
            return true;
        } catch (NamingException e) {
            log.warn("LDAP bind failed for {} via {}: {}", principal, url.trim(), e.getMessage());
            return false;
        }
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
     * factory is a property of LDAPS, so it is only applied to LDAPS.
     *
     * <p>This cannot change a working deployment. The only combination whose behaviour differs is
     * plain {@code ldap://} with {@code trust-self-signed=true}, which could not connect at all
     * before. Package-private so {@code LdapSocketFactorySelectionTest} can assert each
     * combination directly rather than inferring it from a failed bind.
     */
    static boolean shouldTrustAllCertificates(String url, boolean trustSelfSigned) {
        return trustSelfSigned && isLdaps(url);
    }

    /** Case-insensitive {@code ldaps://} test that does not depend on the default locale. */
    private static boolean isLdaps(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        return trimmed.regionMatches(true, 0, "ldaps://", 0, "ldaps://".length());
    }
}
