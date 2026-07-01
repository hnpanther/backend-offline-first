package com.hnp.backendofflinefirst.support;

import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithAppUserSecurityContextFactory.class)
public @interface WithAppUser {
    String username() default "tester";
    String fullName() default "Test User";
    String[] authorities() default {};
    String[] roles() default {};
}
