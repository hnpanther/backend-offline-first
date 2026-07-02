package com.hnp.backendofflinefirst.support;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class WithAppUserSecurityContextFactory implements WithSecurityContextFactory<WithAppUser> {

    @Override
    public SecurityContext createSecurityContext(WithAppUser annotation) {
        User user = new User();
        user.setId(1L);
        user.setUsername(annotation.username());
        user.setFullName(annotation.fullName());
        user.setActive(true);

        Set<String> roleCodes = Arrays.stream(annotation.roles()).collect(Collectors.toSet());
        var authorities = Arrays.stream(annotation.authorities())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        AppUserDetails principal = new AppUserDetails(user, roleCodes,
                authorities.stream().map(a -> a.getAuthority()).collect(Collectors.toSet()));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, authorities));
        return context;
    }
}
