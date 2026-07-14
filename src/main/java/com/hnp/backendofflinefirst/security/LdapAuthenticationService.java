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
        if (properties.isTrustSelfSigned()) {
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
}
