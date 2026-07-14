package com.hnp.backendofflinefirst.security;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocketFactory;

import static org.assertj.core.api.Assertions.assertThat;

class TrustAllLdapSslSocketFactoryTest {

    @Test
    void createsDefaultSslSocketFactory() {
        SSLSocketFactory factory = TrustAllLdapSslSocketFactory.getDefault();
        assertThat(factory).isNotNull();
        assertThat(factory.getDefaultCipherSuites()).isNotEmpty();
    }
}
