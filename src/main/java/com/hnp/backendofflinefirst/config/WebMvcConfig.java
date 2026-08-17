package com.hnp.backendofflinefirst.config;

import com.hnp.backendofflinefirst.web.support.ListStateRedirectInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC wiring for the admin web panel.
 *
 * <p>Deliberately scoped away from {@code /api/**}: the mobile API is stateless JSON and returns
 * no views at all, so nothing here can reach it. Keeping the exclusion explicit rather than
 * relying on that is the point — an interceptor that quietly started applying to the sync
 * endpoints would be a bad day.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ListStateRedirectInterceptor listStateRedirectInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(listStateRedirectInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/api/**", "/actuator/**");
    }
}
