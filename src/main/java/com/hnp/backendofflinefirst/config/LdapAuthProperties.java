package com.hnp.backendofflinefirst.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth.ldap")
@Getter
@Setter
public class LdapAuthProperties {

    /** When false, AD bind is skipped (LOCAL-only deployments). */
    private boolean enabled = false;

    /** e.g. ldap://dc.site.local:389 or ldaps://dc.site.local:636 */
    private String url = "";

    /** Domain suffix appended to username for bind, e.g. site.local → h.nikouei@site.local */
    private String domain = "";

    private int timeoutMs = 5000;

    /**
     * When true, LDAPS connections accept self-signed / untrusted server certificates.
     * Prefer importing the AD CA into the JVM truststore in production.
     */
    private boolean trustSelfSigned = false;
}
