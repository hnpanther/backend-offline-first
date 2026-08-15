package com.hnp.backendofflinefirst.support;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.SystemRoleCapabilities;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class WithAppUserSecurityContextFactory implements WithSecurityContextFactory<WithAppUser> {

    @Override
    public SecurityContext createSecurityContext(WithAppUser annotation) {
        User user = new User();
        user.setId(1L);
        user.setUsername(annotation.username());
        user.setPersonnelCode("PC-" + java.util.UUID.randomUUID());
        user.setFullName(annotation.fullName());
        user.setActive(true);

        Set<String> roleCodes = Arrays.stream(annotation.roles()).collect(Collectors.toSet());

        // Delegates to TestPrincipals so "what does this role hold" is answered in exactly one
        // place across the whole suite — including the capabilities the V3 seed grants, which a
        // hand-built principal would otherwise lack entirely.
        AppUserDetails principal = TestPrincipals.of(user, roleCodes, Arrays.asList(annotation.authorities()));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
        return context;
    }
}
