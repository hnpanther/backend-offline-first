package com.hnp.backendofflinefirst.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppAuthenticationProviderTest {

    @Test
    void normalizeUsernameStripsDomainSuffix() {
        assertThat(AppAuthenticationProvider.normalizeUsername("h.nikouei@site.local")).isEqualTo("h.nikouei");
        assertThat(AppAuthenticationProvider.normalizeUsername("  h.nikouei  ")).isEqualTo("h.nikouei");
        assertThat(AppAuthenticationProvider.normalizeUsername("admin")).isEqualTo("admin");
    }
}
