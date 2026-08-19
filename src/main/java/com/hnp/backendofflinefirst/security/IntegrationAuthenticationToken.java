package com.hnp.backendofflinefirst.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * An authenticated integration client in the security context.
 *
 * <p>Holds a single authority, {@link #AUTHORITY}. It is deliberately not one of the
 * {@code METHOD:/path} permissions: those are granted to roles from the {@code permissions}
 * table, and a key is not a role. Nothing in the application grants this authority, so no
 * user — administrator included — can ever hold it, and no integration client can ever hold a
 * user's. That is the separation the requirement asks for, expressed as a type rather than as
 * a convention.
 *
 * <p>Credentials are erased: the token never carries the presented key.
 */
public class IntegrationAuthenticationToken extends AbstractAuthenticationToken {

    /** The only authority an integration request ever carries. */
    public static final String AUTHORITY = "INTEGRATION_API";

    private final IntegrationClient client;

    public IntegrationAuthenticationToken(IntegrationClient client) {
        super(List.of(new SimpleGrantedAuthority(AUTHORITY)));
        this.client = client;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public IntegrationClient getPrincipal() {
        return client;
    }

    @Override
    public String getName() {
        return client.clientName();
    }
}
