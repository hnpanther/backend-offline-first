package com.hnp.backendofflinefirst.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the mobile REST API ({@code /api/**}). Scanning is scoped to that
 * package via {@code springdoc.paths-to-match}. Enabled in every environment, including
 * production, but the spec/UI endpoints are admin-only — see {@code WebSecurityConfig}.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI mobileApiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Backend Offline-First — Mobile API")
                        .description("REST API for the offline-first operator mobile app. "
                                + "Obtain a token via POST /api/auth/login, then use \"Authorize\" "
                                + "with it (Bearer) to try endpoints that require it.")
                        .version("v1"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
