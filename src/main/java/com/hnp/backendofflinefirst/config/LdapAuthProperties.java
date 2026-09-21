package com.hnp.backendofflinefirst.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How the application reaches Active Directory.
 *
 * <p>Shaped for the network it actually runs on rather than for a lab — the same shape the
 * file-management application arrived at after being deployed against the same kind of domain:
 * several controllers behind one name, each with its own self-signed certificate, not all of
 * them necessarily listening on 636. Every setting below maps onto one part of that.
 *
 * <p>The names that were already deployed ({@code enabled}, {@code url}, {@code domain},
 * {@code timeout-ms}, {@code trust-self-signed}) keep their meaning; the rest are additions with
 * defaults that change nothing for an installation that does not set them.
 */
@Component
@ConfigurationProperties(prefix = "app.auth.ldap")
@Getter
@Setter
public class LdapAuthProperties {

    /** When false, AD bind is skipped (LOCAL-only deployments). */
    private boolean enabled = false;

    /**
     * One or more servers, separated by spaces or commas, e.g.
     * {@code ldaps://dc01.site.local:636 ldaps://dc02.site.local:636}.
     *
     * <p>JNDI treats the list as a fail-over list: each is tried in order until one connects. A
     * controller whose port is closed is skipped in the time a refused connection takes; one
     * that is down but not refusing is bounded by {@link #connectTimeoutMs}. List the
     * controllers, not the balancer name in front of them — see {@link #verifyHostname}.
     */
    private String url = "";

    /** Domain suffix appended to username for bind, e.g. site.local → h.nikouei@site.local */
    private String domain = "";

    /**
     * How long one controller may take, both to accept the TCP connection and to answer the
     * bind, before the next controller on the list is tried — or, with none left, the directory
     * is reported unavailable.
     *
     * <p>One setting rather than the connect/read pair JNDI nominally offers, on purpose: JNDI
     * applies its <em>connect</em> timeout to the initial bind as well (the read timeout only
     * governs later operations, and this service performs none), so a separate read timeout
     * would be a knob that turns nothing. Measured: with {@code read.timeout=500} and
     * {@code connect.timeout=3000}, a controller that accepts and never answers is abandoned
     * after 3000 ms, "timeout used: 3000 ms". This value is handed to JNDI as both.
     */
    private int timeoutMs = 5000;

    /**
     * When true, LDAPS connections accept <b>any</b> certificate and <b>any</b> name — the
     * connection is encrypted, but to whoever answered. Prefer {@link #truststore}, which pins
     * the controllers' own certificates and proves which server answered.
     */
    private boolean trustSelfSigned = false;

    /**
     * A PKCS12 (or JKS) file holding the domain controllers' certificates, or their issuing CA.
     * With it, TLS trusts those certificates and nothing else — a pin — without touching the
     * JVM's own {@code cacerts} or its command line. Ignored while {@link #trustSelfSigned} is
     * true, and on plain {@code ldap://} URLs, which have no certificate to check.
     */
    private String truststore = "";

    private String truststorePassword = "";

    private String truststoreType = "PKCS12";

    /**
     * Whether the host in the URL must appear on the certificate. Switching it off is what makes
     * a balancer name or a bare IP usable with a pinned truststore: the certificate is still
     * verified against the pin, only the name check is skipped. Refused at start-up without a
     * truststore (or {@link #trustSelfSigned}), because encryption to a server whose identity
     * nothing checks is not worth the false comfort.
     */
    private boolean verifyHostname = true;

    /** True when a truststore path is configured. */
    public boolean hasTruststore() {
        return truststore != null && !truststore.isBlank();
    }
}
